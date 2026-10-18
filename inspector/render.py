"""Headless Chromium rendering of HTML we already fetched ourselves.

Chromium must never touch the network. Layers:
  1. HTML is served by the route handler itself as the document at a fake `.invalid` origin,
     with a `default-src 'none'` CSP (inline script/style only) so subresources are refused
     inside the renderer before they even reach the router;
  2. context.route("**/*") aborts every other request and answers later navigations with a
     local 204 (the page stays put); route_web_socket swallows WebSockets;
  3. the context is offline; service workers blocked; downloads refused;
  4. launch flags: dead proxy (127.0.0.1:9) for everything incl. loopback, all DNS -> NOTFOUND,
     WebRTC restricted to proxied UDP (i.e. none);
  5. JS runs only with --jitless (no JIT/WASM: removes a large class of V8 exploits);
  6. a fresh context per call (no cookies/storage), closed in finally; hard timeout; secrets are
     scrubbed from Chromium's environment.
The rendered DOM is read with page.content(), which runs in Playwright's isolated utility world,
so page scripts cannot monkey-patch what we read.
"""

from __future__ import annotations

import asyncio
import logging
import os

from fetch import UA

log = logging.getLogger("inspector.render")
MAX_RENDERED_CHARS = 2 * 1024 * 1024
SECRET_ENV = ("INSPECTOR_SECRET", "SAFE_BROWSING_API_KEY", "URLHAUS_AUTH_KEY")
ARGS = [
    "--js-flags=--jitless",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--proxy-server=http://127.0.0.1:9",
    "--proxy-bypass-list=<-loopback>",
    "--host-resolver-rules=MAP * ~NOTFOUND",
    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
    "--webrtc-ip-handling-policy=disable_non_proxied_udp",
    "--no-pings",
    "--disable-breakpad",
    "--mute-audio",
    "--disable-remote-fonts",
]


DOC_URL = "https://render.sentinel.invalid/"  # never resolvable; only ever fulfilled locally


def _router(html: str):
    """Serve `html` once as the top-level document at DOC_URL, answer any other navigation
    locally with 204 (the browser then stays on the current document, so `location.href=...`
    cannot blank the DOM), and abort everything else. Nothing reaches the network."""
    served = False

    async def handle(route) -> None:
        nonlocal served
        req = route.request
        try:
            if (not served and req.url == DOC_URL and req.is_navigation_request()
                    and req.frame.parent_frame is None):
                served = True
                await route.fulfill(status=200, content_type="text/html; charset=utf-8", body=html,
                                    headers={"Content-Security-Policy": "default-src 'none'; "
                                             "script-src 'unsafe-inline'; style-src 'unsafe-inline'"})
            elif req.is_navigation_request():
                await route.fulfill(status=204, body="")
            else:
                await route.abort()
        except Exception:
            pass

    return handle


async def _ws_close(ws) -> None:
    try:
        await ws.close()
    except Exception:
        pass


class Renderer:
    """One shared browser process; a new incognito context per render."""

    def __init__(self, sandbox: bool | None = None):
        self._sandbox = sandbox  # None: try Chromium's sandbox first, fall back without it
        self._pw = None
        self._browser = None
        self._lock = asyncio.Lock()
        self.sandboxed: bool | None = None

    async def _ensure(self):
        async with self._lock:
            if self._browser is not None and self._browser.is_connected():
                return self._browser
            from playwright.async_api import async_playwright
            if self._pw is None:
                self._pw = await async_playwright().start()
            env = {k: v for k, v in os.environ.items() if k not in SECRET_ENV}
            last: Exception | None = None
            for sb in ((True, False) if self._sandbox is None else (self._sandbox,)):
                try:
                    self._browser = await self._pw.chromium.launch(
                        headless=True, chromium_sandbox=sb, args=ARGS, env=env, timeout=20000,
                        handle_sigint=False, handle_sigterm=False, handle_sighup=False)
                    self.sandboxed = sb
                    if not sb:
                        log.warning("chromium sandbox unavailable; running with --no-sandbox (see README)")
                    return self._browser
                except Exception as e:  # pragma: no cover - environment specific
                    last = e
            raise last or RuntimeError("chromium launch failed")

    async def start(self) -> bool:
        try:
            await self._ensure()
            return True
        except Exception as e:
            log.warning("chromium unavailable, static analysis only: %s", type(e).__name__)
            return False

    async def _reset(self) -> None:
        b, self._browser = self._browser, None
        if b is not None:
            try:
                await asyncio.wait_for(b.close(), 5)
            except Exception:
                pass

    async def render(self, html: str, timeout: float = 3.0) -> str | None:
        """Return the rendered DOM as HTML, or None on any failure/timeout."""
        if timeout <= 0.2:
            return None
        ctx = None
        healthy = True
        try:
            async with asyncio.timeout(timeout):
                browser = await self._ensure()
                ctx = await browser.new_context(
                    java_script_enabled=True, offline=True, accept_downloads=False,
                    service_workers="block", viewport={"width": 412, "height": 915}, user_agent=UA,
                    locale="en-IN", permissions=[], ignore_https_errors=False)
                await ctx.route("**/*", _router(html))
                try:
                    await ctx.route_web_socket("**/*", _ws_close)
                except Exception:  # older Playwright: CSP + offline + dead proxy still block it
                    pass
                page = await ctx.new_page()
                await page.goto(DOC_URL, wait_until="commit", timeout=timeout * 1000)
                try:  # a page whose parser was stopped never fires DOMContentLoaded
                    await page.wait_for_load_state("domcontentloaded", timeout=max(0.1, timeout / 2) * 1000)
                except Exception:
                    pass
                await asyncio.sleep(0.25)  # let short inline timers build the DOM
                if page.url != DOC_URL:
                    return None
                content = await page.content()
                return content[:MAX_RENDERED_CHARS]
        except Exception:  # includes TimeoutError from the hard render deadline
            return None
        finally:
            if ctx is not None:
                try:
                    await asyncio.wait_for(ctx.close(), 3)
                except Exception:
                    healthy = False  # a wedged renderer: restart the browser
            if not healthy:
                await self._reset()

    async def close(self) -> None:
        await self._reset()
        if self._pw is not None:
            try:
                await self._pw.stop()
            except Exception:
                pass
            self._pw = None
