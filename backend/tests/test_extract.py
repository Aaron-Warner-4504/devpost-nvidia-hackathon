import pytest

from app.pipeline.extract import Entity, extract_entities
from app.schemas import CallMeta


def types(ents, t):
    return [e.canonical for e in ents if e.type == t]


@pytest.mark.parametrize(
    "text",
    ["+91 98765-43210", "0091 9876543210", "09876543210", "call 9876543210 now",
     "+91-98765 43210", "९८७६५४३२१०", "+919876543210."],
)
def test_phone_forms(text):
    assert extract_entities(text, None, None) == [Entity("phone", "+919876543210")]


def test_empty_inputs():
    assert extract_entities(None, None, None) == []
    assert extract_entities("", "", None) == []


def test_no_phantom_phones():
    ents = extract_entities(
        "Pay Rs 4,999 OTP [OTP] acct [ACCT] card 4111 1111 1111 1111 id 123456789012 tel 0123456789",
        None, None)
    assert types(ents, "phone") == []
    assert types(ents, "amount") == ["INR:4999"]


def test_international_phone_kept():
    assert types(extract_entities("call +1 650-253-0000", None, None), "phone") == ["+16502530000"]


def test_call_number_and_sender_merge():
    call = CallMeta(number="+91 98765 43210", duration_s=5, is_contact=False, direction="in")
    ents = extract_entities("call 09876543210", "9876543210", call)
    assert types(ents, "phone") == ["+919876543210"]
    call2 = CallMeta(number="9123456780", duration_s=5, is_contact=False, direction="in")
    assert types(extract_entities(None, None, call2), "phone") == ["+919123456780"]


def test_devanagari_with_shortener():
    ents = extract_entities("अपना KYC अपडेट करें https://Bit.ly/AbC", None, None)
    assert Entity("domain", "bit.ly") in ents
    assert Entity("claim", "shortener_link") in ents


def test_domains():
    ents = extract_entities("visit www.HDFCBank-KYC.in/login?x=1 or http://a.example.com:8080/x", None, None)
    assert types(ents, "domain") == ["hdfcbank-kyc.in", "a.example.com"]
    assert types(ents, "claim") == []
    assert types(extract_entities("e.g. see photo.jpg", None, None), "domain") == []


def test_upi_not_domain():
    ents = extract_entities("pay ramesh@okhdfcbank or 9876543210@ybl", None, None)
    assert types(ents, "upi_id") == ["ramesh@okhdfcbank", "9876543210@ybl"]
    assert types(ents, "domain") == []
    ents = extract_entities("mail Bob@Gmail.com", None, None)
    assert types(ents, "upi_id") == [] and types(ents, "domain") == []


def test_amounts():
    ents = extract_entities("₹1,00,000 and Rs. 4999 and INR 250.50 and 500 रुपये and Rs 2 lakh", None, None)
    assert sorted(types(ents, "amount")) == sorted(["INR:100000", "INR:4999", "INR:250.50", "INR:500", "INR:200000"])


def test_hindi_parcel():
    ents = extract_entities("आपका पार्सल कस्टम में रुका है", None, None)
    assert Entity("org", "customs") in ents
    assert Entity("claim", "parcel_held") in ents


def test_orgs_word_boundary_and_hindi():
    ents = extract_entities("SBI and India Post, Phonepe; sbix basis; पेटीएम", None, None)
    assert sorted(types(ents, "org")) == ["india_post", "paytm", "phonepe", "sbi"]


def test_sender_header_org():
    assert types(extract_entities("hello", "VM-HDFCBK", None), "org") == ["hdfc"]
    assert types(extract_entities("hello", "AX-SBIINB", None), "org") == ["sbi"]
    assert types(extract_entities("hello", "JD-ICICIB", None), "org") == ["icici"]


def test_claims_and_dedupe():
    ents = extract_entities(
        "KYC expired, account blocked. digital arrest! डिजिटल अरेस्ट refund part time job lucky draw", None, None)
    assert types(ents, "claim") == ["kyc_expired", "account_blocked", "digital_arrest", "refund",
                                    "job_offer", "lottery"]
