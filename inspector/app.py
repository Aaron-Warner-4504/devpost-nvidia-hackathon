"""Sentinel link inspector: POST /inspect {url} -> LinkReport JSON.

Run: uvicorn --factory app:create_app --host 0.0.0.0 --port 8080
Requires INSPECTOR_SECRET (>= 16 chars); callers send it in X-Inspector-Secret.
"""

from __future__ import annotations

import asyncio
import ctypes
import hmac
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from urllib.parse import urlsplit

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr, ValidationError

from detect import TEXT_CAP, analyze_html, brand_mismatch, decode_body
from feeds import Feeds
from fetch import FetchResult, _is_html, fetch
from guard import Blocked, Policy, Resolver, default_resolver
from render import SECRET_ENV, Renderer

log = logging.getLogger("inspector")
MIN_SECRET_LEN = 16
MAX_REQUEST_BYTES = 4096
FEED_RESERVE = 0.5  # seconds of the total budget kept for the final-URL feed lookup


class LinkReport(BaseModel):
    """Must stay field-for-field identical to backend app/pipeline/link_client.LinkReport."""
    final_url: str
    redirect_chain: list[str] = Field(default_factory=list)
    status: int | None = None
    title: str | None = None
    has_password_field: bool = False
    has_otp_or_upi_or_card_field: bool = False
    triggers_download: bool = False
    apk_download: bool = False
    brand_mismatch: str | None = None
    threat_feed_hits: list[str] = Field(default_factory=list)
    text_excerpt: str = ""
    fetched_ok: bool = False


class InspectIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: StrictStr = Field(max_length=2048)


def _harden_process() -> None:
    """Make this process non-dumpable so a compromised (unsandboxed) Chromium child running as the
    same uid cannot read our /proc/<pid>/environ or memory (where the secrets live)."""
    try:
        ctypes.CDLL(None, use_errno=True).prctl(4, 0, 0, 0, 0)  # PR_SET_DUMPABLE = 4
    except Exception:
        pass


class Inspector:
    def __init__(self, *, resolver: Resolver, policy: Policy, feeds, renderer: Renderer | None,
                 total_timeout: float, render_timeout: float):
        self.resolver, self.policy, self.feeds, self.renderer = resolver, policy, feeds, renderer
        self.total_timeout, self.render_timeout = total_timeout, render_timeout

    async def _feeds(self, urls: list[str], hits: set[str]) -> None:
        try:
            got = await self.feeds.check(urls)
            hits.update(h for h in got if isinstance(h, str) and len(h) <= 64)
        except Exception:
            pass

    async def run(self, url: str) -> LinkReport:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self.total_timeout
        rep = LinkReport(final_url=url[:2048])
        hits: set[str] = set()
        try:
            start = self.policy.parse(url)
        except Blocked as e:
            log.info("inspect rejected: %s", e)
            return rep
        rep.final_url = start.url
        tasks = [asyncio.create_task(self._feeds([start.url], hits))]
        fr = FetchResult(final_url=start.url)
        try:
            try:
                async with asyncio.timeout_at(deadline - FEED_RESERVE):
                    await fetch(start.url, resolver=self.resolver, policy=self.policy, result=fr)
            except TimeoutError:
                log.info("inspect timed out")
            if fr.ok:
                self._page(rep, fr)
                # an undecodable body (br/zstd/corrupt) is unknown content, never a "clean" page
                rep.fetched_ok = not fr.undecodable
                if (rep.fetched_ok and self.renderer is not None and not fr.triggers_download
                        and _is_html(fr.content_type, fr.body)):
                    # rendering has its own sub-budget; if it fails or times out, the static
                    # findings above stand (fetched_ok is already True)
                    budget = min(self.render_timeout, deadline - FEED_RESERVE - loop.time() - 0.5)
                    if budget > 0.3:
                        html = decode_body(fr.body, fr.content_type)
                        try:
                            async with asyncio.timeout(budget + 0.25):
                                rendered = await self.renderer.render(html, timeout=budget)
                        except TimeoutError:
                            rendered = None
                        if rendered is not None:
                            self._merge(rep, analyze_html(rendered))
            rep.redirect_chain = list(fr.chain)
            rep.final_url = fr.final_url or rep.final_url
            if rep.fetched_ok and rep.final_url != start.url:
                tasks.append(asyncio.create_task(self._feeds([rep.final_url], hits)))
            try:
                async with asyncio.timeout_at(deadline):
                    await asyncio.gather(*tasks, return_exceptions=True)
            except TimeoutError:
                pass
        finally:
            for t in tasks:
                t.cancel()
        if fr.error:
            log.info("inspect not fetched: %s", fr.error)
        if rep.fetched_ok:
            host = urlsplit(rep.final_url).hostname
            rep.brand_mismatch = brand_mismatch(rep.title or "", rep.text_excerpt, host,
                                                rep.has_password_field or rep.has_otp_or_upi_or_card_field)
        rep.threat_feed_hits = sorted(hits)
        return rep

    @staticmethod
    def _page(rep: LinkReport, fr: FetchResult) -> None:
        rep.status = fr.status
        rep.triggers_download = fr.triggers_download
        rep.apk_download = fr.apk_download
        if fr.undecodable or fr.triggers_download or not _is_html(fr.content_type, fr.body):
            return
        f = analyze_html(decode_body(fr.body, fr.content_type))
        rep.title = f.title
        rep.has_password_field = f.has_password
        rep.has_otp_or_upi_or_card_field = f.has_sensitive
        rep.text_excerpt = f.text

    @staticmethod
    def _merge(rep: LinkReport, f) -> None:
        # additive only: page scripts can add fields to what we saw statically, never hide them
        rep.has_password_field |= f.has_password
        rep.has_otp_or_upi_or_card_field |= f.has_sensitive
        rep.title = f.title or rep.title
        if f.text:
            rep.text_excerpt = f.text[:TEXT_CAP]


def create_app(*, secret: str | None = None, resolver: Resolver | None = None, feeds=None,
               render: bool = True, renderer=None, total_timeout: float = 10.0, render_timeout: float = 3.0,
               max_concurrency: int = 4, queue_timeout: float = 2.0,
               _test_allow: frozenset[tuple[str, int]] = frozenset()) -> FastAPI:
    """App factory. `_test_allow` (exact (ip, port) pairs) exists for tests only and can only be
    passed in code; there is no env var or request field that relaxes the SSRF guard."""
    secret = os.environ.get("INSPECTOR_SECRET", "") if secret is None else secret
    if not isinstance(secret, str) or len(secret.strip()) < MIN_SECRET_LEN:
        raise RuntimeError("INSPECTOR_SECRET must be set (at least 16 characters)")
    key = secret.encode()
    feeds = feeds if feeds is not None else Feeds.from_env()
    # Secrets now live only in Python objects. Remove them from the environment so that no child
    # process (Playwright's node driver copies os.environ; Chromium inherits from it) ever gets them.
    for k in SECRET_ENV:
        os.environ.pop(k, None)
    renderer = renderer if renderer is not None else (Renderer() if render else None)
    inspector = Inspector(resolver=resolver or default_resolver, policy=Policy(test_allow=frozenset(_test_allow)),
                          feeds=feeds, renderer=renderer,
                          total_timeout=total_timeout, render_timeout=render_timeout)
    sem = asyncio.Semaphore(max_concurrency)

    @asynccontextmanager
    async def lifespan(app):
        if renderer is not None:
            _harden_process()
            await renderer.start()
        try:
            yield
        finally:
            if renderer is not None:
                await renderer.close()

    app = FastAPI(title="sentinel-inspector", docs_url=None, redoc_url=None, openapi_url=None,
                  lifespan=lifespan)
    app.state.inspector = inspector

    @app.get("/health")
    async def health():
        return {"ok": True}

    @app.post("/inspect")
    async def inspect(request: Request):
        got = request.headers.get("x-inspector-secret", "").encode("latin-1", "replace")
        if not hmac.compare_digest(got, key):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        cl = request.headers.get("content-length")
        if cl is not None and (not cl.isdigit() or int(cl) > MAX_REQUEST_BYTES):
            return JSONResponse({"error": "request too large"}, status_code=413)
        body = bytearray()
        async for chunk in request.stream():
            body += chunk
            if len(body) > MAX_REQUEST_BYTES:
                return JSONResponse({"error": "request too large"}, status_code=413)
        try:
            inp = InspectIn.model_validate(json.loads(bytes(body)))
        except (ValueError, ValidationError):
            return JSONResponse({"error": "invalid request"}, status_code=422)
        try:
            await asyncio.wait_for(sem.acquire(), queue_timeout)
        except TimeoutError:
            return JSONResponse({"error": "busy"}, status_code=503)
        t0 = time.monotonic()
        try:
            rep = await inspector.run(inp.url)
        except Exception as e:  # defence in depth: never 500 with details
            log.warning("inspect failed: %s", type(e).__name__)
            rep = LinkReport(final_url=inp.url)
        finally:
            sem.release()
        log.info("inspect done ok=%s in %.2fs", rep.fetched_ok, time.monotonic() - t0)
        return JSONResponse(rep.model_dump())

    return app
