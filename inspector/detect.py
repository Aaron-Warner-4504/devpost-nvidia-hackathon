"""Static HTML analysis (also applied to the Chromium-rendered DOM).

All page content is attacker-controlled. Output text is UNTRUSTED data: control and format
characters (incl. bidi overrides and zero-width chars) are removed and lengths are capped.
"""

from __future__ import annotations

import codecs
import re
import unicodedata
from dataclasses import dataclass

from selectolax.lexbor import LexborHTMLParser

MAX_HTML_CHARS = 2 * 1024 * 1024
MAX_FIELDS = 2000
TEXT_CAP = 1500
TITLE_CAP = 200
_NON_TEXT_INPUTS = frozenset({"hidden", "submit", "button", "image", "reset", "checkbox", "radio",
                              "file", "color", "range"})
_FIELD_ATTRS = ("name", "id", "placeholder", "autocomplete", "aria-label", "title", "data-name",
                "formcontrolname", "ng-model", "v-model")

_EN = re.compile(
    r"\b(?:otp|one ?time ?(?:password|passcode|code|pin)|upi|vpa|cvv2?|cvc2?|csc"
    r"|card ?(?:no|num|number)|cardnumber|cardno|ccnum|cc ?(?:no|num|number|csc|cvc|cvv|exp\w*)"
    r"|(?:debit|credit|atm) ?card|aadhaa?r|aadhar ?no"
    r"|(?:m|atm|upi|t|i|txn|transaction|secure|login|card)? ?pin)\b(?! ?code)")
_HI = re.compile(
    r"ओटीपी|वन\s*टाइम\s*पासवर्ड|यूपीआई|सीवीवी|कार्ड\s*(?:नंबर|संख्या)|आधार|एटीएम\s*पिन"
    r"|(?<![ऀ-ॿ])पिन(?![ऀ-ॿ])(?!\s*कोड)")
_CAMEL = re.compile(r"([a-z])([A-Z])")
_SEP = re.compile(r"[\W_]+", re.UNICODE)
_WS = re.compile(r"\s+")
_REFRESH = re.compile(r"^\s*(\d{1,6})(?:\.\d*)?\s*[;,]?\s*(?:url\s*=\s*)?(['\"]?)(.*?)\2\s*$", re.I | re.S)
_CHARSET = re.compile(rb"<meta[^>]{0,200}?charset\s*=\s*[\"']?\s*([A-Za-z0-9_.:-]{1,40})", re.I)


@dataclass
class PageFacts:
    title: str | None = None
    has_password: bool = False
    has_sensitive: bool = False
    text: str = ""
    meta_refresh: str | None = None


def clean_text(s: object, n: int) -> str:
    """Untrusted text -> single-line, printable, capped."""
    if not isinstance(s, str):
        return ""
    s = s[: n * 8]
    out = "".join(" " if unicodedata.category(c) in ("Cc", "Cf", "Cs", "Co", "Cn") else c for c in s)
    return _WS.sub(" ", out).strip()[:n]


def is_sensitive_descriptor(desc: str) -> bool:
    # Hindi is matched on the raw text: \W would split Devanagari words at their vowel signs
    d = _SEP.sub(" ", _CAMEL.sub(r"\1 \2", desc)).lower()
    return bool(_EN.search(d) or _HI.search(_WS.sub(" ", desc)))


def decode_body(body: bytes, content_type: str | None) -> str:
    charset = None
    m = re.search(r"charset\s*=\s*\"?([A-Za-z0-9_.:-]{1,40})", content_type or "", re.I)
    if m:
        charset = m.group(1)
    elif (m2 := _CHARSET.search(body[:2048])):
        charset = m2.group(1).decode("ascii", "ignore")
    try:
        if charset:
            codecs.lookup(charset)
            return body.decode(charset, errors="replace")
    except Exception:
        pass
    return body.decode("utf-8", errors="replace")


def _meta_refresh(tree: LexborHTMLParser) -> str | None:
    for node in tree.css("meta")[:50]:
        attrs = node.attributes
        if (attrs.get("http-equiv") or "").strip().lower() != "refresh":
            continue
        m = _REFRESH.match(attrs.get("content") or "")
        if m and int(m.group(1)) <= 10 and m.group(3).strip():
            return m.group(3).strip()[:2048]
    return None


def _label_text(node, labels: dict[str, str]) -> str:
    parts = []
    nid = node.attributes.get("id")
    if nid and nid in labels:
        parts.append(labels[nid])
    p, depth = node.parent, 0
    while p is not None and depth < 3:
        if p.tag == "label":
            parts.append((p.text(deep=True) or "")[:200])
            break
        p, depth = p.parent, depth + 1
    return " ".join(parts)


def analyze_html(html: str) -> PageFacts:
    facts = PageFacts()
    if not isinstance(html, str) or not html:
        return facts
    try:
        tree = LexborHTMLParser(html[:MAX_HTML_CHARS])
    except Exception:
        return facts
    t = tree.css_first("title")
    if t is not None:
        facts.title = clean_text(t.text(deep=True), TITLE_CAP) or None
    facts.meta_refresh = _meta_refresh(tree)

    labels: dict[str, str] = {}
    for lab in tree.css("label[for]")[:MAX_FIELDS]:
        labels.setdefault(lab.attributes.get("for") or "", (lab.text(deep=True) or "")[:200])
    for node in tree.css("input, textarea, select")[:MAX_FIELDS]:
        attrs = node.attributes
        typ = (attrs.get("type") or "text").strip().lower() if node.tag == "input" else node.tag
        if typ == "password":
            facts.has_password = True
        if typ in _NON_TEXT_INPUTS or facts.has_sensitive:
            continue
        desc = " ".join(str(attrs.get(a) or "")[:200] for a in _FIELD_ATTRS)
        if is_sensitive_descriptor(desc + " " + _label_text(node, labels)):
            facts.has_sensitive = True

    for bad in tree.css("script, style, noscript, template, svg, head"):
        bad.decompose()
    root = tree.body or tree.root
    if root is not None:
        facts.text = clean_text(root.text(deep=True, separator=" "), TEXT_CAP)
    return facts


# ------------------------------------------------------------------ brand mismatch
_BRANDS: tuple[tuple[str, re.Pattern, tuple[str, ...]], ...] = tuple(
    (name, re.compile(rx, re.I), doms) for name, rx, doms in (
        ("HDFC Bank", r"\bhdfc\b", ("hdfcbank.com", "hdfc.com", "hdfcbank.net", "hdfclife.com",
                                    "hdfcergo.com", "hdfcsec.com", "hdfcfund.com", "hdfc.bank.in")),
        ("SBI", r"\bsbi\b|state\s+bank\s+of\s+india|एसबीआई|भारतीय\s*स्टेट\s*बैंक",
         ("sbi.co.in", "onlinesbi.sbi", "onlinesbi.com", "sbi.bank.in", "sbicard.com", "sbilife.co.in",
          "sbi")),
        ("ICICI Bank", r"\bicici\b", ("icicibank.com", "icici.com", "icicidirect.com", "icicilombard.com",
                                      "iciciprulife.com", "icici.bank.in")),
        ("Axis Bank", r"\baxis\s*bank\b", ("axisbank.com", "axisbank.co.in", "axis.bank.in")),
        ("Kotak", r"\bkotak\b", ("kotak.com", "kotak811.com", "kotaksecurities.com", "kotak.bank.in")),
        ("Paytm", r"\bpaytm\b", ("paytm.com", "paytm.in", "paytmbank.com", "paytmmoney.com", "paytmmall.com")),
        ("PhonePe", r"\bphone\s*pe\b", ("phonepe.com",)),
        ("Amazon", r"\bamazon\b", ("amazon.in", "amazon.com", "amazonpay.in", "amazon.co.uk",
                                   "media-amazon.com")),
        ("Flipkart", r"\bflipkart\b", ("flipkart.com",)),
        ("India Post", r"\bindia\s*post\b|इंडिया\s*पोस्ट|भारतीय\s*डाक", ("indiapost.gov.in", "ippbonline.com",
                                                                        "ippbonline.bank.in")),
        ("IRCTC", r"\birctc\b", ("irctc.co.in", "irctc.com")),
        ("Income Tax Department", r"\bincome\s*tax\s*(?:department|e-?filing|refund)|आयकर",
         ("incometax.gov.in", "incometaxindia.gov.in")),
        ("UIDAI", r"\buidai\b", ("uidai.gov.in",)),
        ("NPCI", r"\bnpci\b|\bbhim\s*upi\b", ("npci.org.in", "bhimupi.org.in")),
        ("BlueDart", r"\bblue\s*dart\b", ("bluedart.com",)),
        ("DHL", r"\bdhl\b", ("dhl.com", "dhl.co.in")),
        ("FedEx", r"\bfedex\b", ("fedex.com",)),
    ))


def _owned(host: str, doms: tuple[str, ...]) -> bool:
    return any(host == d or host.endswith("." + d) for d in doms)


def brand_mismatch(title: str, text: str, host: str | None, has_credential_field: bool) -> str | None:
    """Brand named in the title (or in the text of a page with a credential/OTP form) while the
    final host is not one of that brand's domains. Text-only mentions without a form are ignored
    (news, shops, blogs mention banks all the time)."""
    host = (host or "").lower().rstrip(".")
    if not host:
        return None
    for src, required in ((title or "", False), ((text or "")[:5000], True)):
        if required and not has_credential_field:
            continue
        found = sorted(((m.start(), name, doms) for name, rx, doms in _BRANDS if (m := rx.search(src))),
                       key=lambda x: x[0])
        if not found:
            continue
        if any(_owned(host, doms) for _, _, doms in found):
            return None
        return found[0][1]
    return None
