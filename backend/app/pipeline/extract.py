"""Entity extraction (pure functions): phones, domains, UPI ids, amounts, orgs, claims."""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation
from dataclasses import dataclass
from typing import Literal

import phonenumbers

from app.schemas import CallMeta

EntityType = Literal["phone", "domain", "upi_id", "amount", "org", "claim"]


@dataclass(frozen=True)
class Entity:
    type: EntityType
    canonical: str


_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

# ---------------------------------------------------------------- phones
_IN_PHONE = re.compile(
    r"(?<![\d\w])(?<!\+\d[\s-])(?<!\+\d\d[\s-])(?<!\+\d\d\d[\s-])(?:\+\s?91[\s-]?|0091[\s-]?|91[\s-]?|0[\s-]?)?([6-9](?:[\s-]?\d){9})(?!\d)"
)
_INTL_PHONE = re.compile(r"(?<![\d\w])\+(?!\s?91)\d(?:[\s-]?\d){6,13}(?!\d)")


def _in_phones(text: str) -> list[str]:
    out = []
    for m in _IN_PHONE.finditer(text):
        # a bare "91" prefix is only a country code when it leaves exactly 10 digits (guaranteed by regex)
        out.append("+91" + re.sub(r"\D", "", m.group(1)))
    return out


def _intl_phones(text: str) -> list[str]:
    out = []
    for m in _INTL_PHONE.finditer(text):
        try:
            num = phonenumbers.parse(re.sub(r"[\s-]", "", m.group(0)), None)
        except phonenumbers.NumberParseException:
            continue
        if phonenumbers.is_valid_number(num):
            out.append(phonenumbers.format_number(num, phonenumbers.PhoneNumberFormat.E164))
    return out


def _phones(text: str) -> list[str]:
    return _in_phones(text) + _intl_phones(text)


def _number_phone(number: str) -> list[str]:
    found = _phones(number.translate(_DEVANAGARI_DIGITS))
    if found:
        return found
    try:
        num = phonenumbers.parse(number, "IN")
    except phonenumbers.NumberParseException:
        return []
    if phonenumbers.is_valid_number(num):
        return [phonenumbers.format_number(num, phonenumbers.PhoneNumberFormat.E164)]
    return []


# ---------------------------------------------------------------- UPI / domains
_UPI = re.compile(r"(?<![\w.\-+@])([\w.\-]{2,})@([A-Za-z][A-Za-z0-9]+)(?![\w@]|\.\w)")
_EMAIL = re.compile(r"(?<![\w.\-+])[\w.\-+]+@[\w\-]+(?:\.[\w\-]+)+")
_DOMAIN = re.compile(
    r"(?<![\w@.\-])((?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+([a-z]{2,24}))(?![\w\-]|\.\w)",
    re.IGNORECASE,
)
_TLDS = {
    "com", "in", "org", "net", "info", "xyz", "top", "online", "site", "link", "app", "club",
    "live", "vip", "biz", "me", "io", "co", "gov", "nic", "edu", "ly", "gl", "ai", "cc", "tk",
    "ml", "ga", "cf", "gq", "pw", "ws", "to", "us", "uk", "ru", "cn", "shop", "store", "tech",
    "click", "page", "icu", "cyou", "buzz", "work", "support", "services", "digital", "website",
    "space", "fun", "life", "world", "today", "pro", "mobi", "ink", "cloud", "rest", "sbs",
}
_SHORTENERS = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "cutt.ly", "is.gd", "rb.gy", "ow.ly"}

# ---------------------------------------------------------------- amounts
_CUR = r"(?:₹|\bRs\.?|\bINR|रुपये|रुपए|रु\.?)"
_NUM = r"(?<![\d,.])(\d[\d,]{0,30}(?:\.\d{1,6})?)(?![\d,]\d)(?!\d)"
_LAKH = r"(?:\s*(lakhs?|lacs?|लाख))?"
_AMT_PRE = re.compile(_CUR + r"\s*" + _NUM + _LAKH, re.IGNORECASE)
_AMT_POST = re.compile(_NUM + _LAKH + r"\s*(?:/-|₹|\bRs\b\.?|\bINR\b|रुपये|रुपए|रु(?![ऀ-ॿ])|\brupees\b)",
                       re.IGNORECASE)


def _amount(num: str, lakh: str | None) -> str | None:
    s = num.replace(",", "").rstrip(".")
    try:
        d = Decimal(s)
    except InvalidOperation:
        return None
    if len(s.split(".")[0].lstrip("0")) > 15:
        return None
    if lakh:
        d *= 100000
    if d == d.to_integral_value():
        return f"INR:{int(d)}"
    return f"INR:{s}"


# ---------------------------------------------------------------- orgs
_ORG_PATTERNS: list[tuple[str, str]] = [
    ("sbi", r"\bSBI\b|एसबीआई"),
    ("hdfc", r"\bHDFC\b|एचडीएफसी"),
    ("icici", r"\bICICI\b|आईसीआईसीआई"),
    ("paytm", r"\bPaytm\b|पेटीएम"),
    ("phonepe", r"\bPhone\s?Pe\b|फोन\s?पे"),
    ("amazon", r"\bAmazon\b|अमे(?:ज़|ज़?)न"),
    ("india_post", r"\bIndia\s+Post\b|इंडिया\s+पोस्ट|(?<![ऀ-ॿ])डाक(?![ऀ-ॿ])|डाकघर"),
    ("dhl", r"\bDHL\b"),
    ("fedex", r"\bFedEx\b"),
    ("bluedart", r"\bBlue\s?Dart\b"),
    ("cbi", r"\bCBI\b|सीबीआई"),
    ("rbi", r"\bRBI\b|आरबीआई"),
    ("trai", r"\bTRAI\b|ट्राई"),
    ("customs", r"\bcustoms\b|कस्टम"),
    ("police", r"\bpolice\b|पुलिस"),
]
_ORGS = [(slug, re.compile(p, re.IGNORECASE)) for slug, p in _ORG_PATTERNS]
_SENDER_ORGS = [
    ("HDFCBK", "hdfc"), ("HDFCBN", "hdfc"), ("SBIINB", "sbi"), ("SBIPSG", "sbi"), ("SBICRD", "sbi"),
    ("SBIBNK", "sbi"), ("ICICIB", "icici"), ("ICICIT", "icici"), ("PAYTM", "paytm"),
    ("PHONPE", "phonepe"), ("AMAZON", "amazon"), ("INDPST", "india_post"), ("BLDART", "bluedart"),
]

# ---------------------------------------------------------------- claims
_CLAIM_PATTERNS: list[tuple[str, str]] = [
    ("parcel_held",
     r"(?:parcel|courier|package).{0,40}?(?:held|stuck|seiz|detain|on hold|ruk[aie]|rok[aie]|atk[aie])"
     r"|(?:held|stuck|seized|detained).{0,25}(?:parcel|courier|package)"
     r"|(?:पार्सल|पारसल|कूरियर).{0,40}?(?:रुक|रोक|जब्त|फंस|अटक)"),
    ("kyc_expired",
     r"\bkyc\b.{0,30}?(?:expir|updat|pending|verif|suspend|incomplete|block)"
     r"|(?:updat|complet|verify|renew)\w*\s+(?:your\s+)?kyc"
     r"|केवाईसी|केवायसी"),
    ("account_blocked",
     r"(?:account|a/c|खाता|अकाउंट).{0,30}?(?:block|suspend|freez|froze|deactivat|ब्लॉक|बंद|फ्रीज|निलंबित)"
     r"|(?:blocked|suspended|frozen)\s+(?:your\s+)?(?:bank\s+)?account"),
    ("digital_arrest", r"digital\s+arrest|डिजिटल\s+(?:अरेस्ट|गिरफ्तार)"),
    ("refund", r"\brefund|रिफंड|पैसे\s+वापस"),
    ("job_offer", r"job\s+offer|part[\s-]?time\s+job|work\s+from\s+home|\bWFH\b|पार्ट\s?टाइम|घर\s+बैठे|नौकरी"),
    ("lottery", r"lottery|lucky\s+draw|you\s+(?:have\s+)?won\b|लॉटरी|लकी\s+ड्रा|आपने.{0,15}जीत"),
]
_CLAIMS = [(slug, re.compile(p, re.IGNORECASE | re.DOTALL)) for slug, p in _CLAIM_PATTERNS]


def extract_entities(text: str | None, sender: str | None, call: CallMeta | None) -> list[Entity]:
    seen: dict[Entity, None] = {}

    def add(t: EntityType, c: str) -> None:
        seen.setdefault(Entity(t, c), None)

    sender = sender[:200] if sender else sender
    body = (text or "")[:10000].translate(_DEVANAGARI_DIGITS)

    for p in _phones(body):
        add("phone", p)
    if sender and re.fullmatch(r"[+\d\s\-]+", sender.translate(_DEVANAGARI_DIGITS).strip() or "x"):
        for p in _number_phone(sender.strip()):
            add("phone", p)
    if call and call.number:
        for p in _number_phone(call.number):
            add("phone", p)

    for m in _UPI.finditer(body):
        add("upi_id", f"{m.group(1)}@{m.group(2)}".lower())

    scan = _EMAIL.sub(" ", _UPI.sub(" ", body))
    for m in _DOMAIN.finditer(scan):
        if m.group(2).lower() not in _TLDS:
            continue
        # bare (scheme-less) domains need a lowercase TLD, to avoid "done.Click"
        if not m.group(2).islower() and scan[max(0, m.start(1) - 3):m.start(1)] != "://":
            continue
        host = m.group(1).lower()
        if host.startswith("www."):
            host = host[4:]
        add("domain", host)
        if host in _SHORTENERS:
            add("claim", "shortener_link")

    for pat in (_AMT_PRE, _AMT_POST):
        for m in pat.finditer(body):
            if re.fullmatch(r"[6-9]\d{9}", m.group(1)):
                continue  # currency token directly followed by a mobile number
            a = _amount(m.group(1), m.group(2))
            if a:
                add("amount", a)

    for slug, pat in _ORGS:
        if pat.search(body):
            add("org", slug)
    if sender:
        up = sender.upper()
        for sub, slug in _SENDER_ORGS:
            if sub in up:
                add("org", slug)

    for slug, pat in _CLAIMS:
        if pat.search(body):
            add("claim", slug)

    return list(seen)
