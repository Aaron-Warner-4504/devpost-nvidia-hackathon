"""Signals and the tier rules engine.

Pure module: no DB, no network. Message text is treated strictly as DATA, matched with regexes
only; text that tries to instruct the AI is itself a (positive) signal and can never lower a tier.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

from app.pipeline.extract import Entity, extract_entities
from app.pipeline.memory import HistoryItem
from app.pipeline.redact import redact
from app.schemas import EventIn, Tier

# ------------------------------------------------------------------ registry
# Single source of truth for weights. Add new signals (e.g. credential_form_on_new_domain,
# apk_download, threat_feed_hit) here; tier_for sums whatever names it finds.
SIGNAL_WEIGHTS: dict[str, int] = {
    "urgency": 1,
    "authority_impersonation": 2,
    "payment_request": 3,
    "credential_or_otp_ask": 3,
    "remote_access_ask": 3,
    "new_domain": 2,
    "lookalike_domain": 3,
    "unknown_sender": 1,
    "channel_hop": 2,
    "repeat_contact": 2,
    "secrecy_demand": 3,
    "amount_escalation": 1,
    "known_benign_sender": -2,
    "injection_attempt": 2,
    "known_bad_entity": 7,
    "user_marked_safe": -2,
}

TIER_ORDER: tuple[Tier, ...] = ("info", "caution", "high", "critical")
_THRESHOLDS: tuple[tuple[int, Tier], ...] = ((7, "critical"), (4, "high"), (2, "caution"))
_FLOOR_A = {"payment_request", "credential_or_otp_ask"}
_FLOOR_B = {"channel_hop", "repeat_contact"}
REPEAT_WINDOW = timedelta(hours=72)
NEW_DOMAIN_DAYS = 30
LARGE_AMOUNT = 10_000


def max_tier(a: Tier, b: Tier) -> Tier:
    return a if TIER_ORDER.index(a) >= TIER_ORDER.index(b) else b


@dataclass(frozen=True)
class Signal:
    name: str
    weight: int
    evidence: str


@dataclass(frozen=True)
class DomainInfo:
    age_days: int | None = None
    lookalike_of: str | None = None
    shortener: bool = False


@dataclass(frozen=True)
class Overrides:
    safe: frozenset[Entity] = frozenset()
    known_bad: frozenset[Entity] = frozenset()


@dataclass(frozen=True)
class HistoryCtx:
    """Plain data filled by the caller. `events_by_entity` holds PRIOR events (excluding the
    current one), oldest first."""
    now: datetime
    events_by_entity: dict[Entity, list[HistoryItem]] = field(default_factory=dict)
    related: dict[Entity, list[Entity]] = field(default_factory=dict)
    sender_seen_before: bool = False
    domain_info: dict[str, DomainInfo] | None = None

    @classmethod
    def empty(cls, now: datetime) -> "HistoryCtx":
        return cls(now=now)


# ------------------------------------------------------------------ patterns
_I = re.IGNORECASE
_DEV = r"[ऀ-ॿ]*"  # trailing Devanagari letters/matras (\w misses combining marks)


def _rx(p: str) -> re.Pattern:
    return re.compile(p, _I)


_URGENCY = _rx(
    r"\b(?:immediately|urgent(?:ly)?|asap|right\s+now|last\s+warning|final\s+(?:warning|notice)"
    r"|within\s+\d+\s*(?:hours?|hrs?|minutes?|mins?)|expires?\s+(?:today|soon|in)|act\s+now"
    r"|turant|abhi|jaldi|jald\s+se\s+jald)\b"
    r"|तुरंत|अभी|जल्दी|फौरन|आखिरी\s+चेतावनी")
_AUTH_ORGS = {"cbi", "rbi", "trai", "customs", "police"}
_AUTHORITY = _rx(
    r"\b(?:officer|inspector|cyber\s*crime|narcotics|enforcement\s+directorate|arrest\s+warrant"
    r"|court\s+order|legal\s+action|supreme\s+court|income\s+tax\s+department|ministry)\b"
    r"|अधिकारी|इंस्पेक्टर|साइबर\s*क्राइम|गिरफ्तारी|वारंट|सरकारी|न्यायालय")
_PAYMENT = _rx(
    r"\b(?:collect\s+request|payment\s+request|pay\s*(?:₹|rs\.?|inr|rupees)"
    r"|pay\s+\d|pay\s+(?:the\s+|a\s+)?(?:fee|fine|amount|charges?|penalty|duty|deposit)|pay\s+now"
    r"|transfer|send\s+(?:me\s+)?(?:money|₹|rs\.?)|approve\s+(?:the\s+)?(?:request|collect)"
    r"|scan\s+(?:the\s+)?qr|paise\s+bhej\w*|paisa\s+bhej\w*|pay\s+kar\w*)"
    r"|भुगतान|ट्रांसफर|पैसे\s*भेज" + _DEV + r"|रुपये?\s*भेज|जमा\s+कर" + _DEV)
_CRED_EN = _rx(
    r"\b(?:share|send|enter|tell|give|provide|submit|confirm|read\s+out|reply\s+with|forward|update)\b"
    r"[^.!?\n]{0,40}?\b(?:otp|pin|password|passcode|cvv|card(?:\s+(?:number|details|no))?"
    r"|aadhaar|aadhar|pan|mpin)\b"
    r"|\b(?:otp|pin|password|cvv|aadhaar|aadhar|pan)\b[^.!?\n]{0,20}\b(?:required|needed|share|send)\b")
_CRED_HI = re.compile(
    r"(?:ओटीपी|पिन|पासवर्ड|सीवीवी|आधार|पैन|कार्ड|एटीएम)[^।.!?\n]{0,30}"
    r"(?:बता|शेयर|साझा|दें|दीजिए|भेज|डाल|दर्ज)"
    r"|(?:शेयर|साझा|भेज|बता|दर्ज|डाल)" + _DEV + r"[^।.!?\n]{0,30}"
    r"(?:ओटीपी|पिन|पासवर्ड|सीवीवी|आधार|पैन|कार्ड)")
_CRED_HINGLISH = _rx(
    r"\botp\s+(?:bata|share|bhej|de\b)|\b(?:bata|share|bhej)\w*\s+(?:do\s+)?(?:otp|pin|password|cvv)")
_VERIFY = _rx(r"\bverif(?:y|ication)\b|\bre-?verify\b|सत्यापित|वेरिफाई|वेरीफाई")
_LINK_WORD = _rx(r"https?://|www\.|\blink\b|लिंक")
# Short anchored "standard bank warning" spans. A credential match is only ignored when it lies
# entirely inside one of these, so an ask appended after the warning ("...with anyone, but send
# me your OTP") is still caught. Text is already redacted, so OTP digits appear as "[OTP]".
_TOK = r"(?:\s*\[otp\])?"
_WARN = re.compile(
    r"\b(?:do\s*n[o']?t|don'?t|dont|never|please\s+do\s+not)\s+(?:share|disclose|give|reveal|tell)\s+"
    r"(?:(?:this|your|the)\s+)?(?:(?:otp|pin|code|password|cvv)" + _TOK + r"|it|this)"
    r"(?:\s+with\s+(?:anyone|anybody|any\s*one|others?))?"
    r"|(?:(?:किसी\s*(?:के\s*साथ|से)\s*)?(?:ओटीपी|पिन|कोड|पासवर्ड)" + _TOK + r"\s*)?"
    r"(?:किसी\s*(?:के\s*साथ|से)\s*)?(?:साझा|शेयर)\s*(?:न|नहीं|ना|मत)\s*(?:करें|करना|कीजिए)"
    r"|\b(?:(?:otp|pin|code)" + _TOK + r"\s+)?(?:kisi\s+(?:ke\s+saath|se|ko)\s+)?"
    r"(?:otp|pin|code)?" + _TOK + r"\s*share\s+(?:na|mat|nahi|nahin)\s+kar\w*",
    _I)


def _unwarned(rx: re.Pattern, text: str, spans: list[tuple[int, int]]) -> re.Match | None:
    """First match of `rx` not lying entirely inside a warning span."""
    pos = 0
    while (m := rx.search(text, pos)):
        if not any(a <= m.start() and m.end() <= b for a, b in spans):
            return m
        pos = m.start() + 1
    return None


_REMOTE = _rx(
    r"\b(?:any\s*desk|team\s*viewer|quick\s*support|rust\s*desk|screen\s*shar\w*|\.apk|apk|install)\b"
    r"|एनीडेस्क|टीमव्यूअर|स्क्रीन\s*शेयर|इंस्टॉल")
_SECRECY = _rx(
    r"\b(?:do\s*n[o']?t|don'?t|dont|never)\s+(?:tell|inform|discuss\s+(?:this\s+)?with)\s+"
    r"(?:this\s+to\s+)?(?:anyone|anybody|any\s*one|others?|family)\b"
    r"|\bkeep\s+(?:this|it)\s+(?:strictly\s+)?(?:confidential|secret|private|between\s+us)\b"
    r"|\bconfidential\b|\btell\s+(?:no\s*one|nobody)\b|\bkisi\s+ko\s+(?:mat|na)\s+bata\w*"
    r"|किसी\s*(?:को|से)\s*(?:भी\s*)?(?:मत|न|नहीं)\s*(?:बता|कहना|कहें)" + _DEV
    + r"|गोपनीय|राज़?\s+रखें")
_INJECTION = _rx(
    r"\b(?:ignore|disregard|forget)\s+(?:all\s+|any\s+|the\s+|your\s+)*(?:previous|prior|above|earlier)\b"
    r"|\byou\s+are\s+(?:now\s+)?(?:a|an|the)\s+(?:\w+\s+){0,4}?(?:assistant|ai|model|bot|agent|mode)\b"
    r"|\brespond\s+with\s+(?:tier|\{|\"?(?:info|safe)\b)"
    r"|\bmark\s+(?:this\s+|it\s+|message\s+)?(?:as\s+)?(?:safe|benign|legit\w*|not\s+(?:a\s+)?scam)\b"
    r"|\b(?:classify|rate|label|treat)\s+(?:this\s+|it\s+)?as\s+(?:safe|info|benign)\b"
    r"|\bdo\s*n[o']?t\s+(?:flag|alert|warn)\b"
    r"|\bsystem\s*:|\bassistant\s*:|\byou\s+are\s+now\b|\bnew\s+instructions?\b"
    r"|\{\s*\"(?:tool|tool_calls?|function|name|action)\"\s*:"
    r"|\bpichle\s+(?:sabhi\s+)?(?:nirdesh|instructions?)\b"
    r"|पिछले\s+(?:सभी\s+)?(?:निर्देश|आदेश)" + _DEV + r"[^।.!?\n]{0,15}(?:अनदेखा|नजरअंदाज|नज़रअंदाज़|इग्नोर)"
    r"|(?:अनदेखा|नजरअंदाज|इग्नोर)\s*(?:कर)" + _DEV + r"[^।.!?\n]{0,25}(?:निर्देश|आदेश)"
    r"|सुरक्षित\s+(?:के\s+रूप\s+में\s+)?(?:मार्क|चिह्नित|चिन्हित)|\bतुम\s+अब\b|\bआप\s+अब\s+एक\b")
_OTP_WORD = _rx(r"\botp\b|one[\s-]?time\s+pass|ओटीपी")
_BANK_HEADER = re.compile(
    r"^(?:[A-Z]{2}-)?(?:HDFCBK|HDFCBN|SBIINB|SBIPSG|SBICRD|SBIBNK|ICICIB|ICICIT|AXISBK|KOTAKB|"
    r"PNBSMS|BOBSMS|CANBNK|IDFCFB|YESBNK|INDBNK)(?:-[A-Z])?$")


def _ev(m: re.Match | str) -> str:
    s = m if isinstance(m, str) else m.group(0)
    return redact(" ".join(s.split())[:80])


def _utc(d: datetime) -> datetime:
    return d if d.tzinfo else d.replace(tzinfo=timezone.utc)


def _amount_value(e: Entity) -> Decimal | None:
    try:
        return Decimal(e.canonical.split(":", 1)[1])
    except (IndexError, InvalidOperation):
        return None


def _max_amount(entities) -> Decimal | None:
    vals = [v for e in entities if e.type == "amount" and (v := _amount_value(e)) is not None]
    return max(vals) if vals else None


# ------------------------------------------------------------------ detection
def detect_signals(event: EventIn, entities: list[Entity], history_ctx: HistoryCtx, *,
                   overrides: Overrides | None = None) -> list[Signal]:
    text = redact((event.text or "")[:10000])
    ents = set(entities)
    out: list[Signal] = []

    def add(name: str, evidence: str) -> None:
        out.append(Signal(name, SIGNAL_WEIGHTS[name], evidence))

    safe = overrides.safe if overrides else frozenset()
    known_bad = overrides.known_bad if overrides else frozenset()

    injection = _INJECTION.search(text)
    if injection:
        add("injection_attempt", _ev(injection))

    if m := _URGENCY.search(text):
        add("urgency", _ev(m))

    org_hit = sorted(e.canonical for e in ents if e.type == "org" and e.canonical in _AUTH_ORGS)
    if org_hit:
        add("authority_impersonation", "org: " + ",".join(org_hit))
    elif m := _AUTHORITY.search(text):
        add("authority_impersonation", _ev(m))

    payment = _PAYMENT.search(text)
    if payment:
        add("payment_request", _ev(payment))

    spans = [m.span() for m in _WARN.finditer(text)]
    has_link = bool(_LINK_WORD.search(text)) or any(e.type == "domain" for e in ents)
    cred = (_unwarned(_CRED_EN, text, spans) or _unwarned(_CRED_HI, text, spans)
            or _unwarned(_CRED_HINGLISH, text, spans))
    if cred:
        add("credential_or_otp_ask", _ev(cred))
    elif has_link and (v := _VERIFY.search(text)):
        add("credential_or_otp_ask", "verify link: " + _ev(v))
    elif has_link and Entity("claim", "kyc_expired") in ents:
        add("credential_or_otp_ask", "kyc update link")

    remote = _REMOTE.search(text)
    if remote:
        add("remote_access_ask", _ev(remote))

    secrecy = _SECRECY.search(text)
    if secrecy:
        add("secrecy_demand", _ev(secrecy))

    # domains
    info = history_ctx.domain_info or {}
    for e in sorted((x for x in ents if x.type == "domain"), key=lambda x: x.canonical):
        di = info.get(e.canonical)
        if di is None:
            continue
        if di.lookalike_of:
            add("lookalike_domain", f"{e.canonical} resembles {di.lookalike_of}")
        if e in safe:
            continue
        if di.age_days is not None and di.age_days < NEW_DOMAIN_DAYS:
            add("new_domain", f"{e.canonical} is {di.age_days} days old")

    # sender
    # only entities identifying the actual originator may earn safe discounts; entities merely
    # named in the body must not (an attacker could name a user-safe number).
    origin = set(extract_entities(None, event.sender, event.call))
    if event.source == "email" and event.sender and "@" in event.sender:
        origin.add(Entity("domain", event.sender.rsplit("@", 1)[1].strip().lower().rstrip(">")))
    safe_sender = sorted((e for e in origin if e in safe and e.type in ("phone", "domain")),
                         key=lambda x: (x.type, x.canonical))
    if event.call is not None:
        unknown = not event.call.is_contact
    elif event.sender:
        unknown = not history_ctx.sender_seen_before
    else:
        unknown = False
    if unknown and not safe_sender:
        add("unknown_sender", "call from non-contact" if event.call else f"first contact: {event.sender}")

    # history based
    now = _utc(history_ctx.now)
    in_window = _hist_window(history_ctx, now)
    hop_keys = _hop_keys(ents, history_ctx)
    for key in sorted(hop_keys, key=lambda x: (x.type, x.canonical)):
        other = {h.source for h in history_ctx.events_by_entity.get(key, []) if h.ts <= now
                 and h.source != event.source}
        if other:
            add("channel_hop", f"{key.canonical} seen earlier via {','.join(sorted(other))}")
            break

    others = {s.name for s in out} - {"unknown_sender", "channel_hop"}
    benign = not others and _is_benign(event, ents, text)

    risk_names = {s.name for s in out} - {"unknown_sender", "channel_hop"}
    content_risk = bool(risk_names)
    if not benign:
        rep = _repeat(event, ents, in_window, content_risk)
        if rep:
            add("repeat_contact", rep)

    amt = _amount_escalation(ents, history_ctx, now)
    if amt:
        add("amount_escalation", amt)

    bad = sorted((e for e in ents if e in known_bad), key=lambda x: (x.type, x.canonical))
    if bad:
        add("known_bad_entity", f"{bad[0].type}:{bad[0].canonical}")

    # negative signals never apply when the text tries to instruct the AI
    if not injection:
        if benign:
            add("known_benign_sender", f"trusted sender {event.sender}, OTP-only message")
        if safe_sender:
            add("user_marked_safe", f"{safe_sender[0].type}:{safe_sender[0].canonical}")
    return out


def _is_benign(event, ents, text) -> bool:
    # NOTE: SMS sender headers are spoofable; a DLT registry lookup is a post-hackathon item.
    # The discount is therefore only granted to a bare OTP message with no other risk content.
    if event.source != "sms" or not event.sender:
        return False
    if not _BANK_HEADER.match(event.sender.strip().upper()):
        return False
    if not _OTP_WORD.search(text) or _LINK_WORD.search(text):
        return False
    header_orgs = {e for e in extract_entities(None, event.sender, None) if e.type == "org"}
    for e in ents:
        if e.type in ("phone", "domain", "claim", "upi_id"):
            return False
        if e.type == "org" and e not in header_orgs:
            return False
    return True


def _hist_window(ctx: HistoryCtx, now: datetime):
    lo = now - REPEAT_WINDOW
    seen: dict[Entity, set[str]] = {}
    for ent, items in ctx.events_by_entity.items():
        seen[ent] = {h.event_id for h in items if lo <= _utc(h.ts) <= now}
    return seen


def _hop_keys(ents: set[Entity], ctx: HistoryCtx) -> set[Entity]:
    base = {e for e in ents if e.type in ("org", "claim")}
    keys = set(base)
    for b in base:
        keys |= {r for r in ctx.related.get(b, []) if r.type in ("phone", "domain")}
    for e in ents:  # current phone/domain explicitly linked to a base entity
        if e.type in ("phone", "domain") and any(e in ctx.related.get(b, []) for b in base):
            keys.add(e)
    return keys


def _repeat(event: EventIn, ents: set[Entity], window: dict[Entity, set[str]],
            content_risk: bool) -> str | None:
    # a chatty contact must not be flagged for chatting: repeat alone needs risky content,
    # a repeated claim, or repeated calls from a non-contact.
    nuisance_call = event.call is not None and not event.call.is_contact
    for e in sorted(ents, key=lambda x: (x.type, x.canonical)):
        if e.type == "amount":
            continue
        if e.type != "claim" and not (content_risk or nuisance_call):
            continue
        n = len(window.get(e, set())) + 1
        if n >= 3:
            return f"{n} events linked to {e.type}:{e.canonical} within 72h"
    return None


def _amount_escalation(ents: set[Entity], ctx: HistoryCtx, now: datetime) -> str | None:
    cur = _max_amount(ents)
    if cur is None:
        return None
    prior_items: dict[str, HistoryItem] = {}
    for e in ents:
        if e.type in ("amount",):
            continue
        for h in ctx.events_by_entity.get(e, []):
            if _utc(h.ts) <= now:
                prior_items[h.event_id] = h
    if not prior_items:
        return None
    prior_vals = []
    for h in prior_items.values():
        if h.redacted_text:
            v = _max_amount(extract_entities(h.redacted_text, None, None))
            if v is not None:
                prior_vals.append(v)
    if prior_vals:
        if cur > max(prior_vals):
            return f"amount {cur} exceeds earlier {max(prior_vals)}"
        return None
    if cur >= LARGE_AMOUNT:
        return f"large amount {cur} after earlier contact"
    return None


# ------------------------------------------------------------------ tiers
def tier_for(signals: list[Signal], history_ctx: HistoryCtx | None = None) -> Tier:
    names = {s.name for s in signals}
    injected = "injection_attempt" in names
    total = sum(s.weight for s in signals if not (injected and s.weight < 0))
    total = max(total, 0)
    tier: Tier = "info"
    for threshold, t in _THRESHOLDS:
        if total >= threshold:
            tier = t
            break
    if names & _FLOOR_A and names & _FLOOR_B:
        tier = max_tier(tier, "high")
    return tier
