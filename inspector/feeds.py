"""Threat-feed lookups (Google Safe Browsing v4, URLhaus).

Hits only ever ADD risk. Every failure (no key, network error, non-200, bad JSON, oversized
response, timeout) yields no hits; a clean or failed lookup carries no "safe" meaning.
Privacy note: the inspected URLs are sent to Google / abuse.ch.
"""

from __future__ import annotations

import asyncio
import json
import os
import re

import httpx

SB_URL = "https://safebrowsing.googleapis.com/v4/threatMatches:find"
URLHAUS_URL = "https://urlhaus-api.abuse.ch/v1/url/"
MAX_RESPONSE = 64 * 1024
MAX_URLS = 4
_BAD = re.compile(r"[^A-Za-z0-9_]+")
_SB_TYPES = ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"]


def _label(s: object) -> str | None:
    if not isinstance(s, str):
        return None
    out = _BAD.sub("_", s.strip())[:40].strip("_")
    return out or None


async def _post_json(client: httpx.AsyncClient, url: str, **kw) -> object | None:
    async with client.stream("POST", url, **kw) as r:
        if r.status_code != 200:
            return None
        buf = bytearray()
        async for chunk in r.aiter_raw():
            buf += chunk
            if len(buf) > MAX_RESPONSE:
                return None
    return json.loads(bytes(buf))


class Feeds:
    def __init__(self, sb_key: str | None = None, urlhaus_key: str | None = None, timeout: float = 3.0):
        self.sb_key = (sb_key or "").strip() or None
        self.urlhaus_key = (urlhaus_key or "").strip() or None
        self.timeout = timeout

    @classmethod
    def from_env(cls) -> "Feeds":
        return cls(os.environ.get("SAFE_BROWSING_API_KEY"), os.environ.get("URLHAUS_AUTH_KEY"))

    async def _safe_browsing(self, c: httpx.AsyncClient, urls: list[str]) -> list[str]:
        body = {"client": {"clientId": "sentinel-inspector", "clientVersion": "0.1.0"},
                "threatInfo": {"threatTypes": _SB_TYPES, "platformTypes": ["ANY_PLATFORM"],
                               "threatEntryTypes": ["URL"], "threatEntries": [{"url": u} for u in urls]}}
        data = await _post_json(c, SB_URL, params={"key": self.sb_key}, json=body)
        matches = data.get("matches") if isinstance(data, dict) else None
        out = []
        for m in matches if isinstance(matches, list) else []:
            if isinstance(m, dict) and (lab := _label(m.get("threatType"))):
                out.append(f"safe_browsing:{lab}")
        return out

    async def _urlhaus(self, c: httpx.AsyncClient, url: str) -> list[str]:
        headers = {"Auth-Key": self.urlhaus_key} if self.urlhaus_key else {}
        data = await _post_json(c, URLHAUS_URL, data={"url": url}, headers=headers)
        if isinstance(data, dict) and data.get("query_status") == "ok":
            return [f"urlhaus:{_label(data.get('threat')) or 'listed'}"]
        return []

    async def check(self, urls: list[str]) -> list[str]:
        urls = [u for u in dict.fromkeys(urls) if isinstance(u, str) and u][:MAX_URLS]
        if not urls:
            return []
        try:
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False, follow_redirects=False,
                                         headers={"User-Agent": "sentinel-inspector/0.1",
                                                  "Accept-Encoding": "identity"}) as c:
                jobs = ([self._safe_browsing(c, urls)] if self.sb_key else []) + [self._urlhaus(c, u) for u in urls]
                async with asyncio.timeout(self.timeout + 0.5):
                    results = await asyncio.gather(*jobs, return_exceptions=True)
        except Exception:
            return []
        hits = {h for r in results if isinstance(r, list) for h in r}
        return sorted(hits)
