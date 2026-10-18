"""PII redaction applied before storage or any LLM call.

Masks OTPs, card / account / Aadhaar numbers and PAN. Phone numbers, URLs,
UPI IDs and amounts are deliberately kept: they are scam evidence.
Works on English and Hindi (Devanagari digits are recognised).
"""
import re
from typing import Callable

# str.translate keeps length, so match offsets map 1:1 onto the original text.
_DEVANAGARI = str.maketrans("०१२३४५६७८९", "0123456789")

_PAN = re.compile(r"(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])", re.IGNORECASE)
_ACCT_CTX = re.compile(
    r"((?:a/c|acct?|account|खाता)(?:\s*(?:no\.?|number|num|संख्या))?[\s:.#-]*)"
    r"(\d(?:[ -]?\d){5,17})(?!\d)",
    re.IGNORECASE,
)
_AADHAAR_CTX = re.compile(
    r"((?<![A-Za-z])(?!(?-i:AADHAAR\]))(?:aadhaar|aadhar|आधार|uid)[^\d]{0,20}?)(\d(?:[ -]?\d){11})(?!\d)",
    re.IGNORECASE,
)
_PHONE_KW = re.compile(
    r"\b(?:call|phone|mob|mobile|whatsapp|contact)\b|संपर्क|कॉल|फोन|मोबाइल", re.IGNORECASE
)
_RUN = re.compile(r"(?<!\d)\d(?:[ -]?\d){8,18}(?!\d)")
_KW = (
    r"(?:otp|code|verification|ओटीपी|कोड|pin|one[ -]time[ -]password|passcode|पासवर्ड)"
)
# 4-8 digits, optionally split in two ("1234 5678") or "123-456"; never half of a phone.
_NUM = (
    r"(?<!\d)(?<!\d[ -])(\d{4,8}(?:[ -]\d{2,4})?|\d{3}[ -]\d{3,4})(?!\d)(?![ -]\d)"
)
_OTP_BEFORE = re.compile(_KW + r"[^\d]{0,30}?" + _NUM, re.IGNORECASE)
_OTP_AFTER = re.compile(_NUM + r"[^\d]{0,30}?" + _KW, re.IGNORECASE)
_CURRENCY_BEFORE = re.compile(
    r"(?:₹|रु\.?|रुपये|(?<![A-Za-z])(?:rs\.?|inr))\s*$", re.IGNORECASE
)
_PHONE = re.compile(r"(?:91|0091|0)?[6-9]\d{9}")


def _sub(rx: re.Pattern, text: str, fn: Callable[[re.Match, str], list | None]) -> str:
    """Match on digit-normalised text; fn returns [(start, end, token)] spans in text."""
    norm = text.translate(_DEVANAGARI)
    spans: list[tuple[int, int, str]] = []
    for m in rx.finditer(norm):
        spans.extend(fn(m, norm) or [])
    for s, e, tok in sorted(spans, reverse=True):
        text = text[:s] + tok + text[e:]
    return text


def _after_currency(norm: str, start: int) -> bool:
    return bool(_CURRENCY_BEFORE.search(norm, max(0, start - 8), start))


def _pan(m, norm):
    return [(m.start(), m.end(), "[PAN]")]


def _acct_ctx(m, norm):
    return [(m.start(2), m.end(2), "[ACCT]")]


def _aadhaar_ctx(m, norm):
    return [(m.start(2), m.end(2), "[AADHAAR]")]


def _is_phone12(raw, norm, start):
    # Aadhaar-vs-phone precedence: a bare 12-digit run is Aadhaar (fail closed) unless it
    # has a leading '+', a "91"/"0091" prefix separated by space/dash, or a phone keyword.
    if start > 0 and norm[start - 1] == "+":
        return True
    if re.match(r"(?:91|0091)[ -]", raw):
        return True
    if re.fullmatch(r"\d{4}([ -])\d{4}\1\d{4}", raw):
        return False  # 4-4-4 grouping is Aadhaar formatting; keywords can't override
    return bool(_PHONE_KW.search(norm, max(0, start - 20), start))


def _run(m, norm):
    raw = m.group(0)
    d = re.sub(r"\D", "", raw)
    n = len(d)
    if _after_currency(norm, m.start()):
        return None
    if _PHONE.fullmatch(d):
        if n != 12 or _is_phone12(raw, norm, m.start()):
            return None  # phone number: evidence, keep
    if n == 12:
        tok = "[AADHAAR]"
    elif 13 <= n <= 19:
        grouped = re.fullmatch(r"\d{4}([ -])\d{4}\1\d{4}\1\d{1,7}", raw)
        tok = "[CARD]" if (n in (15, 16) or grouped or n == 19) else "[ACCT]"
    else:
        tok = "[ACCT]"
    return [(m.start(), m.end(), tok)]


def _otp(m, norm):
    if _after_currency(norm, m.start(1)):
        return None
    return [(m.start(1), m.end(1), "[OTP]")]


def redact(text: str) -> str:
    text = _sub(_PAN, text, _pan)
    text = _sub(_ACCT_CTX, text, _acct_ctx)
    text = _sub(_AADHAAR_CTX, text, _aadhaar_ctx)
    text = _sub(_RUN, text, _run)
    text = _sub(_OTP_BEFORE, text, _otp)
    text = _sub(_OTP_AFTER, text, _otp)
    return text
