"""Signals and the tier rules engine.

Pure module: no DB, no network. Message text is treated strictly as DATA, matched with regexes
only; text that tries to instruct the AI is itself a (positive) signal and can never lower a tier.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from email.utils import getaddresses
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING
from urllib.parse import urlsplit

from app.pipeline.extract import Entity, extract_entities
from app.pipeline.memory import HistoryItem
from app.pipeline.redact import redact
from app.schemas import EventIn, Tier

if TYPE_CHECKING:  # duck-typed at runtime: keeps this module free of config/network imports
    from app.pipeline.link_client import LinkReport

# ------------------------------------------------------------------ registry
# Single source of truth for weights. Add new signals here; tier_for sums whatever names it finds.
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
    # link inspector (Task 8b): positive only; a clean or failed inspection never lowers a tier
    "credential_form_on_new_domain": 4,
    "apk_download": 4,
    "threat_feed_hit": 5,
}

# Link-inspector verdicts that no discount (user_marked_safe, known_benign_sender) may offset
LINK_HARD = frozenset({"credential_form_on_new_domain", "apk_download", "threat_feed_hit"})
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
    # True only if the caller verified the email sender (SPF/DKIM/DMARC); From: is spoofable
    sender_authenticated: bool = False
    # Sandboxed link-inspector results keyed by the message's domain entity canonical (lower-case
    # host without "www.", exactly as extract_entities produces it). Reports for domains that are
    # not in the current message are ignored.
    link_reports: dict[str, "LinkReport"] = field(default_factory=dict)

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
# Hindi credit notifications ("जमा कर दिए गए हैं", "जमा हो गए", "जमा किए गए"): passive/perfective forms
_HI_NOTIF = r"(?![^।.!?\n]*?(?:जमा|डेबिट|क्रेडिट|निकाले|निकाला)\s*(?:कर\s*)?(?:दिए|दिया|दी|किए|किया|की\b|गए|गई|गया|हो\s|हो$|हुए|हुआ|हुई))"
_HI_LETTER = r"[ऀ-ॣॱ-ॿ]"  # Devanagari letters / matras; NOT the danda । ॥ (U+0964/5) or digits
_HI_JAMA_ASK = (r"जमा\s*(?:करें|करे\b|कीजिए|कीजिये|करिए|करिये|करो|करना|कर\s*(?:दो|दें|दीजिए|दीजिये|देना))"
                r"(?!" + _HI_LETTER + r")")
# A UPI id as written in a message (same shape as extract._UPI; "name@gmail.com" is an email, not a UPI id)
_UPI_ID = r"[\w.\-]{2,}@[a-z][a-z0-9]{1,20}(?![\w@]|\.\w)"
_PAYMENT = _rx(
    r"\b(?:collect\s+request|payment\s+request|pay\s*(?:₹|rs\.?|inr|rupees)"
    r"|pay\s+\d|pay\s+(?!(?:cash\s+)?on\s+delivery\b)(?:the\s+|a\s+|your\s+)?(?:[\w-]+\s+){0,2}?(?:fees?|fine|amount|charges?|penalty|duty|deposit)\b"
    r"|pay\s+now"
    r"|\b(?:send|transfer|pay|bhej\w*|invest\w*)\b[^.!?\n।]{0,40}?" + _UPI_ID +
    r"|" + _UPI_ID + r"\s*(?:pe|par|per|on|पर|पे)?\s*(?:bhej\w*|send\b|transfer\b|pay\b|भेज|जमा\s*कर)"
    r"|(?:शुल्क|फीस|फ़ीस)\s*(?:देकर|दें|दीजिए|दीजिये|भरें|भरकर|भरिए|जमा\s*कर)"
    r"|transfer|send\s+(?:me\s+)?(?:money|₹|rs\.?)|approve\s+(?:the\s+)?(?:request|collect)"
    r"|scan\s+(?:the\s+)?qr|paise\s+bhej\w*|paisa\s+bhej\w*|pay\s+kar\w*"
    r"|(?:jama|deposit)\s+(?:kar(?:o|ein|en|iye|na)\b|kar\s+(?:do|dein|den|dijiye|de)\b|kiji\w*)"
    r"|pay\s+\[(?:otp|acct)\]"
    r"|(?:send|bhej\w*)\b[^.!?\n]{0,30}\[(?:acct|card)\]"
    r"|(?:₹|rs\.?|inr|rupe\w*)\s*\d[\d,]*\s*(?:bhej\w*|भेज[ऀ-ॿ]*)"
    r"|\d[\d,]*\s*(?:rs|rupe\w*|रुपये?|रुपए)\s+(?:bhej\w*|भेज[ऀ-ॿ]*))"
    r"|(?:भेज|जमा)[ऀ-ॿ]*\s*(?:₹|रु\.?)?\s*\d[\d,]*\s*(?:रुपये?|रुपए)" + _HI_NOTIF +
    r"|(?<![ऀ-ॿ])(?:खाता|खाते)[^.!?\n]{0,20}\[acct\]" + _HI_NOTIF +
    r"|(?:भेज|जमा)[ऀ-ॿ]*[^.!?\n]{0,30}\[acct\]" + _HI_NOTIF +
    r"|भुगतान|ट्रांसफर|पैसे\s*भेज" + _DEV + r"|रुपये?\s*भेज|" + _HI_JAMA_ASK)
# "deposit" only as an ask: at clause start or after please/kindly/now/must/..., and never in the
# notification forms ("Cash deposit of", "Deposit received:", "fixed deposit will mature").
_DEPOSIT_ASK = _rx(
    r"(?:^|[.!?\n,;:]\s*|\b(?:please|pls|plz|kindly|now|immediately|must|should|need\s+to|have\s+to"
    r"|has\s+to|and|then|you|to|unless|by)\s+)deposit\b"
    r"(?!\s*(?::|-|of\b|received|credited|matured|maturity|amount\b|is\b|has\b|was\b|will\b|rate|"
    r"interest|scheme|receipt|slip|details|account\s+(?:no|number|opened|statement)"
    r"|(?:the\s+|your\s+)?(?:cheques?|checks?|documents?|kyc|forms?)\b))")
# "deposit ... [ACCT]/[CARD]/a/c <number>" is an ask unless it reads as a notification
_DEPOSIT_ACCT = _rx(
    r"(?<!cash\s)(?<!cheque\s)(?<!check\s)(?<!fixed\s)(?<!fd\s)(?<!security\s)(?<!recurring\s)(?<!rd\s)"
    r"\bdeposit\b(?![^.!?\n]{0,60}?\b(?:received|credited|matured|successful(?:ly)?|refunded)\b)"
    r"[^.!?\n]{0,30}?(?:\[(?:acct|card)\]|\b(?:a/c|acct|account)\s*(?:no\.?|number)?\s*[:.]?\s*\d{6,})")
_CRED_EN = _rx(
    r"\b(?:share|send|enter|tell|give|provide|submit|confirm|read\s+out|reply\s+with|forward|update)\b"
    r"[^.!?\n]{0,40}?\b(?:otp|pin|password|passcode|cvv|card(?:\s+(?:number|details|no))?"
    r"|aadhaar|aadhar|pan|mpin)\b"
    r"|\b(?:share|send|enter|tell|give|provide|submit|confirm|read\s+out|reply\s+with|forward|update)\b"
    r"[^.!?\n]{0,40}?(?:\[(?:otp|card|aadhaar|pan)\]|\bcode\s*(?:\d{4,8}|\[otp\]))"
    r"|\b(?:otp|pin|password|cvv|aadhaar|aadhar|pan)\b[^.!?\n]{0,20}\b(?:required|needed|share|send)\b")
_CRED_HI = re.compile(
    r"(?:ओटीपी|पिन|पासवर्ड|सीवीवी|आधार|पैन|कार्ड|एटीएम)[^।.!?\n]{0,30}"
    r"(?:बता|शेयर|साझा|दें|दीजिए|भेज|डाल|दर्ज)"
    r"|(?:शेयर|साझा|भेज|बता|दर्ज|डाल)" + _DEV + r"[^।.!?\n]{0,30}"
    r"(?:ओटीपी|पिन|पासवर्ड|सीवीवी|आधार|पैन|कार्ड)")
_CRED_HINGLISH = _rx(
    r"\b(?:otp|pin|password|cvv)\b[^.!?\n,]{0,20}\b(?:bata|share|bhej|de\b|daal|dalo|dal\s+(?:do|dein|dena)\b|enter)\w*"
    r"|\botp\s+(?:bata|share|bhej|de\b)|\b(?:bata|share|bhej)\w*\s+(?:do\s+)?(?:otp|pin|password|cvv)")
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
    r"|\b(?:otp|pin|password|code)" + _TOK + r"\s+kisi\s+(?:ko|se)\s+(?:na|mat|nahi|nahin)\s+bata\w*"
    r"|(?:ओटीपी|पिन|पासवर्ड|कोड)" + _TOK + r"\s*किसी\s*(?:को|से)\s*(?:न|मत|नहीं)\s*बता[ऀ-ॿ]*"
    r"|\b(?:(?:otp|pin|code)" + _TOK + r"\s+)?(?:kisi\s+(?:ke\s+saath|se|ko)\s+)?"
    r"(?:otp|pin|code)?" + _TOK + r"\s*share\s+(?:na|mat|nahi|nahin)\s+kar\w*",
    _I)


_COND_BEFORE = re.compile(
    r"\b(?:if|agar|warna|varna|otherwise|in\s+case|nahi\s+to|nahin\s+to|yadi)\b|अगर|यदि|वरना|नहीं\s+तो", _I)
_THREAT_AFTER = re.compile(
    r"\b(?:then|toh|else|otherwise|block(?:ed)?|band|suspend\w*|freez\w*|frozen|arrest\w*|deactivat\w*)\b"
    r"|तो|वरना|बंद|ब्लॉक|फ्रीज|गिरफ्तार", _I)
_SENT_END = re.compile(r"[.!?\n।]")


def _coercive(text: str, a: int, b: int) -> bool:
    """A warning inside a conditional/threat ("if you don't share OTP your account is blocked")
    is a demand, not a bank warning."""
    start = max((m.end() for m in _SENT_END.finditer(text, 0, a)), default=0)
    end = next((m.start() for m in _SENT_END.finditer(text, b)), len(text))
    after = " ".join(text[b:end].split()[:6])
    return bool(_COND_BEFORE.search(text[start:a]) or _THREAT_AFTER.search(after))


def _unwarned(rx: re.Pattern, text: str, spans: list[tuple[int, int]]) -> re.Match | None:
    """First match of `rx` not lying entirely inside a (non-coercive) warning span."""
    spans = [(a, b) for a, b in spans if not _coercive(text, a, b)]
    pos = 0
    while (m := rx.search(text, pos)):
        if not any(a <= m.start() and m.end() <= b for a, b in spans):
            return m
        pos = m.start() + 1
    return None


_SECRECY_TOKEN = re.compile(r"\b(?:otp|pin|password|cvv)\b|ओटीपी|पिन|पासवर्ड", _I)
# the warning clause must end right after the span (end, punctuation, or a "-SBI" style signature)
_CLAUSE_END = re.compile(r"\s*(?:$|[.!?।\n]|[-–—~]\s*[\w &]{1,30}(?:[.!?\n]|$))")


def _secrecy_spans(text: str, spans: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Warning spans that may suppress secrecy_demand: credential token is otp/pin/password/cvv
    (not bare 'code') and nothing demand-like follows in the clause ("... ki hum bank se ...")."""
    return [(a, b) for a, b in spans
            if _SECRECY_TOKEN.search(text[a:b]) and _CLAUSE_END.match(text, b)]


# A bank OTP SMS often ends with a bare "Do not tell anyone." / "Kisi ko na batayein." /
# "इसे किसी को न बताएं।". Such a sentence is a warning only when it is the WHOLE sentence (no
# "about this call", "ki ...", "aur ...") and the message has an OTP and no other risk content.
_PURE_SECRECY = re.compile(
    r"(?:please\s+)?(?:do\s*n[o']?t|don'?t|dont|never)\s+(?:tell|disclose\s+(?:it|this)\s+to)\s+"
    r"(?:(?:it|this)\s+to\s+)?(?:anyone|anybody|any\s*one|others)"
    r"|(?:ise|isey|isse)?\s*kisi\s+(?:ko|se)\s+(?:bhi\s+)?(?:na|mat|nahi|nahin)\s+bata(?:yein|yen|ein|en|na|o|iye|ye)?"
    r"|(?:इसे\s*)?किसी\s*(?:को|से)\s*(?:भी\s*)?(?:न|मत|नहीं)\s*बता(?:एं|एँ|ये|यें|ना|ओ|इए|इये)",
    _I)
_SIG_TAIL = re.compile(r"[\s।.!?]*(?:[-–—~]\s*[\w &]{1,30})?[\s।.!?]*$")
# "share it with me" in an OTP message is an ask even though it names no credential
_SHARE_BACK = _rx(r"\b(?:share|send|tell|give|forward|read)\s+(?:it|this|that)\s+(?:out\s+)?(?:with|to)\s+(?:me|us)\b"
                  r"|\b(?:mujhe|hume|hamein)\s+(?:bata|bhej|share)\w*")
_SECRECY_BLOCKERS = {"urgency", "authority_impersonation", "payment_request", "credential_or_otp_ask",
                     "remote_access_ask", "injection_attempt"}


def _pure_otp_warning(text: str, m: re.Match) -> bool:
    start = max((x.end() for x in _SENT_END.finditer(text, 0, m.start())), default=0)
    end = next((x.start() for x in _SENT_END.finditer(text, m.end())), len(text))
    sent = text[start:end].strip()
    p = _PURE_SECRECY.match(sent)
    return bool(p and _SIG_TAIL.fullmatch(sent, p.end()))


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
_AI_NOUN = r"(?:assistant|ai|bot|chatbot|llm|language\s+model|model|system)"
_ROLE_WORD = (r"(?:manager|director|executive|professor|teacher|coach|officer|head|lead|supervisor|"
              r"trainer|engineer|developer|consultant|analyst|designer|trainee|intern)")
# Instruction nouns: "ignore/disregard/forget/override ... <noun>" within ~4 words always fires.
_INSTR_NOUN = (r"(?:instructions?|prompts?|rules|context|commands?|guidelines|directives?|system\s+prompt)"
               r"\b|निर्देश")
_IGNORE_VERB = r"(?:ignore|disregard|forget|override|skip|do\s*n[o']?t\s+follow|stop\s+following)"
_IGNORE_NOUN = (r"\b" + _IGNORE_VERB + r"\b(?:[\s,;]+(?!(?:and|but)\b)[\w'-]+){0,4}?[\s,;]+"
                + _INSTR_NOUN)
# Classification steering words; when one follows within ~6 words, ordinary-notice exclusions
# ("ignore the previous message") no longer apply.
_STEER = (r"(?:" + _INSTR_NOUN + r"|\b(?:classify|mark|safe|benign|tier|label|treat|say|respond|reply|"
          r"legit\w*|not\s+(?:a\s+)?scam|approve|accept|allow|pass|(?:is|it'?s|as)\s+ok(?:ay)?)\b)")
# Ordinary "ignore the previous <thing>" notices. Only IMMEDIATELY after previous/prior/earlier/
# above (no word skipping), and never when a steering word follows.
_NOT_PREV = (r"(?!\s+(?:owner|tenant|number|noise|year|bill|balance|address|e-?mails?|mail|one|times?|"
             r"messages?|reminders?|notices?|sms|alerts?|plans?|otps?|invoices?|statements?|notifications?|"
             r"texts?|calls?|payments?)\b(?!(?:\W+[\w']+){0,6}?\W*" + _STEER + r"))")
_PERSONA = (r"(?:jailbroken|unrestricted|uncensored|unfiltered|dan|developer\s+mode|god\s+mode|"
            r"evil\s+(?:twin|mode)|" + _AI_NOUN + r")")
_INJECTION = _rx(
    _IGNORE_NOUN +
    r"|\b" + _IGNORE_VERB + r"[\s,;]+(?:(?:all|any|the|your|my|of|everything|everyone)[\s,;]+)*"
    r"(?:previous|prior|above|earlier)\b" + _NOT_PREV +
    r"|\byou\s+are\s+(?:now\s+)?(?:a|an|the)\s+(?:[\w-]+\s+){0,3}?" + _AI_NOUN + r"\b(?!\s+" + _ROLE_WORD + r")"
    r"|\byou\s+are\s+now\s+(?:(?:a|an|the)\s+(?:[\w-]+\s+){0,2}?" + _PERSONA + r"\b"
    r"|free\b|unrestricted|jailbroken|dan\b|no\s+longer|acting|in\s+\w+(?:\s+\w+)?\s+mode"
    r"|my\s+(?:[\w-]+\s+)?(?:assistant|slave|servant|bot|ai|agent)\b)"
    r"|\bfrom\s+now\s+on,?\s+you\b|\bpretend\s+(?:this|it|that|you|to\s+be)\b"
    r"|\b(?:respond|answer|reply|output)\s+(?:with\s+)?(?:\{\s*)?\"?(?:tier|risk|label)\"?\s*[:=]?\s*\"?\w+"
    r"|\b(?:respond|answer|reply|output)\s+(?:with\s+)?\"?(?:safe|benign|info|low)\"?\s+(?:tier|risk|label|classification)\b"
    r"|\bmark\s+(?:this\s+|it\s+|message\s+)?(?:as\s+)?(?:safe|benign|legit\w*|not\s+(?:a\s+)?scam)\b"
    r"|\b(?:classify|rate|label|treat)\s+(?:this\s+|it\s+)?as\s+(?:safe|info|benign)\b"
    r"|\bdo\s*n[o']?t\s+(?:flag|alert|warn)\b"
    r"|\bsystem\s*:|\bassistant\s*:|\bnew\s+instructions?\b"
    r"|\{\s*\"(?:tool|tool_calls?|function|name|action)\"\s*:"
    r"|\bpichle\s+(?:sabhi\s+|sab\s+|saare\s+|saari\s+)?(?:nirdesh|instructions?)\b"
    r"|(?:ऊपर|पिछले|पिछली)\s+(?:के\s+)?(?:सभी\s+)?(?:निर्देश|आदेश)" + _DEV
    + r"\s*(?:को\s+)?(?:भूल|अनदेखा|नजरअंदाज|नज़रअंदाज़|इग्नोर|छोड़)"
    r"|पिछले\s+(?:सभी\s+)?(?:निर्देश|आदेश)" + _DEV + r"[^।.!?\n]{0,15}(?:अनदेखा|नजरअंदाज|नज़रअंदाज़|इग्नोर)"
    r"|(?:अनदेखा|नजरअंदाज|इग्नोर)\s*(?:कर)" + _DEV + r"[^।.!?\n]{0,25}(?:निर्देश|आदेश)"
    r"|सुरक्षित\s+(?:के\s+रूप\s+में\s+)?(?:मार्क|चिह्नित|चिन्हित)|\bतुम\s+अब\b|\bआप\s+अब\s+एक\b")
_ZW = re.compile("[\u200b-\u200d\u2060\ufeff]")
_MD = re.compile(r"[*_`]+")  # markdown emphasis, matched as a word separator for injection only
_OTP_WORD = _rx(r"\botp\b|one[\s-]?time\s+pass|ओटीपी")
_BANK_HEADER = re.compile(
    r"^(?:[A-Z]{2}-)?(?:HDFCBK|HDFCBN|SBIINB|SBIPSG|SBICRD|SBIBNK|ICICIB|ICICIT|AXISBK|KOTAKB|"
    r"PNBSMS|BOBSMS|CANBNK|IDFCFB|YESBNK|INDBNK)(?:-[A-Z])?$")


_DOMAIN_OK = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$")


def _email_domain(sender: str | None) -> str | None:
    """Domain of a single well-formed addr-spec, else None."""
    if not sender:
        return None
    addrs = getaddresses([sender])
    if len(addrs) != 1:
        return None
    addr = addrs[0][1].strip()
    if addr.count("@") != 1 or any(c in addr for c in " ,;<>\"()"):
        return None
    dom = addr.split("@", 1)[1].lower()
    return dom if _DOMAIN_OK.match(dom) else None


def _ev(m: re.Match | str) -> str:
    s = m if isinstance(m, str) else m.group(0)
    if not isinstance(m, str) and m.string[m.end():m.end() + 1] == "]" and s.count("[") > s.count("]"):
        s += "]"  # keep a whole [TOKEN] placeholder
    s = " ".join(s.split())
    cut = s[:80]
    if cut.count("[") > cut.count("]"):
        close = s.find("]", 80)
        cut = s[:close + 1] if close != -1 else cut[:cut.rfind("[")].rstrip()
    return redact(cut)


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
    text = redact(_ZW.sub("", (event.text or "")[:10000]))
    ents = set(entities)
    out: list[Signal] = []

    def add(name: str, evidence: str) -> None:
        out.append(Signal(name, SIGNAL_WEIGHTS[name], evidence))

    safe = overrides.safe if overrides else frozenset()
    known_bad = overrides.known_bad if overrides else frozenset()

    injection = _INJECTION.search(text) or _INJECTION.search(_MD.sub(" ", text))
    if injection:
        add("injection_attempt", _ev(injection))

    if m := _URGENCY.search(text):
        add("urgency", _ev(m))

    org_hit = sorted(e.canonical for e in ents if e.type == "org" and e.canonical in _AUTH_ORGS)
    if org_hit:
        add("authority_impersonation", "org: " + ",".join(org_hit))
    elif m := _AUTHORITY.search(text):
        add("authority_impersonation", _ev(m))

    payment = _PAYMENT.search(text) or _DEPOSIT_ASK.search(text) or _DEPOSIT_ACCT.search(text)
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

    secrecy = _unwarned(_SECRECY, text, _secrecy_spans(text, spans))
    if (secrecy and _bank_sms(event) and _OTP_WORD.search(text)
            and not {s.name for s in out} & _SECRECY_BLOCKERS
            and not _SHARE_BACK.search(text) and not _LINK_WORD.search(text)
            and not any(e.type in ("phone", "domain", "claim", "upi_id")
                        for e in extract_entities(text, None, None))
            and all(_pure_otp_warning(text, m) for m in _SECRECY.finditer(text))):
        secrecy = None  # bare bank-OTP warning sentence, not a demand
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

    for name, evidence in _link_signals(ents, history_ctx, safe):
        add(name, evidence)

    # sender
    # only entities identifying the actual originator may earn safe discounts; entities merely
    # named in the body must not (an attacker could name a user-safe number).
    origin = set(extract_entities(None, event.sender, event.call))
    if event.source == "email" and history_ctx.sender_authenticated:
        dom = _email_domain(event.sender)
        if dom:
            origin.add(Entity("domain", dom))
    safe_sender = sorted((e for e in origin if e in safe and e.type in ("phone", "domain")),
                         key=lambda x: (x.type, x.canonical))
    if event.call is not None:
        unknown = not event.call.is_contact
    elif event.sender:
        unknown = not history_ctx.sender_seen_before
    else:
        unknown = False
    if getattr(event, "is_contact", None) is True:  # client-checked saved contact (never inferred from names)
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

    # negative signals never apply when the text tries to instruct the AI, or when an external
    # link verdict (threat feed, APK, credential form on a fresh domain) says the link is bad
    if not injection and not ({s.name for s in out} & LINK_HARD):
        if benign:
            add("known_benign_sender", f"trusted sender {event.sender}, OTP-only message")
        if safe_sender:
            add("user_marked_safe", f"{safe_sender[0].type}:{safe_sender[0].canonical}")
    return out


def _host_key(url: str) -> str | None:
    try:
        h = (urlsplit(url).hostname or "").lower().rstrip(".")
    except ValueError:
        return None
    return (h[4:] if h.startswith("www.") else h) or None


def _risky_domain(di: DomainInfo | None) -> str | None:
    if di is None:
        return None
    if di.lookalike_of:
        return f"resembles {di.lookalike_of}"
    if di.age_days is not None and di.age_days < NEW_DOMAIN_DAYS:
        return f"{di.age_days} days old"
    return None


_FEED_LABEL = re.compile(r"[^A-Za-z0-9_:]+")


def _link_signals(ents: set[Entity], ctx: HistoryCtx, safe) -> list[tuple[str, str]]:
    """Signals from sandboxed link inspection. Additive only: a clean report, an unfetched report
    (inspector failure, blocked hop) or a missing report contributes nothing, and nothing here is
    ever negative (attackers cloak pages for scanners). Page-derived fields are only trusted when
    fetched_ok; threat-feed hits are independent of the fetch and always count. Each signal fires
    at most once per message."""
    reports = ctx.link_reports or {}
    info = ctx.domain_info or {}
    out: dict[str, str] = {}
    for e in sorted((x for x in ents if x.type == "domain"), key=lambda x: x.canonical):
        r = reports.get(e.canonical)
        if r is None:
            continue
        hits = [h for h in (r.threat_feed_hits or []) if isinstance(h, str)]
        if hits and "threat_feed_hit" not in out:
            label = _FEED_LABEL.sub("_", hits[0])[:60]
            more = f" (+{len(hits) - 1})" if len(hits) > 1 else ""
            out["threat_feed_hit"] = f"{e.canonical}: {label}{more}"[:120]
        if not r.fetched_ok:
            continue
        if r.apk_download and "apk_download" not in out:
            out["apk_download"] = f"{e.canonical} serves an Android app (APK) download"
        if ((r.has_password_field or r.has_otp_or_upi_or_card_field) and e not in safe
                and "credential_form_on_new_domain" not in out):
            final = _host_key(r.final_url)
            fdi = info.get(final) if final else None
            di = info.get(e.canonical)
            why = _risky_domain(di) or (_risky_domain(fdi) if final != e.canonical else None)
            if why is None and di is not None and di.shortener and final and final != e.canonical and (
                    fdi is None or fdi.age_days is None):
                why = f"behind link shortener, lands on unverified {final}"
            if why:
                kind = "password" if r.has_password_field else "OTP/UPI/card"
                out["credential_form_on_new_domain"] = f"{e.canonical}: {kind} form, {why}"[:120]
    order = ("credential_form_on_new_domain", "apk_download", "threat_feed_hit")
    return [(n, out[n]) for n in order if n in out]


def _bank_sms(event) -> bool:
    """SMS from an allowlisted bank header (spoofable; see _is_benign), no call context."""
    return (event.source == "sms" and bool(event.sender) and event.call is None
            and bool(_BANK_HEADER.match(event.sender.strip().upper())))


def _is_benign(event, ents, text) -> bool:
    # NOTE: SMS sender headers are spoofable; a DLT registry lookup is a post-hackathon item.
    # The discount is therefore only granted to a bare OTP message with no other risk content.
    if not _bank_sms(event):
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
    base = {e for e in ents if e.type == "claim" or (e.type == "org" and e.canonical in _AUTH_ORGS)}
    keys = set(base)
    for b in base:
        keys |= {r for r in ctx.related.get(b, []) if r.type in ("phone", "domain")}
    for e in ents:  # current phone/domain explicitly linked to a base entity
        if e.type in ("phone", "domain") and any(e in ctx.related.get(b, []) for b in base):
            keys.add(e)
        # ... or tied to a claim by memory (an earlier "parcel held, call 98..." SMS, now this number
        # calls): the event itself names no claim, but the number/site carries one across channels.
        # Orgs alone do not count (bank numbers co-occur with bank names all the time).
        if e.type in ("phone", "domain") and any(r.type == "claim" for r in ctx.related.get(e, [])):
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
    no_discount = bool(names & ({"injection_attempt"} | LINK_HARD))
    total = sum(s.weight for s in signals if not (no_discount and s.weight < 0))
    total = max(total, 0)
    tier: Tier = "info"
    for threshold, t in _THRESHOLDS:
        if total >= threshold:
            tier = t
            break
    if names & _FLOOR_A and names & _FLOOR_B:
        tier = max_tier(tier, "high")
    return tier
