"""Deterministic generator for the Sentinel evaluation scenarios (Task 12).

    uv run python eval/generate_scenarios.py            # writes eval/scenarios/*.json
    uv run python eval/generate_scenarios.py --out DIR  # somewhere else (tests use this)

All scenarios are SYNTHETIC: template banks with seeded slot variation (names, numbers, amounts,
brands, domains, Hindi / English / Hinglish phrasings). No LLM was used (no key was available) and no
real user data. The same team that tunes the rules wrote these templates, so metrics on them are
optimistic (see eval/README.md).

Label-blindness: `triage_score` (the on-device triage the Android app sends) is computed by
`device_triage`, a small keyword/link heuristic that never looks at the scenario label, so it
cannot leak the answer into the input. Fixtures (`domain_info` age, `link_reports`) are the offline
stand-ins for RDAP and the sandboxed link inspector; scam links do NOT all get damning reports
(some are down / cloaked, some have no RDAP age), and benign brand pages may have login forms.
"""

from __future__ import annotations

import argparse
import json
import random
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

SEED = 20261017
IST = timezone(timedelta(hours=5, minutes=30))
BASE = datetime(2026, 3, 2, 0, 0, tzinfo=IST)
OUT = Path(__file__).resolve().parent / "scenarios"
HOLDOUT_FRACTION = 0.3

SCAM_COUNTS = {  # 40
    "digital_arrest": 5, "courier_customs": 5, "kyc_bank": 5, "upi_collect": 4, "job_task": 5,
    "electricity_bill": 4, "remote_access": 4, "sextortion": 4, "investment_group": 4,
}
# Added in Task 12 fix round 1 (R34): attackers whose WhatsApp display name looks like a contact. Generated
# after everything else with their own RNG and split stream, so earlier scenarios and splits are unchanged.
EXTRA_SCAM_COUNTS = {"contact_name_spoof": 3}
BENIGN_COUNTS = {  # 80
    "bank_otp": 5, "bank_txn_alerts": 5, "upi_confirmations": 5, "delivery_updates": 6, "family_chat": 8,
    "salary_credit": 4, "emi_reminder": 4, "app_otp": 5, "appointments": 4, "telecom_offers": 5,
    "gov_tax_notices": 4, "two_factor": 4, "legit_kyc_reminder": 4, "marketing_eligible": 5,
    "adversarial_benign": 8, "card_statement": 4,
}

# ---------------------------------------------------------------- slot banks
NAMES_EN = ["Rahul", "Priya", "Amit", "Sneha", "Vikram", "Anjali", "Ravi", "Pooja", "Suresh", "Neha",
            "Arjun", "Kavita", "Rohan", "Meera", "Karan", "Divya"]
NAMES_HI = ["राहुल", "प्रिया", "अमित", "सुनीता", "विकास", "अंजलि", "रवि", "पूजा", "सुरेश", "नेहा"]
OFFICER_EN = ["Vikram Singh", "Rajesh Sharma", "Anil Yadav", "Sanjay Verma", "Pradeep Kumar", "Ajay Rathore"]
OFFICER_HI = ["विक्रम सिंह", "राजेश शर्मा", "अनिल यादव", "संजय वर्मा", "प्रदीप कुमार"]
CITIES = ["Mumbai", "Delhi", "Bengaluru", "Hyderabad", "Pune", "Kolkata", "Chennai", "Lucknow"]
CITIES_HI = ["मुंबई", "दिल्ली", "लखनऊ", "जयपुर", "पटना"]
SHOPS = ["Sharma Kirana Store", "Ramesh Vegetables", "Shree Medical", "Annapurna Dairy", "Balaji Fuels",
         "City Bakery", "Gupta General Store", "Metro Chemist"]
FAMILY = [("Mummy", "en"), ("Papa", "en"), ("Didi", "en"), ("Bhaiya", "en"), ("Rohan (cousin)", "en"),
          ("Priya Office", "en"), ("Chachu", "en"), ("Nani", "en")]
FOODS = ["Paneer Butter Masala", "Chicken Biryani", "Masala Dosa", "Veg Thali", "Chole Bhature", "Pizza"]
ITEMS = ["boAt Airdopes 141", "Prestige pressure cooker", "Redmi Note 13 cover", "Bournvita 1kg",
         "USB-C charger", "Bedsheet double", "Cotton kurta", "Yoga mat"]
BANKS = [  # header, name, short, hindi name
    ("VM-HDFCBK", "HDFC Bank", "HDFC", "एचडीएफसी बैंक"),
    ("AD-SBIINB", "SBI", "SBI", "एसबीआई"),
    ("JD-ICICIB", "ICICI Bank", "ICICI", "आईसीआईसीआई बैंक"),
    ("BZ-AXISBK", "Axis Bank", "Axis", "एक्सिस बैंक"),
    ("VK-KOTAKB", "Kotak Bank", "Kotak", "कोटक बैंक"),
]


def inr(n: int) -> str:
    """Indian digit grouping: 125000 -> 1,25,000."""
    s = str(n)
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join(parts) + "," + tail


class Slots:
    def __init__(self, rng: random.Random):
        self.r = rng

    def pick(self, seq):
        return self.r.choice(seq)

    def phone10(self) -> str:
        return self.r.choice("6789") + "".join(self.r.choice("0123456789") for _ in range(9))

    def phone_fmt(self, p: str | None = None) -> str:
        p = p or self.phone10()
        style = self.r.randrange(3)
        return p if style == 0 else f"+91 {p[:5]} {p[5:]}" if style == 1 else f"+91{p}"

    def amount(self, lo: int, hi: int, step: int = 1) -> int:
        return self.r.randrange(lo // step, hi // step + 1) * step

    def digits(self, n: int) -> str:
        return "".join(self.r.choice("0123456789") for _ in range(n))

    def otp(self) -> str:
        return self.digits(6)

    def masked_acct(self) -> str:
        return "XX" + self.digits(4)

    def upi_handle(self, base: str) -> str:
        return f"{base}{self.r.randrange(10, 999)}@{self.pick(['ybl', 'okaxis', 'paytm', 'ibl', 'axl'])}"

    def suffix(self) -> str:
        return self.pick(["", "-in", "-online", "24", "-help", "-care", "-india"])


# ---------------------------------------------------------------- device triage (label-blind)
_URLISH = re.compile(r"https?://|www\.|\b[a-z0-9-]+\.(?:com|in|xyz|top|online|app|club|link|site|info|co|me|io)\b",
                     re.IGNORECASE)
_TRIAGE_KW = re.compile(
    r"\b(?:otp|kyc|block\w*|suspend\w*|urgent\w*|immediately|arrest\w*|police|cbi|customs|refund|lottery|apk"
    r"|anydesk|teamviewer|disconnect\w*|penalty|fine|verify|expire\w*|pin)\b"
    r"|केवाईसी|पुलिस|गिरफ्तार|तुरंत|ओटीपी|बंद|ब्लॉक", re.IGNORECASE)


def device_triage(ev: dict) -> float:
    """Stand-in for the app's on-device triage: link +0.3, risky keyword +0.3, long call from a
    non-contact +0.2. Never sees the scenario label."""
    s = 0.1
    t = ev.get("text") or ""
    if _URLISH.search(t):
        s += 0.3
    if _TRIAGE_KW.search(t):
        s += 0.3
    c = ev.get("call")
    if c and not c["is_contact"] and c["duration_s"] >= 60:
        s += 0.2
    return round(min(s, 0.95), 2)


def call(number: str, duration: int, contact: bool = False, direction: str = "in") -> dict:
    return {"source": "call", "sender": None, "text": None,
            "call": {"number": number, "duration_s": duration, "is_contact": contact, "direction": direction}}


def msg(source: str, sender: str | None, text: str, contact: bool | None = None) -> dict:
    """`contact` -> EventIn.is_contact (client-checked saved contact); None = omitted."""
    out = {"source": source, "sender": sender, "text": text}
    if contact is not None:
        out["is_contact"] = contact
    return out


def report(final_url: str, *, fetched_ok: bool = True, password: bool = False, otp_form: bool = False,
           apk: bool = False, feeds: list[str] | None = None, title: str | None = None) -> dict:
    return {"final_url": final_url, "fetched_ok": fetched_ok, "has_password_field": password,
            "has_otp_or_upi_or_card_field": otp_form, "apk_download": apk,
            "triggers_download": apk, "threat_feed_hits": feeds or [], "title": title,
            "status": 200 if fetched_ok else None}


def scam_link_report(s: Slots, url: str, *, kind: str = "form") -> dict:
    """Realistic, not uniformly damning: some pages are down/cloaked for scanners, some are on a
    threat feed, most phishing pages carry a credential / OTP / card form."""
    roll = s.r.random()
    if roll < 0.2:
        return report(url, fetched_ok=False)  # taken down / cloaked / inspector blocked
    feeds = ["urlhaus:phishing"] if roll > 0.85 else []
    if kind == "apk":
        return report(url, apk=True, feeds=feeds)
    if kind == "info":  # landing page with just a contact number / chat button
        return report(url, feeds=feeds)
    return report(url, password=s.r.random() < 0.5, otp_form=True, feeds=feeds)


# ---------------------------------------------------------------- scam templates
# Each template: (slots) -> dict(lang, shape, steps=[(gap_hours, event)], harm, fixtures)
# `harm` = index of the step where the payment / credential / remote-access ask is made; the user
# would act on it right after, so an alert >= high must be shown at an index <= harm.

def _fx(domains: dict | None = None, links: dict | None = None) -> dict:
    return {"domain_info": domains or {}, "link_reports": links or {}}


def da_en_slow(s: Slots):
    p1, p2 = s.phone10(), s.phone10()
    off, city, amt = s.pick(OFFICER_EN), s.pick(CITIES), s.amount(50000, 400000, 1000)
    acct = "5010" + s.digits(10)
    return dict(lang="en", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p1),
                f"Your mobile number will be disconnected in 2 hours as per TRAI due to illegal activity "
                f"reported on it. Press 9 or call {s.phone_fmt(p2)} for details.")),
        (1, call("+91" + p2, 1260)),
        (3, msg("whatsapp", s.phone_fmt(p2),
                f"This is Inspector {off}, {city} Cyber Crime Branch. An FIR has been registered against your "
                f"Aadhaar for money laundering. You are under digital arrest. Stay on the video call and do not "
                f"tell anyone, including family.")),
        (2, msg("whatsapp", s.phone_fmt(p2),
                f"To prove your funds are clean, transfer Rs {inr(amt)} to the RBI verification account "
                f"{acct}, IFSC HDFC0001234. The amount will be returned after verification within 24 hours.")),
    ])


def da_hi_slow(s: Slots):
    p = s.phone10()
    off, city, amt = s.pick(OFFICER_HI), s.pick(CITIES_HI), s.amount(40000, 300000, 1000)
    acct = "3920" + s.digits(8)
    return dict(lang="hi", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, call("+91" + p, 840)),
        (0.5, msg("whatsapp", s.phone_fmt(p),
                  f"मैं सीबीआई अधिकारी {off} बोल रहा हूँ। आपके आधार कार्ड से जुड़े बैंक खाते में मनी लॉन्ड्रिंग "
                  f"हुई है और आपके नाम पर गिरफ्तारी वारंट जारी हुआ है। वीडियो कॉल पर बने रहें।")),
        (1, msg("whatsapp", s.phone_fmt(p),
                f"जांच पूरी होने तक ₹{inr(amt)} इस सरकारी खाते {acct} में जमा करें। यह बात किसी को न बताएं, "
                f"वरना आपको आज ही {city} ले जाया जाएगा।")),
    ])


def da_hinglish_slow(s: Slots):
    p = s.phone10()
    city, amt = s.pick(CITIES), s.amount(25000, 200000, 500)
    upi = s.upi_handle("delhipolice.case")
    return dict(lang="hinglish", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                "Namaste, main FedEx se bol raha hoon. Aapke naam ka ek parcel Taiwan bheja ja raha tha.")),
        (0.2, call("+91" + p, 600)),
        (0.3, msg("whatsapp", s.phone_fmt(p),
                  f"Main {city} Police se bol raha hoon. Aapke Aadhaar se juda parcel mein drugs mile hain, "
                  f"aap pe case bana hai. Video call pe raho aur kisi ko mat batana.")),
        (0.5, msg("whatsapp", s.phone_fmt(p),
                  f"Case close karne ke liye verification amount {inr(amt)} rupees abhi {upi} pe bhejo, "
                  f"jaanch ke baad wapas mil jayega.")),
    ])


def da_en_intl(s: Slots):
    p = "+92 3" + s.digits(2) + " " + s.digits(7)
    amt = s.amount(100000, 500000, 5000)
    return dict(lang="en", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, call(p.replace(" ", ""), 45, direction="missed")),
        (0.1, call(p.replace(" ", ""), 1800)),
        (0.2, msg("whatsapp", p,
                  f"Supreme Court of India. Arrest warrant No. {s.digits(6)}/CBI. You are directed to remain "
                  f"under digital arrest. To avoid physical arrest, deposit Rs {inr(amt)} as security in the "
                  f"court escrow account shared by the officer immediately. Do not disconnect the call.")),
    ])


def da_en_single(s: Slots):
    p = s.phone10()
    return dict(lang="en", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"NOTICE from Narcotics Control Bureau: a parcel with MDMA booked on your Aadhaar was seized. "
                f"An arrest warrant will be issued within 2 hours. To settle the case pay the fine of "
                f"Rs {inr(s.amount(20000, 90000, 500))} immediately via UPI to ncb.settlement@ybl.")),
    ])


def cc_en_single_link(s: Slots):
    dom = s.pick(["indiapost-redelivery", "indiapost-track", "ipost-parcel", "indiapost-care"]) + "." + s.pick(
        ["top", "xyz", "online", "info"])
    url = f"https://{dom}/in"
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(1, 15)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.phone_fmt(),
                               f"India Post: your package could not be delivered due to an incomplete address. "
                               f"Please update your address within 12 hours or it will be returned: {url}"))])


def cc_hi_single_link(s: Slots):
    dom = s.pick(["indiapost-seva", "dak-parcel", "indiapost-help"]) + "." + s.pick(["top", "online", "site"])
    url = f"http://{dom}/track"
    return dict(lang="hi", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(2, 20)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.phone_fmt(),
                               f"इंडिया पोस्ट: आपका पार्सल गलत पते के कारण गोदाम में रुका हुआ है। ₹25 शुल्क देकर "
                               f"48 घंटे में पता अपडेट करें, अन्यथा पार्सल वापस भेज दिया जाएगा: {url}"))])


def cc_en_customs_slow(s: Slots):
    p, p2 = s.phone10(), s.phone10()
    nm = s.pick(["James", "Daniel", "Mark", "Henry"])
    amt1, amt2 = s.amount(25000, 45000, 500), s.amount(80000, 150000, 1000)
    upi = s.upi_handle("customsclearance")
    return dict(lang="en", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                f"Hello dear, it's {nm} from London. I have sent you a small birthday gift box with a watch and "
                f"some pounds, it should reach you this week. Take care.")),
        (30, msg("whatsapp", s.phone_fmt(p), "Did you receive the parcel? The courier said it reached Delhi.")),
        (20, msg("sms", s.phone_fmt(p2),
                 f"Delhi Airport Customs: a parcel in your name is held for clearance. Contact the customs "
                 f"desk at {s.phone_fmt(p2)}.")),
        (2, msg("whatsapp", s.phone_fmt(p2),
                f"Customs officer here. The parcel contains foreign currency. Pay customs duty Rs {inr(amt1)} to "
                f"{upi} today, else penalty of Rs {inr(amt2)} and legal action.")),
    ])


def cc_hinglish_courier_call(s: Slots):
    p = s.phone10()
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"BlueDart: aapka courier address issue ki wajah se ruka hua hai. Delivery ke liye "
                f"{s.phone_fmt(p)} pe call karein.")),
        (5, call("+91" + p, 300)),
        (0.2, msg("whatsapp", s.phone_fmt(p),
                  "Sir redelivery ke liye Rs 5 ka verification payment karna hoga. Jo link bheja hai usme card "
                  "details daal ke jo OTP aayega woh mujhe bata dijiye.")),
    ])


def cc_en_fedex_drugs(s: Slots):
    p = s.phone10()
    city = s.pick(CITIES)
    return dict(lang="en", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, call("+91" + p, 25, direction="missed")),
        (0.3, call("+91" + p, 720)),
        (0.1, msg("whatsapp", s.phone_fmt(p),
                  f"FedEx {city}: the parcel booked on your Aadhaar contains 5 passports and 200g MDMA. As discussed, "
                  f"connect to the police officer now and share your Aadhaar and bank details for verification.")),
    ])


def kyc_en_sbi_link(s: Slots):
    dom = s.pick(["sbi-yono-kyc", "onlinesbi-update", "sbi-kycupdate", "yono-sbi-verify"]) + s.suffix() + "." + s.pick(
        ["xyz", "in", "top", "online"])
    url = f"http://{dom}/login"
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(1, 10)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.pick(["AD-SBIBKG", "VK-SBlINB", s.phone_fmt()]),
                               f"Dear SBI user, your YONO account will be blocked today. Please update your PAN "
                               f"card immediately: {url}"))])


def kyc_en_hdfc_link(s: Slots):
    dom = s.pick(["hdfc-kyc-update", "hdfcbank-netbanking", "hdfc-ekyc"]) + s.suffix() + "." + s.pick(
        ["in", "xyz", "info"])
    url = f"https://{dom}/"
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(3, 25)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.phone_fmt(),
                               f"Dear Customer, your HDFC Bank KYC has expired. Your NetBanking will be suspended "
                               f"in 24 hrs. Update now at {url} to avoid inconvenience."))])


def kyc_hi_link(s: Slots):
    dom = s.pick(["sbi-kyc", "bank-kyc-seva", "kyc-sbi"]) + s.suffix() + "." + s.pick(["top", "online", "site"])
    url = f"http://{dom}/hi"
    return dict(lang="hi", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(1, 12)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.phone_fmt(),
                               f"प्रिय ग्राहक, आपका एसबीआई खाता आज रात बंद कर दिया जाएगा। अपना केवाईसी अपडेट करने के "
                               f"लिए तुरंत इस लिंक पर क्लिक करें {url}"))])


def kyc_hinglish_paytm_slow(s: Slots):
    p = s.phone10()
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"Dear user aapka Paytm KYC expire ho gaya hai, wallet band ho jayega. KYC ke liye executive se "
                f"baat karein {s.phone_fmt(p)}")),
        (4, call("+91" + p, 420)),
        (0.1, msg("whatsapp", s.phone_fmt(p),
                  "Sir KYC complete karne ke liye aapke phone pe abhi jo OTP aaya hai woh bata dijiye, 2 min ka "
                  "kaam hai.")),
    ])


def kyc_en_icici_rewards(s: Slots):
    dom = s.pick(["icici-rewardz", "icicibank-points", "icici-redeem"]) + "." + s.pick(["online", "xyz", "site"])
    url = f"https://{dom}/redeem"
    pts = s.amount(4000, 12000, 50)
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(2, 20)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.pick(["JM-ICICIR", s.phone_fmt()]),
                               f"Dear ICICI Bank customer, your reward points worth Rs {inr(pts)} will expire today. "
                               f"Redeem now by entering your card details at {url}"))])


def upi_en_olx(s: Slots):
    p = s.phone10()
    item = s.pick(["sofa set", "study table", "Activa scooter", "double bed", "fridge"])
    amt = s.amount(8000, 35000, 500)
    return dict(lang="en", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                f"Hi, I saw your {item} on OLX. I am in the Army, posted in Pune cantonment. I will take it at your "
                f"price, my jawan will pick it up tomorrow.")),
        (1, msg("whatsapp", s.phone_fmt(p),
                "Army rules we can't do normal transfer, I will send money through PhonePe request. Just check "
                "it and approve.")),
        (0.2, msg("notification", "PhonePe",
                  f"Payment request of ₹{inr(amt)} from {s.upi_handle('armycanteen')}. Tap to pay.")),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   f"I have sent request of Rs {inr(amt)}. Approve the request and enter your UPI PIN to receive "
                   f"the money in your account.")),
    ])


def upi_hi_cashback(s: Slots):
    p = s.phone10()
    amt = s.amount(2000, 9999)
    return dict(lang="hi", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"बधाई हो! आपको PhonePe से ₹{inr(amt)} का कैशबैक मिला है। पैसे अपने खाते में पाने के लिए अभी आए "
                f"रिक्वेस्ट को स्वीकार करें और UPI पिन डालें।")),
    ])


def upi_hinglish_wrong_transfer(s: Slots):
    p = s.phone10()
    amt = s.amount(3000, 15000, 500)
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"Rs.{inr(amt)}.00 credited to your a/c XX{s.digits(4)} by UPI ref {s.digits(12)}. Avl bal Rs.{inr(amt + s.amount(100, 900))}")),
        (0.1, msg("whatsapp", s.phone_fmt(p),
                  f"Bhaiya galti se aapke number pe {inr(amt)} chale gaye, mera beta hospital mein hai. Please wapas "
                  f"kar do, maine request bhej di hai.")),
        (0.1, msg("whatsapp", s.phone_fmt(p),
                  f"Request accept karke apna UPI PIN daal do bas, {inr(amt)} turant wapas aa jayega mere paas. "
                  f"Jaldi please.")),
    ])


def upi_en_reward_collect(s: Slots):
    p = s.phone10()
    amt = s.amount(1000, 5000, 1)
    return dict(lang="en", shape="slow_burn", harm=1, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                f"Congratulations! You are selected for Google Pay Diwali reward of Rs {inr(amt)}. Our team will "
                f"send a collect request now.")),
        (0.1, msg("whatsapp", s.phone_fmt(p),
                  "Approve the collect request and enter UPI PIN to claim the reward. Offer valid for 10 minutes only.")),
    ])


def job_en_youtube_slow(s: Slots):
    p = s.phone10()
    nm = s.pick(NAMES_EN)
    agency = s.pick(["Naukri Partner", "TalentEdge HR", "Digital Media Agency", "Prime Placement"])
    tg = s.pick(["t.me/TaskMaster_VIP", "t.me/DailyEarn_Group", "t.me/PrepaidTaskTeam"])
    amt = s.amount(5000, 30000, 500)
    upi = s.upi_handle("taskmerchant")
    return dict(lang="en", shape="slow_burn", harm=4, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                f"Hi, I am {nm} from {agency}. We are hiring for a part-time job, earn Rs 3,000 to 8,000 daily by "
                f"liking YouTube videos from home. Are you interested?")),
        (3, msg("whatsapp", s.phone_fmt(p),
                "Great! Task 1: like these 3 videos and send the screenshots. "
                "https://www.youtube.com/watch?v=" + s.digits(4) + "abcXyZ")),
        (2, msg("whatsapp", s.phone_fmt(p), "Thank you! Rs 150 commission sent to your account. Very good work.")),
        (20, msg("whatsapp", s.phone_fmt(p),
                 f"For more tasks please join our Telegram group {tg} and contact the receptionist.")),
        (3, msg("whatsapp", s.phone_fmt(p),
                f"Today's merchant task: deposit Rs {inr(amt)} to {upi} and get 30% profit back within 1 hour. "
                f"This is a prepaid task, complete it fast to keep your bonus.")),
    ])


def job_hi_slow(s: Slots):
    p = s.phone10()
    amt = s.amount(2000, 20000, 500)
    upi = s.upi_handle("hrtasks")
    return dict(lang="hi", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                "नमस्ते! घर बैठे पार्ट टाइम काम करें और रोज़ ₹2,000 से ₹5,000 कमाएं। सिर्फ होटल को रेटिंग देनी है। "
                "रुचि हो तो 'हाँ' लिखें।")),
        (5, msg("whatsapp", s.phone_fmt(p), "बहुत बढ़िया! आपका पहला काम पूरा हुआ, ₹210 आपके खाते में भेज दिए गए हैं।")),
        (24, msg("whatsapp", s.phone_fmt(p), "आज के VIP टास्क से 40% अधिक कमाई होगी। सीट सीमित हैं।")),
        (2, msg("whatsapp", s.phone_fmt(p),
                f"VIP टास्क शुरू करने के लिए ₹{inr(amt)} इस UPI {upi} पर भेजें। एक घंटे में मूल राशि और कमीशन "
                f"वापस मिल जाएगा।")),
    ])


def job_hinglish_slow(s: Slots):
    p = s.phone10()
    amt = s.amount(3000, 25000, 500)
    dom = s.pick(["earn-daily-task", "amzn-task-pro", "taskhub-india"]) + "." + s.pick(["club", "vip", "online"])
    url = f"https://{dom}/register"
    return dict(lang="hinglish", shape="slow_burn", harm=3,
                fixtures=_fx({dom: {"age_days": s.r.randrange(5, 40)}}, {url: scam_link_report(s, url)}),
                steps=[
                    (0, msg("whatsapp", s.phone_fmt(p),
                            "Hello ji, part time job hai, ghar baithe mobile se roz 3000 tak kamao. Interested ho?")),
                    (2, msg("whatsapp", s.phone_fmt(p),
                            f"Is website pe apna account bana lo, wahin tasks milenge: {url}")),
                    (26, msg("whatsapp", s.phone_fmt(p),
                             "Aapka level 1 complete, 480 rupees aapke wallet mein dikh rahe honge.")),
                    (4, msg("whatsapp", s.phone_fmt(p),
                            f"Withdrawal unlock karne ke liye {inr(amt)} rupees ka recharge karo, phir poora paisa "
                            f"withdraw ho jayega. Aaj hi karna hoga.")),
                ])


def job_en_single_fee(s: Slots):
    return dict(lang="en", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(),
                f"Congratulations! You are selected for Amazon work from home job, salary Rs 32,000/month. Pay "
                f"registration fee of Rs {s.pick(['499', '999', '1,499'])} to confirm your joining today: "
                f"{s.upi_handle('amazonhr')}")),
    ])


def job_en_telegram_slow(s: Slots):
    p = s.phone10()
    amt1, amt2 = s.amount(1000, 3000, 100), s.amount(15000, 60000, 1000)
    return dict(lang="en", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"Hi, your resume was shortlisted for an online review job. Daily income Rs 2000-6000. "
                f"Contact HR on WhatsApp {s.phone_fmt(p)}")),
        (6, msg("whatsapp", s.phone_fmt(p),
                f"Welcome. First prepaid task: send Rs {inr(amt1)}, you will get Rs {inr(int(amt1 * 1.3))} back "
                f"in 10 minutes.")),
        (0.3, msg("whatsapp", s.phone_fmt(p), f"Rs {inr(int(amt1 * 1.3))} returned to you. Congratulations!")),
        (20, msg("whatsapp", s.phone_fmt(p),
                 f"Combination task unlocked. Transfer Rs {inr(amt2)} to finish it, otherwise your previous "
                 f"balance will be frozen.")),
    ])


def elec_en_single(s: Slots):
    p = s.phone10()
    return dict(lang="en", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(),
                f"Dear Consumer, your electricity power will be disconnected tonight at 9.30 pm from the electricity "
                f"office because your previous month bill was not updated. Please immediately contact our "
                f"electricity officer {s.phone_fmt(p)}. Thank you.")),
    ])


def elec_en_apk_slow(s: Slots):
    p = s.phone10()
    dom = s.pick(["bijli-bill-update", "power-bill-pay", "mseb-update", "bescom-billpay"]) + "." + s.pick(
        ["online", "in", "xyz"])
    url = f"http://{dom}/BillUpdate.apk"
    return dict(lang="en", shape="slow_burn", harm=2,
                fixtures=_fx({dom: {"age_days": s.r.randrange(1, 9)}}, {url: scam_link_report(s, url, kind="apk")}),
                steps=[
                    (0, msg("sms", s.phone_fmt(),
                            f"Dear Customer, your electricity connection will be cut tonight due to pending bill "
                            f"update. Call {s.phone_fmt(p)} immediately.")),
                    (0.5, call("+91" + p, 360)),
                    (0.1, msg("whatsapp", s.phone_fmt(p),
                              f"Install this bill update app and enter your consumer number and card details to "
                              f"avoid disconnection: {url}")),
                ])


def elec_hi_single(s: Slots):
    p = s.phone10()
    return dict(lang="hi", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(),
                f"प्रिय उपभोक्ता, आपका पिछले महीने का बिल अपडेट नहीं हुआ है, आज रात 9:30 बजे आपकी बिजली काट दी "
                f"जाएगी। कृपया तुरंत बिजली अधिकारी से {p} पर संपर्क करें।")),
    ])


def elec_hinglish_slow(s: Slots):
    p = s.phone10()
    amt = s.amount(10, 15)
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(),
                f"Priy upbhokta aaj raat aapki bijli kaat di jayegi kyunki pichla bill update nahi hua. Turant "
                f"{s.phone_fmt(p)} pe sampark karein.")),
        (1, call("+91" + p, 420)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   f"Sir bill update ke liye {amt} rupees ka payment karo aur screen share on karke AnyDesk "
                   f"app install kar lo, main yahin se update kar dunga.")),
    ])


def ra_en_amazon_refund(s: Slots):
    p = s.phone10()
    amt = s.amount(1999, 15999)
    return dict(lang="en", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"Amazon: your refund of Rs {inr(amt)} for order 403-{s.digits(7)} has failed. Call our refund "
                f"desk {s.phone_fmt(p)} to receive it.")),
        (2, call("+91" + p, 540)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   "To process your refund, install AnyDesk from Play Store and share the 9-digit code shown on the "
                   "screen with me. Keep the app open.")),
    ])


def ra_hi_bank(s: Slots):
    p = s.phone10()
    bank = s.pick(BANKS)
    return dict(lang="hi", shape="slow_burn", harm=1, fixtures=_fx(), steps=[
        (0, call("+91" + p, 300)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   f"मैं {bank[3]} कस्टमर केयर से बोल रहा हूँ। आपका नेट बैंकिंग ब्लॉक होने से बचाने के लिए "
                   f"प्ले स्टोर से 'TeamViewer QuickSupport' इंस्टॉल करें और जो आईडी दिखे वह मुझे भेजें।")),
    ])


def ra_hinglish_card(s: Slots):
    p = s.phone10()
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("sms", s.phone_fmt(p),
                f"Aapke credit card pe Rs {inr(s.amount(20000, 60000, 1))} ka transaction hua hai. Agar aapne nahi "
                f"kiya to turant {s.phone_fmt(p)} pe call karein.")),
        (0.2, call("+91" + p, 480)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   "Transaction cancel karne ke liye RustDesk app download karo aur screen share on rakho, OTP mat "
                   "batana bas screen open rakhna.")),
    ])


def ra_en_microsoft(s: Slots):
    dom = s.pick(["windows-support-help", "ms-secure-alert", "microsoft-defender-care"]) + "." + s.pick(
        ["online", "site", "xyz"])
    url = f"https://{dom}/fix"
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(1, 20)}}, {url: scam_link_report(s, url, kind="apk")}),
                steps=[(0, msg("email", "security@" + dom,
                               f"Microsoft Defender detected a Trojan on your computer and your bank details are at "
                               f"risk. Download the support tool from {url} and allow screen sharing so our "
                               f"technician can remove it."))])


def sx_en_video(s: Slots):
    p = s.phone10()
    amt = s.amount(10000, 50000, 1000)
    upi = s.upi_handle("settle")
    return dict(lang="en", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p), "hi dear, I am Riya from Jaipur. Can we be friends? 😊")),
        (0.5, call("+91" + p, 40)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   f"I have recorded your video call. Pay Rs {inr(amt)} to {upi} in 1 hour or I will send it to all "
                   f"your Facebook friends and family and upload it on YouTube.")),
    ])


def sx_en_police_followup(s: Slots):
    p, p2 = s.phone10(), s.phone10()
    off = s.pick(OFFICER_EN)
    amt = s.amount(30000, 150000, 1000)
    return dict(lang="en", shape="slow_burn", harm=3, fixtures=_fx(), steps=[
        (0, call("+91" + p, 35)),
        (0.1, msg("whatsapp", s.phone_fmt(p), "[Video] Check this video of yours. Talk to me now else I will share.")),
        (20, call("+91" + p2, 600)),
        (0.1, msg("whatsapp", s.phone_fmt(p2),
                  f"This is Inspector {off}, Delhi Police Cyber Cell. A girl has filed a complaint and your video is "
                  f"with us. To delete the video from YouTube and close the FIR, deposit Rs {inr(amt)} now. "
                  f"Do not discuss this with family.")),
    ])


def sx_hi(s: Slots):
    p = s.phone10()
    amt = s.amount(5000, 40000, 500)
    return dict(lang="hi", shape="slow_burn", harm=1, fixtures=_fx(), steps=[
        (0, call("+91" + p, 50)),
        (0.05, msg("whatsapp", s.phone_fmt(p),
                   f"तुम्हारी वीडियो कॉल की रिकॉर्डिंग मेरे पास है। अगर ₹{inr(amt)} नहीं भेजे तो यह वीडियो तुम्हारे "
                   f"सभी रिश्तेदारों को भेज दूँगी। किसी को बताया तो अंजाम बुरा होगा।")),
    ])


def sx_en_email_single(s: Slots):
    return dict(lang="en", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("email", f"hacker{s.digits(3)}@protonmail.com",
                f"I hacked your device and recorded you through your webcam. Transfer Rs {inr(s.amount(40000, 90000, 1000))} "
                f"in Bitcoin within 48 hours or I send the video to all your contacts. Do not tell anyone or "
                f"go to police.")),
    ])


def inv_en_group(s: Slots):
    p = s.phone10()
    dom = s.pick(["nse-vip-trade", "sebi-ipo-club", "zerodha-pro-elite", "bse-institution"]) + "." + s.pick(
        ["app", "online", "com", "vip"])
    url = f"https://{dom}/download"
    amt = s.amount(50000, 500000, 5000)
    stock = s.pick(["TATAMOTORS", "IRFC", "SUZLON", "RELIANCE", "ADANIPORTS"])
    return dict(lang="en", shape="slow_burn", harm=3,
                fixtures=_fx({dom: {"age_days": s.r.randrange(5, 60)}}, {url: scam_link_report(s, url, kind="apk")}),
                steps=[
                    (0, msg("whatsapp", s.phone_fmt(p),
                            "Welcome to NSE Wealth Club 📈 Daily free stock tips by Prof. Sharma. Stay active in the group.")),
                    (24, msg("whatsapp", s.phone_fmt(p),
                             f"Today's pick: {stock} buy above {s.amount(100, 1000)}, target +5%. Members booked "
                             f"18% profit this week!")),
                    (48, msg("whatsapp", s.phone_fmt(p),
                             "Institutional account slots open for IPO allotment with guaranteed 300% returns. "
                             "Only for active members.")),
                    (20, msg("whatsapp", s.phone_fmt(p),
                             f"Download our institutional trading app {url} and deposit minimum Rs {inr(amt)} to "
                             f"activate your VIP account today.")),
                ])


def inv_hi_group(s: Slots):
    p = s.phone10()
    amt = s.amount(25000, 200000, 5000)
    acct = "9201" + s.digits(8)
    return dict(lang="hi", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                "शेयर बाज़ार ग्रुप में आपका स्वागत है। हर दिन मुफ्त टिप्स, पिछले महीने सदस्यों को 40% मुनाफा हुआ।")),
        (30, msg("whatsapp", s.phone_fmt(p), "आज का शेयर: खरीदें और 3 दिन होल्ड करें। लक्ष्य 8%।")),
        (48, msg("whatsapp", s.phone_fmt(p),
                 f"IPO में पक्का आवंटन चाहिए तो ₹{inr(amt)} कंपनी के खाते {acct} में जमा करें। आज आखिरी दिन है।")),
    ])


def inv_hinglish_crypto(s: Slots):
    p = s.phone10()
    upi = s.upi_handle("cryptofund")
    amt = s.amount(20000, 100000, 1000)
    return dict(lang="hinglish", shape="slow_burn", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(p),
                "Bhai mere group mein aa jao, crypto trading se daily 5% return, guaranteed. Screenshot dekho members ke.")),
        (40, msg("whatsapp", s.phone_fmt(p), "Kal ke signal pe sabko 22% profit hua 🔥 aap kab start kar rahe ho?")),
        (24, msg("whatsapp", s.phone_fmt(p),
                 f"Minimum {inr(amt)} invest karna hai, {upi} pe bhej do, main aapka account bana deta hoon.")),
    ])


def inv_en_single_link(s: Slots):
    dom = s.pick(["ipo-allotment-vip", "stock-guru-signals", "nifty-profit-club"]) + "." + s.pick(["online", "xyz", "top"])
    url = f"https://{dom}/join"
    return dict(lang="en", shape="single", harm=0,
                fixtures=_fx({dom: {"age_days": s.r.randrange(2, 30)}}, {url: scam_link_report(s, url)}),
                steps=[(0, msg("sms", s.phone_fmt(),
                               f"Earn 10% daily with SEBI registered experts. Guaranteed returns, no loss. Register "
                               f"with your PAN and bank details at {url} and deposit Rs 10,000 to start."))])


def spoof_mom_hinglish(s: Slots):
    amt = s.amount(8000, 40000, 500)
    return dict(lang="hinglish", shape="single", harm=0, fixtures=_fx(), steps=[
        (0, msg("whatsapp", "Mom",
                f"Beta ye mera naya number hai, purana phone kharab ho gaya. Hospital mein urgent ₹{inr(amt)} "
                f"chahiye, abhi {s.upi_handle('medhelp')} pe bhej do, ghar aake bataungi.")),
    ])


def spoof_papa_en(s: Slots):
    amt = s.amount(15000, 60000, 1000)
    return dict(lang="en", shape="slow_burn", harm=1, fixtures=_fx(), steps=[
        (0, msg("whatsapp", "Papa", "Hi beta, this is my new number, save it. Old phone fell in water.", contact=False)),
        (2, msg("whatsapp", "Papa",
                f"I am stuck at the airport, my card got blocked. Transfer Rs {inr(amt)} to account "
                f"5010{s.digits(10)} IFSC SBIN0001234 immediately, will explain later. Don't tell Mummy.",
                contact=False)),
    ])


def spoof_bank_manager_hi(s: Slots):
    return dict(lang="hi", shape="slow_burn", harm=1, fixtures=_fx(), steps=[
        (0, msg("whatsapp", "Bank Manager",
                "नमस्ते, मैं आपकी शाखा का बैंक मैनेजर हूँ। आपके खाते का केवाईसी आज अपडेट होना ज़रूरी है।")),
        (0.2, msg("whatsapp", "Bank Manager",
                  "आपके फ़ोन पर अभी जो ओटीपी आया है वह मुझे बताएं, केवाईसी पूरा हो जाएगा।")),
    ])


SCAM_TEMPLATES = {
    "digital_arrest": [da_en_slow, da_hi_slow, da_hinglish_slow, da_en_intl, da_en_single],
    "courier_customs": [cc_en_single_link, cc_hi_single_link, cc_en_customs_slow, cc_hinglish_courier_call,
                        cc_en_fedex_drugs],
    "kyc_bank": [kyc_en_sbi_link, kyc_en_hdfc_link, kyc_hi_link, kyc_hinglish_paytm_slow, kyc_en_icici_rewards],
    "upi_collect": [upi_en_olx, upi_hi_cashback, upi_hinglish_wrong_transfer, upi_en_reward_collect],
    "job_task": [job_en_youtube_slow, job_hi_slow, job_hinglish_slow, job_en_single_fee, job_en_telegram_slow],
    "electricity_bill": [elec_en_single, elec_en_apk_slow, elec_hi_single, elec_hinglish_slow],
    "remote_access": [ra_en_amazon_refund, ra_hi_bank, ra_hinglish_card, ra_en_microsoft],
    "sextortion": [sx_en_video, sx_en_police_followup, sx_hi, sx_en_email_single],
    "investment_group": [inv_en_group, inv_hi_group, inv_hinglish_crypto, inv_en_single_link],
    "contact_name_spoof": [spoof_mom_hinglish, spoof_papa_en, spoof_bank_manager_hi],
}


# ---------------------------------------------------------------- benign: weekly traffic
# Each benign builder returns (lang, [(day, hour, event)], fixtures). The runner sees one user-week.

def _bank(s: Slots):
    return s.pick(BANKS)


def otp_text(s: Slots, bank, lang: str) -> str:
    o = s.otp()
    if lang == "hi":
        return (f"{o} आपके {bank[3]} खाते में लॉगिन के लिए ओटीपी है। यह 5 मिनट के लिए मान्य है। "
                f"इसे किसी के साथ साझा न करें।")
    return s.pick([
        f"{o} is your OTP for login to {bank[1]} NetBanking. Valid for 5 mins. Do not share it with anyone. -{bank[2]}",
        f"OTP for transaction of INR {inr(s.amount(100, 20000))}.00 on your {bank[2]} card XX{s.digits(4)} at "
        f"AMAZON is {o}. Do not share OTP with anyone.",
        f"Dear Customer, {o} is the OTP to add a beneficiary in {bank[1]} NetBanking. Never share your OTP. If "
        f"not done by you, call the number on the back of your card.",
    ])


def debit_text(s: Slots, bank, lang: str) -> str:
    amt = s.amount(50, 6000)
    if lang == "hi":
        return (f"आपके {bank[3]} खाते XX{s.digits(4)} से ₹{inr(amt)} UPI द्वारा डेबिट किए गए। "
                f"उपलब्ध शेष ₹{inr(s.amount(5000, 90000))}। यदि यह आपने नहीं किया तो बैंक को सूचित करें।")
    return s.pick([
        f"Rs.{inr(amt)}.00 debited from A/c XX{s.digits(4)} on {s.r.randrange(1, 28):02d}-03-26 to VPA "
        f"{s.pick(['swiggy', 'zomato', 'bigbasket', 'irctc'])}@{s.pick(['icici', 'axisbank', 'hdfcbank'])}. "
        f"UPI Ref {s.digits(12)}. Not you? Call 18002586161 -{bank[2]}",
        f"Your A/c XX{s.digits(4)} is debited for INR {inr(amt)}.00 on account of ATM withdrawal. Available "
        f"balance INR {inr(s.amount(2000, 80000))}.00 -{bank[2]}",
        f"INR {inr(amt)}.00 spent on {bank[2]} Credit Card XX{s.digits(4)} at {s.pick(['DMART', 'RELIANCE FRESH', 'BOOKMYSHOW', 'UBER'])} "
        f"on {s.r.randrange(1, 28):02d}-Mar-26. Avl limit INR {inr(s.amount(20000, 200000))}.",
    ])


def credit_text(s: Slots, bank, lang: str) -> str:
    amt = s.amount(200, 25000)
    if lang == "hi":
        return (f"आपके खाते XX{s.digits(4)} में ₹{inr(amt)} जमा कर दिए गए हैं। UPI संदर्भ {s.digits(12)}। "
                f"- {bank[3]}")
    return s.pick([
        f"Dear Customer, Rs.{inr(amt)}.00 credited to your A/c XX{s.digits(4)} on {s.r.randrange(1, 28):02d}-03-26 "
        f"by NEFT from {s.pick(NAMES_EN).upper()} KUMAR. -{bank[2]}",
        f"Your a/c XX{s.digits(4)} is credited with INR {inr(amt)}.00 via UPI from {s.pick(NAMES_EN)}. "
        f"Ref {s.digits(12)}. -{bank[2]}",
    ])


def times(s: Slots, n: int) -> list[tuple[int, float]]:
    out = sorted((s.r.randrange(0, 7), s.r.randrange(7, 23) + s.r.randrange(0, 60) / 60) for _ in range(n))
    return out


def filler(s: Slots, lang: str, n: int) -> list[tuple[int, float, dict]]:
    """Background traffic every user gets: bank OTPs / debits, an app OTP, a contact's message."""
    bank = _bank(s)
    pool = [
        lambda: msg("sms", bank[0], otp_text(s, bank, lang)),
        lambda: msg("sms", bank[0], debit_text(s, bank, lang)),
        lambda: msg("sms", s.pick(["AX-SWIGGY", "VM-ZOMATO", "JD-UBERIN", "AD-OLACAB"]),
                    f"{s.otp()} is your login OTP. Do not share it with anyone."),
        lambda: msg("whatsapp", s.pick([f[0] for f in FAMILY]),
                    s.pick(["Reached home?", "Call me when free", "Khana kha liya?", "Ok 👍", "Kal milte hain",
                            "घर कब आ रहे हो?", "Happy birthday to Chintu! 🎂"]), contact=True),
    ]
    return [(d, h, s.pick(pool)()) for d, h in times(s, n)]


def b_bank_otp(s: Slots, lang: str):
    bank = _bank(s)
    evs = []
    for d in range(7):  # daily OTPs, a few repeated
        for _ in range(s.r.randrange(1, 3)):
            evs.append((d, s.r.randrange(8, 22) + 0.5, msg("sms", bank[0], otp_text(s, bank, lang))))
    return evs, _fx()


def b_bank_txn(s: Slots, lang: str):
    bank = _bank(s)
    evs = [(d, h, msg("sms", bank[0], debit_text(s, bank, lang) if s.r.random() < 0.6 else credit_text(s, bank, lang)))
           for d, h in times(s, s.r.randrange(5, 9))]
    return evs, _fx()


def b_upi(s: Slots, lang: str):
    app = s.pick([("PhonePe", "phonepe"), ("Google Pay", "gpay"), ("Paytm", "paytm")])
    evs = []
    for d, h in times(s, s.r.randrange(5, 9)):
        shop, amt = s.pick(SHOPS), s.amount(20, 2500)
        if lang == "hi":
            t = f"आपने {shop} को ₹{inr(amt)} का भुगतान सफलतापूर्वक किया।"
        elif lang == "hinglish":
            t = f"₹{inr(amt)} {shop} ko pay ho gaya. UPI Ref {s.digits(12)}"
        else:
            t = s.pick([f"You paid ₹{inr(amt)} to {shop}. UPI transaction ID {s.digits(12)}.",
                        f"₹{inr(amt)} received from {s.pick(NAMES_EN)} via UPI.",
                        f"Payment of ₹{inr(amt)} to {shop} was successful."])
        evs.append((d, h, msg("notification", app[0], t)))
    return evs, _fx()


DELIVERY = [  # sender, domain, builder
    ("AX-SWIGGY", "swiggy.com", lambda s, u: f"Your Swiggy order of {s.pick(FOODS)} is out for delivery. Track live: {u}"),
    ("VM-ZOMATO", "zomato.com", lambda s, u: f"Your Zomato order is on the way! {s.pick(NAMES_EN)} is your delivery partner. Track: {u}"),
    ("JD-AMAZON", "amazon.in", lambda s, u: f"Delivered: your Amazon package with {s.pick(ITEMS)} was delivered. Rate your experience: {u}"),
    ("JD-AMAZON", "amzn.in", lambda s, u: f"Arriving today: {s.pick(ITEMS)}. Share OTP {s.digits(4)} with the delivery agent only at the time of delivery. Track: {u}"),
    ("VK-FLPKRT", "flipkart.com", lambda s, u: f"Your Flipkart order for {s.pick(ITEMS)} has been shipped and will be delivered by {s.pick(['Friday', 'Saturday', 'Monday'])}. Track: {u}"),
    ("BZ-MYNTRA", "myntra.com", lambda s, u: f"Out for delivery: your Myntra order. Pay on delivery amount Rs {inr(s.amount(299, 2999))}. Details: {u}"),
]


def b_delivery(s: Slots, lang: str):
    evs, links, doms = [], {}, {}
    for d, h in times(s, s.r.randrange(4, 8)):
        sender, dom, f = s.pick(DELIVERY)
        url = f"https://{'www.' if dom.count('.') == 1 and s.r.random() < 0.3 else ''}{dom}/{s.pick(['t', 'track', 'd', 'o'])}/{s.digits(6)}"
        text = f(s, url)
        if lang == "hinglish":
            text = text + " Thank you ji!"
        evs.append((d, h, msg("sms", sender, text)))
        doms[dom] = {"age_days": s.r.randrange(3000, 9000)}
        links[url] = report(url, password=s.r.random() < 0.4, title="Track your order")
    return evs, _fx(doms, links)


def b_family(s: Slots, lang: str):
    who = s.pick(FAMILY)[0]
    other = s.pick([f[0] for f in FAMILY if f[0] != who])
    if lang == "hi":
        lines = ["बेटा खाना खा लिया?", "शाम को फ़ोन करना", "दवाई ले आना मेरे लिए", "ठीक है, ध्यान रखना",
                 f"₹{s.pick(['500', '1,000', '2,000'])} भेज देना, सब्ज़ी वाले को देने हैं"]
    elif lang == "hinglish":
        lines = ["Kahan ho? Call karo", "Aaj dinner pe kya banau?", "Bijli ka bill bhar diya kya?",
                 f"Beta paise bhej do ₹{s.pick(['500', '800', '1500'])}, kal wapas kar dungi",
                 "Haan theek hai, kal milte hain", "Gas cylinder book kar dena"]
    else:
        lines = ["Did you reach office?", "Please pick up milk on the way back", "Call me when you are free",
                 f"Can you send ₹{s.pick(['500', '1,200', '2,000'])} for the electrician? Will return tomorrow",
                 "Movie on Saturday?", "Mom says hi", "Sending the photos from the wedding"]
    evs = [(d, h, msg("whatsapp", who, s.pick(lines), contact=True)) for d, h in times(s, s.r.randrange(5, 9))]
    ask = [line for line in lines if "₹" in line][0]
    d, h = times(s, 1)[0]
    evs.append((d, h, msg("whatsapp", who, ask, contact=True)))
    evs.append((s.r.randrange(0, 7), 20.0, call(s.phone_fmt(), s.r.randrange(60, 900), contact=True)))
    evs.append((s.r.randrange(0, 7), 18.5, msg("whatsapp", other, s.pick(lines), contact=True)))
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_salary(s: Slots, lang: str):
    bank = _bank(s)
    amt = s.amount(25000, 180000, 1)
    emp = s.pick(["INFOSYS LTD", "TCS", "ACME SOFTWARE PVT LTD", "WIPRO LIMITED", "RELIANCE RETAIL"])
    if lang == "hi":
        sal = f"वेतन: आपके खाते XX{s.digits(4)} में {emp} से ₹{inr(amt)} जमा किए गए हैं। - {bank[3]}"
    else:
        sal = (f"Your A/c XX{s.digits(4)} has been credited with INR {inr(amt)}.00 on 01-03-26 towards SALARY "
               f"from {emp}. Avl Bal INR {inr(amt + s.amount(1000, 50000))}.00 -{bank[2]}")
    evs = [(0, 10.0, msg("sms", bank[0], sal)),
           (1, 9.0, msg("sms", bank[0], f"EMI of Rs {inr(s.amount(3000, 25000))} for loan XX{s.digits(4)} has been debited. -{bank[2]}")),
           (2, 11.0, msg("sms", "AD-SIPMFD", f"Your SIP of Rs {inr(s.amount(1000, 10000, 500))} in {s.pick(['Parag Parikh Flexi Cap', 'HDFC Index Fund', 'Axis Bluechip'])} is processed."))]
    evs += filler(s, lang, s.r.randrange(2, 4))
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_emi(s: Slots, lang: str):
    lender = s.pick([("VM-BAJAJF", "Bajaj Finance"), ("AD-HDFCBK", "HDFC Bank"), ("JD-TATACP", "Tata Capital")])
    amt = s.amount(1500, 30000)
    if lang == "hi":
        t1 = f"प्रिय ग्राहक, आपकी ₹{inr(amt)} की EMI 05-04-26 को देय है। कृपया खाते में पर्याप्त राशि रखें। - {lender[1]}"
    elif lang == "hinglish":
        t1 = f"Reminder: aapki EMI Rs {inr(amt)} 5 April ko due hai. Account mein balance maintain karein. -{lender[1]}"
    else:
        t1 = (f"Dear Customer, EMI of Rs {inr(amt)} for loan a/c XX{s.digits(4)} is due on 05-Apr-26. Please "
              f"maintain sufficient balance to avoid charges. -{lender[1]}")
    t2 = f"Your EMI of Rs {inr(amt)} has been successfully paid. Thank you. -{lender[1]}"
    evs = [(0, 10.0, msg("sms", lender[0], t1)), (3, 10.0, msg("sms", lender[0], t1)),
           (5, 12.0, msg("sms", lender[0], t2))]
    evs += filler(s, lang, s.r.randrange(2, 5))
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_app_otp(s: Slots, lang: str):
    apps = [("AX-SWIGGY", "Swiggy"), ("VM-ZOMATO", "Zomato"), ("JD-UBERIN", "Uber"), ("AD-OLACAB", "Ola"),
            ("VK-DREAMS", "Dream11"), ("BZ-MEESHO", "Meesho"), ("JD-PHONPE", "PhonePe")]
    evs = []
    for d, h in times(s, s.r.randrange(5, 9)):
        hdr, name = s.pick(apps)
        o = s.otp()
        if lang == "hi":
            t = f"{name} लॉगिन के लिए आपका ओटीपी {o} है। इसे किसी के साथ साझा न करें।"
        else:
            t = s.pick([f"{o} is your {name} verification code. Do not share it with anyone.",
                        f"Use OTP {o} to log in to {name}. Valid for 10 minutes. Never share your OTP.",
                        f"Your {name} OTP is {o}. Please do not share this OTP with anyone for security reasons."])
        evs.append((d, h, msg("sms", hdr, t)))
    return evs, _fx()


def b_appointments(s: Slots, lang: str):
    evs, doms, links = [], {}, {}
    url = f"https://www.practo.com/a/{s.digits(6)}"
    doms["practo.com"] = {"age_days": 5000}
    links[url] = report(url, title="Practo appointment")
    doc = s.pick(["Dr. Mehta", "Dr. Iyer", "Dr. Khan", "Dr. Reddy"])
    if lang == "hi":
        evs.append((0, 9.0, msg("sms", "VM-PRACTO", f"आपका {doc} के साथ अपॉइंटमेंट कल सुबह 11 बजे तय है। विवरण: {url}")))
    else:
        evs.append((0, 9.0, msg("sms", "VM-PRACTO", f"Your appointment with {doc} is confirmed for tomorrow 11:00 AM. Details: {url}")))
    pnr = s.digits(10)
    evs.append((2, 18.0, msg("sms", "AD-IRCTCi", f"PNR {pnr}, Train 12951, CNF/B2/{s.r.randrange(1, 72)}. Happy journey. -IRCTC")))
    evs.append((3, 8.0, msg("sms", "AD-IRCTCi", f"PNR {pnr}: chart prepared. Your berth B2/{s.r.randrange(1, 72)} is confirmed.")))
    evs.append((4, 19.0, msg("sms", "JM-BMSHOW", f"Booking confirmed: 2 tickets for {s.pick(['Pushpa 3', 'Jawan 2', 'Stree 3'])}, PVR, Screen 4, 7:15 PM. Booking ID {s.digits(8)}.")))
    evs.append((5, 10.0, msg("sms", "VM-APOLLO", f"Reminder: your lab test at Apollo Diagnostics is due tomorrow at 8 AM. Please come fasting.")))
    evs += filler(s, lang, 2)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx(doms, links)


def b_telecom(s: Slots, lang: str):
    op = s.pick([("JD-JIOINF", "Jio", "jio.com"), ("AD-AIRTEL", "Airtel", "airtel.in"), ("VK-VIINDA", "Vi", "myvi.in")])
    url = f"https://www.{op[2]}/recharge"
    doms = {op[2]: {"age_days": s.r.randrange(3000, 8000)}}
    links = {url: report(url, title=f"{op[1]} recharge")}
    if lang == "hi":
        ts = [f"आपका {op[1]} प्लान 2 दिन में समाप्त हो रहा है। ₹299 में रिचार्ज करें: {url}",
              f"आपने आज का 90% डेटा इस्तेमाल कर लिया है। - {op[1]}"]
    elif lang == "hinglish":
        ts = [f"Aapka {op[1]} plan 2 din mein khatam ho raha hai. Abhi recharge karein ₹299 mein: {url}",
              f"90% daily data use ho gaya hai. - {op[1]}"]
    else:
        ts = [f"Your {op[1]} plan expires in 2 days. Recharge now with ₹299 for 2GB/day and unlimited calls: {url}",
              f"You have used 90% of your daily data quota. -{op[1]}",
              f"Get 30 days of {s.pick(['JioHotstar', 'Airtel Xstream', 'Disney+'])} free on recharge of ₹449. Offer valid till Sunday. {url}"]
    evs = [(d, h, msg("sms", op[0], s.pick(ts))) for d, h in times(s, s.r.randrange(3, 6))]
    evs += filler(s, lang, 2)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx(doms, links)


def b_gov(s: Slots, lang: str):
    url = "https://www.incometax.gov.in/iec/foportal/"
    doms = {"incometax.gov.in": {"age_days": 4000}, "epfindia.gov.in": {"age_days": 6000}}
    links = {url: report(url, password=True, title="e-Filing Home Page, Income Tax Department")}
    amt = s.amount(1000, 40000)
    if lang == "hi":
        t = [f"आयकर विभाग: आकलन वर्ष 2025-26 के लिए आपका आयकर रिफंड ₹{inr(amt)} आपके बैंक खाते XX{s.digits(4)} में जमा कर दिया गया है।",
             "ITR दाखिल करने की अंतिम तिथि 31 जुलाई है। कृपया समय पर रिटर्न दाखिल करें। - आयकर विभाग"]
    else:
        t = [f"Refund of Rs {inr(amt)} for AY 2025-26 has been credited to your bank account XX{s.digits(4)}. -Income Tax Department",
             f"Dear taxpayer, your ITR for AY 2025-26 has been processed under section 143(1). Login to {url} to view the intimation.",
             f"EPFO: Rs {inr(s.amount(2000, 15000))} contribution for Feb-26 credited to your UAN XXXX{s.digits(4)}. Check passbook at https://passbook.epfindia.gov.in"]
    hdr = s.pick(["AD-ITDEFL", "VM-CBDTIT", "JD-EPFOHO"])
    evs = [(d, h, msg("sms", hdr, s.pick(t))) for d, h in times(s, s.r.randrange(2, 4))]
    evs += filler(s, lang, 3)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx(doms, links)


def b_2fa(s: Slots, lang: str):
    t = [lambda: f"G-{s.otp()} is your Google verification code.",
         lambda: f"Your WhatsApp code: {s.digits(3)}-{s.digits(3)}. Don't share this code with others.",
         lambda: f"Use {s.digits(7)} as Microsoft account security code.",
         lambda: f"{s.otp()} is your Instagram code. Don't share it.",
         lambda: f"Your DigiLocker OTP is {s.otp()}. Valid for 10 minutes."]
    hdrs = ["VM-GOOGLE", "JD-WHTSAP", "AD-MSFTAC", "VK-INSTAG", "AX-DIGILK"]
    evs = []
    for d, h in times(s, s.r.randrange(4, 8)):
        i = s.r.randrange(len(t))
        evs.append((d, h, msg("sms", hdrs[i], t[i]())))
    evs += filler(s, lang, 2)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_legit_kyc(s: Slots, lang: str):
    bank = _bank(s)
    if lang == "hi":
        t = (f"प्रिय ग्राहक, आपके खाते का केवाईसी आवधिक अपडेट देय है। कृपया अपनी नज़दीकी शाखा पर जाएँ या "
             f"नेटबैंकिंग से अपडेट करें। बैंक कभी भी ओटीपी या पासवर्ड नहीं मांगता। - {bank[3]}")
    elif lang == "hinglish":
        t = (f"Dear customer, aapka KYC periodic update due hai. Nearest branch visit karein ya NetBanking se update "
             f"karein. Bank kabhi OTP nahi maangta. -{bank[2]}")
    else:
        t = (f"Dear Customer, as per RBI guidelines your KYC is due for periodic update. Please visit your nearest "
             f"{bank[1]} branch or update it via NetBanking. {bank[2]} never asks for your OTP or password. -{bank[2]}")
    evs = [(0, 11.0, msg("sms", bank[0], t)), (4, 11.0, msg("sms", bank[0], t))]
    evs += filler(s, lang, s.r.randrange(3, 6))
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_marketing(s: Slots, lang: str):
    bank = _bank(s)
    lim = inr(s.amount(1, 10) * 100000)
    ts = [f"Good news! You are now eligible for a pre-approved personal loan of up to Rs {lim} at attractive rates. "
          f"Apply on the {bank[2]} mobile app. T&C apply.",
          f"Congratulations! You are now eligible for a {bank[2]} Platinum Credit Card with lifetime free benefits. "
          f"Apply on the app in 2 minutes.",
          f"You are now eligible for an instant top-up loan. Get funds in your account in minutes. Apply via "
          f"{bank[1]} NetBanking. T&C apply."]
    if lang == "hi":
        ts = [f"खुशखबरी! आप {bank[3]} से ₹{lim} तक के प्री-अप्रूव्ड पर्सनल लोन के लिए पात्र हैं। ऐप पर आवेदन करें। नियम व शर्तें लागू।"]
    evs = [(d, h, msg("sms", bank[0], s.pick(ts))) for d, h in times(s, s.r.randrange(2, 4))]
    evs.append((s.r.randrange(0, 7), 13.0, msg("sms", "VM-CREDCL", "Your CRED rewards are waiting! Pay your credit card bill on CRED and win cashback up to ₹1,000.")))
    evs += filler(s, lang, 3)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_adversarial(s: Slots, lang: str):
    """Benign messages full of scary words."""
    bank = _bank(s)
    city = s.pick(CITIES)
    pool_en = [
        ("VM-PSKMEA", f"Police verification for your passport application file {s.pick(['BN', 'MU', 'DL'])}{s.digits(10)} "
                      f"has been initiated. The officer from {city} police station may visit you. Keep your documents "
                      f"ready. -Passport Seva"),
        (bank[0], f"KYC update reminder: please update your KYC at the nearest {bank[1]} branch before 31-Mar-26. "
                  f"Ignore if already done. -{bank[2]}"),
        (bank[0], f"Beware of fraud! {bank[1]} never asks for OTP, PIN or password over phone, SMS or email. "
                  f"Do not share OTP with anyone. Report fraud at 1930. -{bank[2]}"),
        ("AD-CYBDOT", "Cyber Crime Awareness: never share OTP or install screen sharing apps like AnyDesk on a "
                      "stranger's request. Report cyber fraud at cybercrime.gov.in or dial 1930."),
        ("VM-RBISAY", "RBI Kehta Hai: be aware of fake calls claiming digital arrest. No government agency arrests "
                      "anyone over video call. Report at 1930."),
        ("JD-AMAZON", f"Refund of Rs {inr(s.amount(199, 2999))} for your returned item has been processed and will "
                      f"reach your account in 3-5 business days."),
        ("AD-BESCOM", f"Dear consumer, your electricity bill of Rs {inr(s.amount(400, 3500))} is due on 10-04-26. "
                      f"Pay via the BESCOM Mithra app to avoid late fee."),
        (bank[0], f"Your {bank[2]} account XX{s.digits(4)} was temporarily blocked after 3 wrong PIN attempts and "
                  f"is now unblocked. Visit the branch if this was not you."),
        ("VM-TRAIOR", "TRAI never calls to disconnect your number. Beware of fraud calls claiming to be from TRAI. "
                      "Report suspicious calls on Sanchar Saathi."),
    ]
    pool_hi = [
        (bank[0], f"केवाईसी अपडेट अनुस्मारक: कृपया 31-03-26 से पहले अपनी नज़दीकी {bank[3]} शाखा में केवाईसी अपडेट करें।"),
        (bank[0], "सावधान! बैंक कभी भी फ़ोन पर ओटीपी या पिन नहीं मांगता। ओटीपी किसी के साथ साझा न करें।"),
        ("VM-PSKMEA", f"आपके पासपोर्ट आवेदन के लिए पुलिस सत्यापन शुरू हो गया है। {s.pick(CITIES_HI)} पुलिस अधिकारी आपसे संपर्क कर सकते हैं।"),
        ("VM-RBISAY", "आरबीआई कहता है: डिजिटल अरेस्ट जैसी कोई प्रक्रिया नहीं होती। ऐसी कॉल आने पर 1930 पर शिकायत करें।"),
    ]
    pool = pool_hi + pool_en[:4] if lang == "hi" else pool_en
    evs = [(d, h, msg("sms", *s.pick(pool))) for d, h in times(s, s.r.randrange(3, 6))]
    evs += filler(s, lang, 2)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


def b_card_statement(s: Slots, lang: str):
    bank = _bank(s)
    due = s.amount(2000, 60000)
    ts = [f"{bank[2]} Credit Card XX{s.digits(4)} statement: total due Rs {inr(due)}, minimum due Rs {inr(due // 20)}, "
          f"due date 05-04-26. Pay via NetBanking or the app.",
          f"Payment of Rs {inr(due)} received towards your {bank[2]} Credit Card XX{s.digits(4)}. Thank you.",
          f"Reminder: your {bank[2]} Credit Card payment of Rs {inr(due)} is due in 3 days. Ignore if already paid."]
    evs = [(d, h, msg("sms", bank[0], s.pick(ts))) for d, h in times(s, s.r.randrange(2, 4))]
    evs.append((s.r.randrange(0, 7), 9.0, msg("email", f"statements@{s.pick(['hdfcbank.net', 'icicibank.com', 'sbicard.com'])}",
                                                  f"Your credit card e-statement for March 2026 is attached. Total amount due Rs {inr(due)}.")))
    evs += filler(s, lang, 3)
    return sorted(evs, key=lambda x: (x[0], x[1])), _fx()


BENIGN_BUILDERS = {
    "bank_otp": b_bank_otp, "bank_txn_alerts": b_bank_txn, "upi_confirmations": b_upi,
    "delivery_updates": b_delivery, "family_chat": b_family, "salary_credit": b_salary, "emi_reminder": b_emi,
    "app_otp": b_app_otp, "appointments": b_appointments, "telecom_offers": b_telecom,
    "gov_tax_notices": b_gov, "two_factor": b_2fa, "legit_kyc_reminder": b_legit_kyc,
    "marketing_eligible": b_marketing, "adversarial_benign": b_adversarial, "card_statement": b_card_statement,
}
BENIGN_LANGS = ["en", "hi", "hinglish"]


# ---------------------------------------------------------------- distributed slow burn (memory upgrade)
# Scam stories told by DIFFERENT numbers over days, with NO shared phone / domain / org / claim entity;
# each message looks harmless alone. Benign look-alikes: real group invites, recruiter headers, family
# money requests from saved contacts, local services that introduce themselves. Generated last with
# their own RNG and split stream, so the original 123 scenarios and their splits are unchanged.
DSB_CATEGORY = "distributed_slow_burn"
DSB_SCAMS = 12
DSB_BENIGN = 12
COMPANIES = ["TalentBridge", "HireNest", "WorkSphere", "JobKart"]
CLUBS = ["Alpha Traders", "Bull Run Circle", "Nifty Masters", "Wealth Wave"]


def _nums(s: Slots, n: int) -> list[str]:
    out: list[str] = []
    while len(out) < n:
        p = s.phone10()
        if p not in out:
            out.append(p)
    return out


def dsb_inv_en(s: Slots):
    a, b, c = _nums(s, 3)
    amt = s.amount(2999, 9999, 1000) - 1
    return dict(lang="en", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hi {s.pick(NAMES_EN)}, I got your number from the {s.pick(CITIES)} "
                                            f"investors meetup. Hope you are doing well! I share market updates sometimes.")),
        (s.r.randrange(20, 40), msg("whatsapp", s.phone_fmt(b),
                                    f"Good morning! Our members booked {s.r.randrange(15, 35)}% profit this month with "
                                    f"expert stock tips. Guaranteed returns, only for serious members.")),
        (s.r.randrange(24, 50), msg("whatsapp", s.phone_fmt(c),
                                    f"Only {s.r.randrange(2, 6)} VIP seats left in the premium batch. The membership fee "
                                    f"of Rs {inr(amt)} is required to secure your slot.")),
    ])


def dsb_job_en(s: Slots):
    a, b, c = _nums(s, 3)
    return dict(lang="en", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hello, this is {s.pick(NAMES_EN)} from {s.pick(COMPANIES)} HR. We saw "
                                            f"your profile on a job portal and have a remote opening for you.")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(b),
                                    f"Work from home opportunity: rate hotels online and earn Rs {inr(s.amount(2000, 5000, 500))} "
                                    f"daily. Many members are already earning.")),
        (s.r.randrange(20, 48), msg("whatsapp", s.phone_fmt(c),
                                    f"Your onboarding is approved. A refundable training kit fee of Rs "
                                    f"{inr(s.amount(999, 2999, 500))} is needed to activate your employee ID.")),
    ])


def dsb_relative_en(s: Slots):
    a, b, c, d = _nums(s, 4)
    who = s.pick(["Chachu", "Mama", "Uncle", "Fufa ji"])
    return dict(lang="en", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hi beta, this is {who}. My phone fell in water, this is my new number. Save it.")),
        (s.r.randrange(20, 30), call("+91" + b, s.r.randrange(60, 300))),
        (s.r.randrange(2, 20), msg("whatsapp", s.phone_fmt(c),
                                   f"I am Dr. {s.pick(['Mehta', 'Rao', 'Khanna'])}'s assistant at City Hospital. Your "
                                   f"{who.lower()} is admitted and asked me to message you.")),
        (s.r.randrange(1, 4), msg("whatsapp", s.phone_fmt(d),
                                  f"The advance deposit of Rs {inr(s.amount(15000, 40000, 5000))} is required before the "
                                  f"procedure. Please arrange it, he will return it.")),
    ])


def dsb_crypto_en(s: Slots):
    a, b, c, d = _nums(s, 4)
    return dict(lang="en", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hey, sorry wrong number! I was looking for my friend {s.pick(NAMES_EN)}. "
                                            f"Nice talking to you anyway 🙂")),
        (s.r.randrange(24, 48), msg("whatsapp", s.phone_fmt(b),
                                    "My uncle is a crypto mentor, his students make steady weekly income. I can introduce "
                                    "you if you like.")),
        (s.r.randrange(24, 48), msg("whatsapp", s.phone_fmt(c),
                                    "Welcome to the mentor's trading desk. Your account is ready; withdrawals open after the "
                                    "first top-up.")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(d),
                                    f"To unlock your withdrawal, a verification deposit of Rs {inr(s.amount(5000, 15000, 100))} "
                                    f"is needed.")),
    ])


def dsb_inv_hi(s: Slots):
    a, b, c = _nums(s, 3)
    return dict(lang="hi", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"नमस्ते, मुझे आपका नंबर {s.pick(CITIES_HI)} के निवेशक ग्रुप से मिला। मैं "
                                            f"कभी-कभी बाज़ार की जानकारी भेजता हूँ।")),
        (s.r.randrange(20, 40), msg("whatsapp", s.phone_fmt(b),
                                    f"हमारे सदस्यों को इस महीने {s.r.randrange(15, 35)}% मुनाफा हुआ। विशेषज्ञ टिप्स, गारंटी के साथ।")),
        (s.r.randrange(24, 50), msg("whatsapp", s.phone_fmt(c),
                                    f"प्रीमियम बैच में सिर्फ {s.r.randrange(2, 6)} सीटें बाकी हैं। अपनी सीट पक्की करने के लिए "
                                    f"₹{inr(s.amount(2999, 9999, 1000) - 1)} सदस्यता शुल्क देना होगा।")),
    ])


def dsb_job_hi(s: Slots):
    a, b, c = _nums(s, 3)
    return dict(lang="hi", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"नमस्ते, मैं {s.pick(COMPANIES)} से {s.pick(NAMES_HI)} बोल रही हूँ। हमें आपकी "
                                            f"प्रोफ़ाइल जॉब पोर्टल पर मिली।")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(b),
                                    f"घर बैठे होटल रेटिंग का काम, रोज़ ₹{inr(s.amount(2000, 5000, 500))} तक कमाई। कई लोग जुड़ चुके हैं।")),
        (s.r.randrange(20, 48), msg("whatsapp", s.phone_fmt(c),
                                    f"आपका चयन हो गया है। कर्मचारी आईडी चालू करने के लिए ₹{inr(s.amount(999, 2999, 500))} "
                                    f"प्रशिक्षण किट फीस लगेगी।")),
    ])


def dsb_relative_hi(s: Slots):
    a, b, c, d = _nums(s, 4)
    return dict(lang="hi", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), "बेटा, मैं चाचा बोल रहा हूँ। पुराना फ़ोन खराब हो गया, यह मेरा नया नंबर है।")),
        (s.r.randrange(20, 30), call("+91" + b, s.r.randrange(60, 300))),
        (s.r.randrange(2, 20), msg("whatsapp", s.phone_fmt(c),
                                   "मैं सिटी अस्पताल से डॉक्टर का सहायक बोल रहा हूँ। आपके चाचा यहाँ भर्ती हैं, उन्होंने आपको "
                                   "संदेश भेजने को कहा।")),
        (s.r.randrange(1, 4), msg("whatsapp", s.phone_fmt(d),
                                  f"ऑपरेशन से पहले ₹{inr(s.amount(15000, 40000, 5000))} एडवांस जमा कराना ज़रूरी है, वे बाद "
                                  f"में लौटा देंगे।")),
    ])


def dsb_crypto_hi(s: Slots):
    a, b, c, d = _nums(s, 4)
    return dict(lang="hi", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), "माफ़ कीजिए, गलत नंबर लग गया। वैसे आपसे बात करके अच्छा लगा।")),
        (s.r.randrange(24, 48), msg("whatsapp", s.phone_fmt(b),
                                    "मेरे भैया क्रिप्टो सिखाते हैं, उनके छात्र हर हफ्ते अच्छी कमाई करते हैं।")),
        (s.r.randrange(24, 48), msg("whatsapp", s.phone_fmt(c),
                                    "आपका ट्रेडिंग खाता तैयार है, पहली राशि के बाद निकासी शुरू होगी।")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(d),
                                    f"निकासी के लिए ₹{inr(s.amount(5000, 15000, 100))} सत्यापन शुल्क आज ही भरना होगा।")),
    ])


def dsb_inv_hinglish(s: Slots):
    a, b, c = _nums(s, 3)
    return dict(lang="hinglish", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hello ji, aapka number {s.pick(CITIES)} investors group se mila. Main market "
                                            f"updates share karta hoon kabhi kabhi.")),
        (s.r.randrange(20, 40), msg("whatsapp", s.phone_fmt(b),
                                    f"Is mahine members ko {s.r.randrange(15, 35)}% munafa hua, expert tips guaranteed hai. "
                                    f"Aapko add karu?")),
        (s.r.randrange(24, 50), msg("whatsapp", s.phone_fmt(c),
                                    f"Premium batch mein sirf {s.r.randrange(2, 6)} seat bachi hai. Seat pakki karne ke liye "
                                    f"{inr(s.amount(2999, 9999, 1000) - 1)} membership fees bharni hogi.")),
    ])


def dsb_job_hinglish(s: Slots):
    a, b, c = _nums(s, 3)
    return dict(lang="hinglish", shape="distributed", harm=2, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), f"Hi, main {s.pick(COMPANIES)} HR se {s.pick(NAMES_EN)}. Aapki profile job "
                                            f"portal pe dekhi, ek remote opening hai.")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(b),
                                    f"Ghar baithe hotel rating ka kaam, roz {inr(s.amount(2000, 5000, 500))} tak kamai. Bahut log "
                                    f"jud chuke hain.")),
        (s.r.randrange(20, 48), msg("whatsapp", s.phone_fmt(c),
                                    f"Aapka selection ho gaya. Employee ID activate karne ke liye {inr(s.amount(999, 2999, 500))} "
                                    f"training kit fees deni hogi.")),
    ])


def dsb_relative_hinglish(s: Slots):
    a, b, c, d = _nums(s, 4)
    return dict(lang="hinglish", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), "Beta main Mama bol raha hoon, purana phone kharab ho gaya, ye mera naya number hai.")),
        (s.r.randrange(20, 30), call("+91" + b, s.r.randrange(60, 300))),
        (s.r.randrange(2, 20), msg("whatsapp", s.phone_fmt(c),
                                   "Main City Hospital se doctor ka assistant bol raha hoon, aapke mama yahan admit hain.")),
        (s.r.randrange(1, 4), msg("whatsapp", s.phone_fmt(d),
                                  f"Operation se pehle {inr(s.amount(15000, 40000, 5000))} advance ka intezaam karna padega, "
                                  f"baad mein lauta denge.")),
    ])


def dsb_task_hinglish(s: Slots):
    a, b, c, d = _nums(s, 4)
    return dict(lang="hinglish", shape="distributed", harm=3, fixtures=_fx(), steps=[
        (0, msg("whatsapp", s.phone_fmt(a), "Hello dear, aap kaise ho? Main aapke purane school group se hoon.")),
        (s.r.randrange(24, 48), msg("whatsapp", s.phone_fmt(b),
                                    "Ek part time kaam hai, YouTube videos like karo aur commission kamao.")),
        (s.r.randrange(20, 40), msg("whatsapp", s.phone_fmt(c),
                                    "Aapka task account ready hai, aaj hi start karo, slots limited hain.")),
        (s.r.randrange(20, 30), msg("whatsapp", s.phone_fmt(d),
                                    f"Bonus nikalne ke liye {inr(s.amount(1500, 5000, 500))} ka upgrade amount bharna padega.")),
    ])


DSB_SCAM_TEMPLATES = [dsb_inv_en, dsb_inv_hi, dsb_inv_hinglish, dsb_job_en, dsb_job_hi, dsb_job_hinglish,
                      dsb_relative_en, dsb_relative_hi, dsb_relative_hinglish, dsb_crypto_en, dsb_crypto_hi,
                      dsb_task_hinglish]


def dsb_b_society(s: Slots, lang: str):
    admin, host, treas = "Mrs Kapoor (Society)", "Mr Iyer (Society)", "Treasurer Green Park"
    if lang == "hi":
        lines = ["दिवाली की तैयारी के लिए आपको सोसाइटी ग्रुप में जोड़ रही हूँ।", "शनिवार शाम 7 बजे दिवाली पार्टी, सब आइए।",
                 f"अक्टूबर का रखरखाव शुल्क ₹{inr(s.amount(1500, 4000, 500))} देय है, सोसाइटी खाते में जमा करें।"]
    elif lang == "hinglish":
        lines = ["Diwali planning ke liye aapko society group mein add kar rahi hoon.", "Saturday 7 baje Diwali party hai, sab aana.",
                 f"October ka maintenance {inr(s.amount(1500, 4000, 500))} due hai, society account mein bhej dena."]
    else:
        lines = ["Hi, adding you to the Green Park residents group for Diwali planning.",
                 "Diwali party on Saturday at 7 pm, everyone welcome!",
                 f"Maintenance fee of Rs {inr(s.amount(1500, 4000, 500))} for October is due, please pay to the society account."]
    evs = [(0, 11.0, msg("whatsapp", admin, lines[0], contact=True)), (2, 19.0, msg("whatsapp", host, lines[1], contact=True)),
           (4, 10.0, msg("whatsapp", treas, lines[2], contact=True))]
    return evs + filler(s, lang, 3), _fx()


def dsb_b_recruiter(s: Slots, lang: str):
    co = s.pick(["Infosys", "Wipro", "TCS", "Accenture"])
    if lang == "hi":
        lines = [f"आपकी प्रोफ़ाइल {co} में डेवलपर पद के लिए चयनित हुई है।", "आपका इंटरव्यू मंगलवार सुबह 11 बजे तय है।",
                 "बधाई! ऑफ़र लेटर जारी कर दिया गया है, पोर्टल पर दस्तावेज़ पूरे करें।"]
    else:
        lines = [f"Your profile was shortlisted by {co} for a developer role.",
                 "Your interview is scheduled for Tuesday 11 AM; the HR team will share the meeting link.",
                 "Congratulations! Your offer letter is released. Complete onboarding documents on the portal."]
        if lang == "hinglish":
            lines = [x + " All the best ji!" for x in lines]
    evs = [(0, 10.0, msg("sms", "VM-NAUKRI", lines[0])), (2, 12.0, msg("sms", "JD-HRMAIL", lines[1])),
           (5, 16.0, msg("sms", "JD-HRMAIL", lines[2]))]
    return evs + filler(s, lang, 3), _fx()


def dsb_b_family(s: Slots, lang: str):
    amt = s.pick(["2,000", "3,000", "5,000"])
    if lang == "hi":
        lines = ["बेटा, आज पापा के फ़ोन से बात कर रही हूँ, मेरा फ़ोन ठीक नहीं चल रहा।", "अस्पताल में चेकअप था, सब ठीक है।",
                 f"दवाई के लिए ₹{amt} भेज दो, घर आकर दे दूँगी।"]
    elif lang == "hinglish":
        lines = ["Beta aaj Papa ke phone se baat kar rahi hoon, mera phone kharab hai.", "Hospital mein checkup tha, sab theek hai.",
                 f"Dawai ke liye ₹{amt} bhej do, ghar aake de dungi."]
    else:
        lines = ["Beta, my phone is acting up, using Papa's phone today.", "Had a checkup at the hospital, all fine.",
                 f"Can you send ₹{amt} for the medicines? Will return when you visit."]
    evs = [(0, 9.0, msg("whatsapp", "Mummy", lines[0], contact=True)), (1, 18.0, msg("whatsapp", "Papa", lines[1], contact=True)),
           (2, 20.0, msg("whatsapp", "Papa", lines[2], contact=True)),
           (3, 20.0, call(s.phone_fmt(), s.r.randrange(60, 600), contact=True))]
    return evs + filler(s, lang, 3), _fx()


def dsb_b_local_service(s: Slots, lang: str):
    p = s.phone_fmt()
    if lang == "hi":
        lines = ["नमस्ते, मुझे आपका नंबर शर्मा जी से मिला। मैं कक्षा 8 के लिए गणित पढ़ाता हूँ।", "कक्षाएँ सोमवार शाम 5 बजे से।",
                 f"मासिक फीस ₹{inr(s.amount(1500, 3000, 500))} है, पहली कक्षा के बाद दे सकते हैं।"]
    elif lang == "hinglish":
        lines = ["Hello ji, aapka number reception se mila, Fit Zone gym ke liye.", "Is hafte joining pe 20% off hai.",
                 f"Monthly charge {inr(s.amount(1500, 3000, 500))} hai, cash ya UPI dono chalega."]
    else:
        lines = ["Hi, I got your number from Mrs Sharma. I teach maths for class 8, she said your son needs help.",
                 "Classes start Monday at 5 pm.", f"The monthly charge is Rs {inr(s.amount(1500, 3000, 500))}, you can pay after the first class."]
    evs = [(0, 10.0, msg("whatsapp", p, lines[0])), (1, 17.0, msg("whatsapp", p, lines[1])),
           (3, 12.0, msg("whatsapp", p, lines[2]))]
    return evs + filler(s, lang, 3), _fx()


DSB_BENIGN_BUILDERS = [dsb_b_society, dsb_b_recruiter, dsb_b_family, dsb_b_local_service]


def build_dsb_benign(sid: str, k: int, rng: random.Random, start: datetime) -> dict:
    lang = BENIGN_LANGS[(k // len(DSB_BENIGN_BUILDERS)) % len(BENIGN_LANGS)]
    fn = DSB_BENIGN_BUILDERS[k % len(DSB_BENIGN_BUILDERS)]
    evs, fixtures = fn(Slots(rng), lang)
    sc = _assemble_benign(sid, DSB_CATEGORY, lang, sorted(evs, key=lambda x: (x[0], x[1])), fixtures, rng, start)
    sc["template"] = fn.__name__
    return sc


# ---------------------------------------------------------------- assembly
def _ts(t: datetime) -> str:
    return t.isoformat(timespec="seconds")


def _event(sid: str, i: int, ev: dict, ts: datetime) -> dict:
    lang = ev.pop("_lang", None)
    out = {"device_event_id": f"{sid}-e{i:02d}", "source": ev["source"], "ts": _ts(ts),
           "sender": ev.get("sender"), "text": ev.get("text"), "call": ev.get("call"),
           "triage_score": device_triage(ev), "lang": lang}
    if ev.get("is_contact") is not None:
        out["is_contact"] = ev["is_contact"]
    return out


_DEV = re.compile(r"[ऀ-ॿ]")


def _event_lang(ev: dict, scen_lang: str) -> str | None:
    t = ev.get("text")
    if not t:
        return None
    if _DEV.search(t):
        return "hi"
    return "hi-Latn" if scen_lang == "hinglish" else "en"


def build_scam(sid: str, category: str, k: int, rng: random.Random, start: datetime, tpl=None) -> dict:
    tpl = tpl or SCAM_TEMPLATES[category][k % len(SCAM_TEMPLATES[category])]
    spec = tpl(Slots(rng))
    t = start
    events = []
    for i, (gap, ev) in enumerate(spec["steps"]):
        t = t + timedelta(hours=gap) + (timedelta(minutes=rng.randrange(1, 9)) if i else timedelta())
        ev = dict(ev)
        ev["_lang"] = _event_lang(ev, spec["lang"])
        events.append(_event(sid, i, ev, t))
    harm = spec["harm"]
    return {"id": sid, "label": "scam", "category": category, "lang": spec["lang"], "shape": spec["shape"],
            "template": tpl.__name__, "harm_index": harm, "events": events,
            "expect": {"first_alert_at_index": harm, "min_tier": "high"},
            "fixtures": spec["fixtures"], "split": None, "source": "synthetic"}


def build_benign(sid: str, category: str, k: int, rng: random.Random, start: datetime) -> dict:
    lang = BENIGN_LANGS[k % len(BENIGN_LANGS)]
    evs, fixtures = BENIGN_BUILDERS[category](Slots(rng), lang)
    evs = sorted(evs, key=lambda x: (x[0], x[1]))
    sc = _assemble_benign(sid, category, lang, evs, fixtures, rng, start)
    sc["template"] = BENIGN_BUILDERS[category].__name__
    return sc


def _assemble_benign(sid, category, lang, evs, fixtures, rng, start) -> dict:
    events, last = [], None
    for i, (day, hour, ev) in enumerate(evs):
        t = start + timedelta(days=day, hours=hour)
        if last is not None and t <= last:
            t = last + timedelta(minutes=rng.randrange(2, 30))
        last = t
        ev = dict(ev)
        ev["_lang"] = _event_lang(ev, lang)
        events.append(_event(sid, i, ev, t))
    return {"id": sid, "label": "benign", "category": category, "lang": lang, "shape": "user_week",
            "template": None, "harm_index": None, "events": events,
            "expect": {"first_alert_at_index": None, "min_tier": None},
            "fixtures": fixtures, "split": None, "source": "synthetic"}


def assign_splits(scenarios: list[dict], seed: int) -> None:
    """Stratified by (label, category): within each group (sorted by id) a seeded shuffle puts
    round(30%) (at least 1 when the group has >= 3) in holdout."""
    rng, xrng, drng = random.Random(seed + 1), random.Random(seed + 2), random.Random(seed + 12)
    groups: dict[tuple, list[dict]] = {}
    for sc in scenarios:
        groups.setdefault((sc["label"], sc["category"]), []).append(sc)
    for key in sorted(groups):
        g = sorted(groups[key], key=lambda x: x["id"])
        (drng if key[1] == DSB_CATEGORY else xrng if key[1] in EXTRA_SCAM_COUNTS else rng).shuffle(g)
        n = round(len(g) * HOLDOUT_FRACTION)
        if n == 0 and len(g) >= 3:
            n = 1
        for i, sc in enumerate(g):
            sc["split"] = "holdout" if i < n else "tune"


def generate(seed: int = SEED) -> list[dict]:
    rng = random.Random(seed)
    out: list[dict] = []
    day = 0
    for category, n in SCAM_COUNTS.items():
        for k in range(n):
            sid = f"scam-{category.replace('_', '-')}-{k + 1:02d}"
            start = BASE + timedelta(days=day, hours=rng.randrange(8, 20), minutes=rng.randrange(0, 60))
            out.append(build_scam(sid, category, k, rng, start))
            day += 1
    for category, n in BENIGN_COUNTS.items():
        for k in range(n):
            sid = f"benign-{category.replace('_', '-')}-{k + 1:02d}"
            start = BASE + timedelta(days=day)
            out.append(build_benign(sid, category, k, rng, start))
            day += 1
    xrng = random.Random(seed + 7)
    for category, n in EXTRA_SCAM_COUNTS.items():
        for k in range(n):
            sid = f"scam-{category.replace('_', '-')}-{k + 1:02d}"
            start = BASE + timedelta(days=day, hours=xrng.randrange(8, 20), minutes=xrng.randrange(0, 60))
            out.append(build_scam(sid, category, k, xrng, start))
            day += 1
    drng = random.Random(seed + 11)  # distributed slow burn: own stream, appended last
    slug = DSB_CATEGORY.replace("_", "-")
    for k, tpl in enumerate(DSB_SCAM_TEMPLATES[:DSB_SCAMS]):
        start = BASE + timedelta(days=day, hours=drng.randrange(8, 20), minutes=drng.randrange(0, 60))
        out.append(build_scam(f"scam-{slug}-{k + 1:02d}", DSB_CATEGORY, k, drng, start, tpl=tpl))
        day += 1
    for k in range(DSB_BENIGN):
        out.append(build_dsb_benign(f"benign-{slug}-{k + 1:02d}", k, drng, BASE + timedelta(days=day)))
        day += 1
    assign_splits(out, seed)
    return out


def dump(sc: dict) -> str:
    return json.dumps(sc, ensure_ascii=False, indent=2) + "\n"


def write(out_dir: Path, seed: int = SEED) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.json"):
        old.unlink()
    paths = []
    for sc in generate(seed):
        p = out_dir / f"{sc['id']}.json"
        p.write_text(dump(sc), encoding="utf-8")
        paths.append(p)
    return paths


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--seed", type=int, default=SEED)
    a = ap.parse_args()
    paths = write(a.out, a.seed)
    print(f"wrote {len(paths)} scenarios to {a.out}")


if __name__ == "__main__":
    main()
