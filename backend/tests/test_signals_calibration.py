"""Task 12 rules calibration, driven by TUNE-split eval failures (see eval/report.md). Each test pins one
change and the benign counter-examples it must not break. Existing safety tests are unchanged."""
from datetime import datetime, timedelta, timezone

import pytest

from app.pipeline.extract import Entity
from app.pipeline.memory import HistoryItem
from app.pipeline.signals import HistoryCtx
from app.pipeline.tools import lookalike_of
from tests.test_signals import NOW, ev, names, run


def pay(text, sender="+919811122233", source="whatsapp"):
    return "payment_request" in names(run(ev(text, sender=sender, source=source))[0])


# ---------------------------------------------------------------- 1. BUG: Hindi danda after a payment ask
@pytest.mark.parametrize("text", [
    "₹500 जमा करें।",
    "IPO में पक्का आवंटन चाहिए तो ₹1,25,000 कंपनी के खाते 920191147203 में जमा करें। आज आखिरी दिन है।",
    "जांच पूरी होने तक ₹2,53,000 इस सरकारी खाते 392060444246 में जमा करें।",
    "पैसे जमा कीजिए॥",
])
def test_hindi_deposit_ask_before_danda(text):
    assert pay(text)


@pytest.mark.parametrize("text", [
    "आपके खाते XX1234 में ₹500 जमा कर दिए गए हैं।",
    "वेतन: आपके खाते XX1234 में INFOSYS LTD से ₹45,000 जमा किए गए हैं। - एचडीएफसी बैंक",
])
def test_hindi_credit_notifications_still_not_payment(text):
    assert not pay(text, sender="VM-HDFCBK", source="sms")


# ---------------------------------------------------------------- 2. pay / send to a UPI id
@pytest.mark.parametrize("text", [
    "Minimum 76,000 invest karna hai, cryptofund868@ybl pe bhej do, main aapka account bana deta hoon.",
    "VIP टास्क शुरू करने के लिए ₹16,500 इस UPI hrtasks569@okaxis पर भेजें।",
    "Send the amount to taskmerchant42@paytm and get 30% profit back",
    "Pay Rs 2000 to refund.desk@ybl today",
])
def test_payment_to_upi_id(text):
    assert pay(text)


@pytest.mark.parametrize("text,sender,source", [
    ("You paid ₹250 to Sharma Kirana Store. UPI transaction ID 123456789012.", "PhonePe", "notification"),
    ("Payment of ₹500 to shop42@ybl was successful.", "PhonePe", "notification"),
    ("Rs.500.00 debited from A/c XX1234 to VPA swiggy@icici. UPI Ref 1234. -HDFC", "VM-HDFCBK", "sms"),
    ("₹300 received from rahul.k@okaxis via UPI.", "Google Pay", "notification"),
    ("Please send the wedding photos to rahul@gmail.com", "Didi", "whatsapp"),
])
def test_upi_notifications_and_emails_are_not_asks(text, sender, source):
    assert not pay(text, sender=sender, source=source)


# ---------------------------------------------------------------- 3. fee asks
@pytest.mark.parametrize("text", [
    "Congratulations! You are selected for Amazon work from home job. Pay registration fee of Rs 999 today",
    "इंडिया पोस्ट: ₹25 शुल्क देकर 48 घंटे में पता अपडेट करें",
    "पार्सल छुड़ाने के लिए कस्टम शुल्क का भुगतान करें",
    "Pay a small processing fee to release your parcel",
])
def test_fee_asks(text):
    assert pay(text, sender="+919811122233", source="sms")


@pytest.mark.parametrize("text", [
    "Dear consumer, your electricity bill of Rs 1,200 is due on 10-04-26. Pay via the BESCOM Mithra app to "
    "avoid late fee.",
    "No convenience fee on UPI payments this weekend!",
    "Out for delivery: your Myntra order. Pay on delivery amount Rs 1,299. Details: https://myntra.com/t/1",
    "Pay cash on delivery amount Rs 499 to the delivery agent",
])
def test_fee_mentions_are_not_asks(text):
    assert not pay(text, sender="AD-BESCOM", source="sms")


# ---------------------------------------------------------------- 4. Hinglish "PIN daal do"
@pytest.mark.parametrize("text", [
    "Request accept karke apna UPI PIN daal do bas, paisa wapas aa jayega",
    "UPI pin dalo aur request approve karo",
])
def test_hinglish_pin_entry_is_credential_ask(text):
    assert "credential_or_otp_ask" in names(run(ev(text, sender="+919811122233", source="whatsapp"))[0])


def test_hinglish_pin_warning_still_not_an_ask():
    sigs, tier = run(ev("Apna UPI PIN kisi ko na batayein. -SBI", sender="AD-SBIINB"))
    assert "credential_or_otp_ask" not in names(sigs)


# ---------------------------------------------------------------- 5. channel hop needs more than a bank name
def _h(source, hours, text="x", i=0):
    return HistoryItem(f"h{source}{i}", NOW - timedelta(hours=hours), source, text)


def test_bank_org_seen_on_another_channel_is_not_a_hop():
    org = Entity("org", "icici")
    ctx = HistoryCtx(now=NOW, events_by_entity={org: [_h("sms", 30)]}, sender_seen_before=False)
    e = ev("Your ICICI credit card e-statement is attached. Total amount due Rs 32,531.",
           source="email", sender="statements@icicibank.com")
    assert "channel_hop" not in names(run(e, ctx)[0])


def test_authority_org_and_claims_still_hop():
    org = Entity("org", "police")
    ctx = HistoryCtx(now=NOW, events_by_entity={org: [_h("sms", 30)]}, sender_seen_before=True)
    e = ev("Delhi police here, call back now", source="whatsapp", sender="+919811122233")
    assert "channel_hop" in names(run(e, ctx)[0])
    claim = Entity("claim", "parcel_held")
    ctx = HistoryCtx(now=NOW, events_by_entity={claim: [_h("email", 5)]}, sender_seen_before=True)
    assert "channel_hop" in names(run(ev("parcel is held at customs", sender="x"), ctx)[0])


# ---------------------------------------------------------------- 6. saved-contact display names
@pytest.mark.parametrize("sender", ["Mummy", "Rohan (cousin)", "Priya Office", "दीदी"])
def test_whatsapp_display_name_is_a_saved_contact(sender):
    sigs, _ = run(ev("Can you send ₹1,200 for the electrician? Will return tomorrow", source="whatsapp",
                     sender=sender))
    assert "unknown_sender" not in names(sigs)


@pytest.mark.parametrize("sender,source", [("+91 98111 22233", "whatsapp"), ("9811122233", "whatsapp"),
                                           ("+919811122233", "sms"), ("AD-SBIBKG", "sms"),
                                           ("hacker@protonmail.com", "email")])
def test_numbers_headers_and_emails_still_unknown_when_unseen(sender, source):
    assert "unknown_sender" in names(run(ev("hello", source=source, sender=sender))[0])


# ---------------------------------------------------------------- 7. Amazon's own short domains
@pytest.mark.parametrize("d", ["amzn.in", "amzn.to", "amzn.eu"])
def test_amazon_short_domains_are_not_lookalikes(d):
    assert lookalike_of(d) is None


def test_amazon_lookalikes_still_detected():
    assert lookalike_of("amazon-refund.in") == "amazon.in"
    assert lookalike_of("amzon.in") == "amazon.in"
