"""Client for the sandboxed link inspector service (../inspector).

The backend NEVER fetches the inspected URL itself: it only POSTs {url} to the inspector on the
internal network with the shared secret. Any failure (unconfigured, network, timeout, non-200,
oversized or malformed response) degrades to LinkReport(final_url=url, fetched_ok=False); this
function never raises. Everything in the report is page-derived and UNTRUSTED: strings are capped
and stripped of control/format characters here as defence in depth.
"""

from __future__ import annotations

import json
import logging
import re
import time
import unicodedata
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel, Field

from app.config import Settings, settings as default_settings

log = logging.getLogger(__name__)
TIMEOUT = 12.0
MAX_RESPONSE_BYTES = 64 * 1024


class LinkReport(BaseModel):
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


def _clean(s: str | None, n: int) -> str | None:
    if s is None:
        return None
    s = "".join(" " if unicodedata.category(c) in ("Cc", "Cf", "Cs", "Co", "Cn") else c for c in s[: n * 4])
    return " ".join(s.split())[:n]


MAX_URL = 300
_FEED_HIT = re.compile(r"[a-z_]+:[A-Za-z0-9_]+")
_BRAND = re.compile(r"[A-Za-z0-9 .&'-]{1,64}")


def safe_url(u: object) -> bool:
    """Strict check for page-influenced URLs before anything downstream (agent, UI) sees them:
    http(s) only, printable ASCII without whitespace, <= 300 chars, parseable with a host."""
    if not isinstance(u, str) or not 0 < len(u) <= MAX_URL:
        return False
    if any(not (0x21 <= ord(c) <= 0x7E) for c in u):
        return False
    try:
        sp = urlsplit(u)
        sp.port  # noqa: B018  (raises ValueError for a malformed port)
        return sp.scheme in ("http", "https") and bool(sp.hostname)
    except ValueError:
        return False


def sanitize_report(r: LinkReport, url: str) -> LinkReport:
    """Validate/cap every page-influenced field. Invalid hops are dropped, an invalid final_url is
    replaced by the original url, malformed feed hits are dropped. Idempotent."""
    return r.model_copy(update={
        "final_url": r.final_url if safe_url(r.final_url) else url,
        "redirect_chain": [u for u in r.redirect_chain[:10] if safe_url(u)],
        "title": _clean(r.title, 200) or None,
        "brand_mismatch": b if (b := _clean(r.brand_mismatch, 64)) and _BRAND.fullmatch(b) else None,
        "threat_feed_hits": [h for h in r.threat_feed_hits[:20] if isinstance(h, str) and len(h) <= 64
                             and _FEED_HIT.fullmatch(h)],
        "text_excerpt": _clean(r.text_excerpt, 1500) or "",
    })


def inspect_link(url: str, settings: Settings | None = None) -> LinkReport:
    """Ask the inspector service to look at `url`. Never raises; never logs the secret."""
    failed = LinkReport(final_url=url, fetched_ok=False)
    s = settings or default_settings
    base, secret = (s.inspector_url or "").strip(), s.inspector_secret or ""
    try:
        sp = urlsplit(base)
        if not secret or sp.scheme not in ("http", "https") or not sp.hostname:
            return failed
        deadline = time.monotonic() + TIMEOUT
        with httpx.Client(timeout=httpx.Timeout(TIMEOUT), trust_env=False, follow_redirects=False) as c:
            with c.stream("POST", base.rstrip("/") + "/inspect", json={"url": url},
                          headers={"X-Inspector-Secret": secret,
                                   "Accept-Encoding": "identity"}) as r:
                if r.status_code != 200:
                    log.info("inspector returned HTTP %s", r.status_code)
                    return failed
                cl = r.headers.get("content-length")
                if cl is not None and (not cl.isdigit() or int(cl) > MAX_RESPONSE_BYTES):
                    return failed
                buf = bytearray()
                for chunk in r.iter_raw():
                    buf += chunk
                    if len(buf) > MAX_RESPONSE_BYTES or time.monotonic() > deadline:
                        return failed
        return sanitize_report(LinkReport.model_validate(json.loads(bytes(buf))), url)
    except Exception as e:  # message text could echo request details: log the type only
        log.info("inspector call failed: %s", type(e).__name__)
        return failed
