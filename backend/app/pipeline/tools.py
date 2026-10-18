"""Read-only agent tools. Every call goes through `run_tool`, which validates arguments against the
tool's JSON schema, never raises, and caps the result size. Tool inputs are attacker-influenced."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import urljoin, urlsplit

import httpx
from sqlalchemy import select

from app.config import Settings, settings as default_settings
from app.models import Override
from app.pipeline import link_client
from app.pipeline.extract import Entity
from app.pipeline.memory import history, related_entities
from app.pipeline.signals import DomainInfo

MAX_RESULT_CHARS = 4000
UNTRUSTED = "[untrusted web text] "
_ENTITY_TYPES = ["phone", "domain", "upi_id", "amount", "org", "claim"]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: dict
    func: Callable[..., Any]


@dataclass
class ToolContext:
    db: Any
    user: Any
    now: datetime
    settings: Settings = field(default_factory=lambda: default_settings)
    http_client_factory: Callable[[float], httpx.Client] | None = None

    def client(self, timeout: float) -> httpx.Client:
        if self.http_client_factory:
            return self.http_client_factory(timeout)
        return httpx.Client(timeout=timeout, follow_redirects=False)


# ---------------------------------------------------------------- domain validation
_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
_TLD = re.compile(r"[a-z]{2,24}|xn--[a-z0-9-]{2,20}")
_INTERNAL_TLDS = {"local", "localhost", "internal", "lan", "corp", "home", "test", "invalid",
                  "example", "intranet", "private", "onion", "arpa"}


def valid_domain(d: Any) -> bool:
    if not isinstance(d, str) or not 3 <= len(d) <= 253 or not d.isascii():
        return False
    labels = d.split(".")
    if len(labels) < 2 or not all(_LABEL.fullmatch(x) for x in labels):
        return False
    tld = labels[-1]
    return bool(_TLD.fullmatch(tld)) and tld not in _INTERNAL_TLDS


# ---------------------------------------------------------------- brands / lookalikes
BRANDS: dict[str, str] = {  # brand token -> canonical domains
    "hdfcbank": "hdfcbank.com", "sbi": "sbi.co.in", "icicibank": "icicibank.com",
    "axisbank": "axisbank.com", "paytm": "paytm.com", "phonepe": "phonepe.com",
    "amazon": "amazon.in", "indiapost": "indiapost.gov.in", "dhl": "dhl.com",
    "fedex": "fedex.com", "bluedart": "bluedart.com", "flipkart": "flipkart.com",
    "npci": "npci.org.in", "incometax": "incometax.gov.in", "uidai": "uidai.gov.in",
    "irctc": "irctc.co.in", "google": "google.com", "microsoft": "microsoft.com",
}
_BRAND_DOMAINS = set(BRANDS.values()) | {"amazon.com", "onlinesbi.sbi", "sbi.bank.in", "hdfc.com",
                                         "google.co.in", "microsoft.in"}
_SAFE_INFRA = {"amazonaws.com", "googleapis.com", "googleusercontent.com", "microsoftonline.com",
               "azurewebsites.net", "googlevideo.com", "gstatic.com"}
SHORTENERS = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "is.gd", "rb.gy", "ow.ly"}
_SLD = {"co", "gov", "org", "com", "net", "ac", "nic", "edu", "res"}


def _registrable(d: str) -> str:
    p = d.split(".")
    if len(p) >= 3 and len(p[-1]) == 2 and p[-2] in _SLD:
        return ".".join(p[-3:])
    return ".".join(p[-2:])


def _lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _variants(s: str) -> set[str]:
    s = s.replace("rn", "m").replace("vv", "w").replace("0", "o")
    return {s, s.replace("1", "l"), s.replace("1", "i")}


def lookalike_of(domain: str) -> str | None:
    if domain in _BRAND_DOMAINS or domain in SHORTENERS:
        return None
    if any(domain.endswith("." + b) for b in _BRAND_DOMAINS):
        return None
    reg = _registrable(domain)
    if reg in _SAFE_INFRA:
        return None
    name = reg.split(".")[0]
    labels = domain.split(".")[:-1]
    for token, canon in BRANDS.items():
        # (a) edit distance / homoglyphs on the registrable name
        limit = 2 if len(token) >= 6 else 1 if len(token) >= 4 else 0
        if any(_lev(v, token) <= limit for v in _variants(name)):
            return canon
        # (b) brand token inside an extended / hyphenated label, or in a subdomain label
        for lab in labels:
            for v in _variants(lab):
                parts = v.split("-")
                if len(token) >= 5 and token in v or token in parts:
                    return canon
    return None


# ---------------------------------------------------------------- RDAP
def _rdap_age(ctx: ToolContext, domain: str) -> int | None:
    try:
        with ctx.client(3.0) as c:
            url = f"https://rdap.org/domain/{domain}"
            for _ in range(4):
                r = c.get(url, headers={"accept": "application/rdap+json"})
                if r.status_code in (301, 302, 303, 307, 308):
                    nxt = urljoin(url, r.headers.get("location", ""))
                    sp = urlsplit(nxt)
                    if sp.scheme != "https" or not sp.hostname or not valid_domain(sp.hostname):
                        return None
                    url = nxt
                    continue
                if r.status_code != 200:
                    return None
                events = r.json().get("events")
                for e in events if isinstance(events, list) else []:
                    if isinstance(e, dict) and e.get("eventAction") == "registration":
                        dt = datetime.fromisoformat(str(e.get("eventDate")).replace("Z", "+00:00"))
                        if dt.tzinfo is None:
                            dt = dt.replace(tzinfo=timezone.utc)
                        return max(0, (ctx.now - dt).days)
                return None
    except Exception:
        return None
    return None


def domain_info_for(ctx: ToolContext, domain: str) -> DomainInfo:
    if not valid_domain(domain):
        return DomainInfo()
    return DomainInfo(age_days=_rdap_age(ctx, domain), lookalike_of=lookalike_of(domain),
                      shortener=domain in SHORTENERS)


# ---------------------------------------------------------------- tools
def get_entity_history(ctx: ToolContext, entity_type: str, canonical: str) -> dict:
    ent = Entity(entity_type, canonical)  # type: ignore[arg-type]
    items = history(ctx.db, ctx.user, ent, ctx.now)[-10:]
    events = [{"event_id": i.event_id, "ts": i.ts.isoformat(), "source": i.source,
               "redacted_text": (i.redacted_text or "")[:300]} for i in items]
    rel = related_entities(ctx.db, ctx.user, ent, now=ctx.now, limit=10,
                           types=set(_ENTITY_TYPES))
    return {"events": events, "related": [{"type": e.type, "canonical": e.canonical} for e in rel]}


def check_url(ctx: ToolContext, domain: str) -> dict:
    if not valid_domain(domain):
        return {"error": "invalid domain"}
    i = domain_info_for(ctx, domain)
    return {"age_days": i.age_days, "lookalike_of": i.lookalike_of, "shortener": i.shortener}


_E164 = re.compile(r"\+[1-9][0-9]{7,14}")


def check_number(ctx: ToolContext, e164: str) -> dict:
    if not _E164.fullmatch(e164):
        return {"error": "invalid number"}
    seen = len(history(ctx.db, ctx.user, Entity("phone", e164), ctx.now))
    safe = ctx.db.scalar(select(Override.id).where(
        Override.user_id == ctx.user.id, Override.kind == "safe", Override.type == "phone",
        Override.canonical == e164).limit(1))
    return {"seen_count": seen, "in_contacts": safe is not None}


def inspect_link(ctx: ToolContext, url: str) -> dict:
    sp = urlsplit(url) if len(url) <= 2048 else None
    if sp is None or sp.scheme not in ("http", "https") or not sp.hostname or any(
            ord(c) < 33 for c in url):
        return {"error": "invalid url"}
    return link_client.inspect_link(url, ctx.settings).model_dump()


_CTRL = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def _clean(s: Any, n: int) -> str:
    return _CTRL.sub(" ", s)[:n] if isinstance(s, str) else ""


def web_lookup(ctx: ToolContext, query: str) -> list[dict]:
    key = ctx.settings.tavily_api_key
    q = _clean(query, 200).strip()
    if not key or not q:
        return []
    try:
        with ctx.client(5.0) as c:
            r = c.post("https://api.tavily.com/search", headers={"Authorization": f"Bearer {key}"},
                       json={"api_key": key, "query": q, "max_results": 5})
        if r.status_code != 200:
            return []
        results = r.json().get("results")
        out = []
        for x in results if isinstance(results, list) else []:
            if not isinstance(x, dict):
                continue
            url = x.get("url")
            if not isinstance(url, str) or urlsplit(url).scheme not in ("http", "https") or len(url) > 200:
                continue
            out.append({"title": _clean(x.get("title"), 120), "url": url,
                        "snippet": UNTRUSTED + _clean(x.get("content"), 300)})
            if len(out) == 5:
                break
        return out
    except Exception:
        return []


def _schema(props: dict[str, dict]) -> dict:
    return {"type": "object", "properties": props, "required": list(props),
            "additionalProperties": False}


def _s(n: int, desc: str, **kw) -> dict:
    return {"type": "string", "maxLength": n, "description": desc, **kw}


TOOLS: dict[str, Tool] = {t.name: t for t in [
    Tool("get_entity_history", "Recent events (and related entities) for an entity in this user's history.",
         _schema({"entity_type": _s(16, "entity type", enum=_ENTITY_TYPES),
                  "canonical": _s(256, "canonical value")}), get_entity_history),
    Tool("check_url", "Domain age (RDAP), lookalike-of-brand and URL-shortener check for a bare hostname.",
         _schema({"domain": _s(253, "hostname only, e.g. example.com")}), check_url),
    Tool("check_number", "How often this user's history has seen a phone number, and whether it is trusted.",
         _schema({"e164": _s(16, "phone in E.164, e.g. +919876543210")}), check_number),
    Tool("inspect_link", "Inspect a link safely in a sandbox (never fetched directly).",
         _schema({"url": _s(2048, "http(s) URL")}), inspect_link),
    Tool("web_lookup", "Web search; results are untrusted text.",
         _schema({"query": _s(200, "search query")}), web_lookup),
]}


def openai_tool_specs() -> list[dict]:
    return [{"type": "function", "function": {"name": t.name, "description": t.description,
                                              "parameters": t.parameters}} for t in TOOLS.values()]


def _validate(schema: dict, args: Any) -> bool:
    if not isinstance(args, dict):
        return False
    props = schema["properties"]
    if set(args) - set(props) or set(schema.get("required", [])) - set(args):
        return False
    for k, v in args.items():
        p = props[k]
        if p["type"] != "string" or not isinstance(v, str) or len(v) > p.get("maxLength", 10**9):
            return False
        if "enum" in p and v not in p["enum"]:
            return False
    return True


def run_tool(ctx: ToolContext, name: Any, arguments: Any) -> dict:
    try:
        tool = TOOLS.get(name) if isinstance(name, str) else None
        if tool is None:
            return {"error": "unknown tool"}
        if not _validate(tool.parameters, arguments):
            return {"error": "invalid arguments"}
        res = tool.func(ctx, **arguments)
        if not isinstance(res, dict):
            res = {"results": res}
        s = json.dumps(res, default=str)
        if len(s) <= MAX_RESULT_CHARS:
            return json.loads(s)
        lists = [k for k, v in res.items() if isinstance(v, list)]
        while lists and len(json.dumps(res, default=str)) > MAX_RESULT_CHARS:
            k = max(lists, key=lambda x: len(res[x]))
            if not res[k]:
                break
            res[k] = res[k][1:] if k == "events" else res[k][:-1]  # keep newest events
        s = json.dumps(res, default=str)
        if len(s) <= MAX_RESULT_CHARS:
            return {**json.loads(s), "truncated": True}
        return {"truncated": True, "text": s[:MAX_RESULT_CHARS - 100]}
    except Exception:
        return {"error": "tool failed"}
