import pytest

from app.pipeline.redact import redact


def test_otp_english():
    out = redact("Your OTP is 482913")
    assert "[OTP]" in out and "482913" not in out


def test_otp_hindi():
    out = redact("आपका ओटीपी 482913 है")
    assert "[OTP]" in out and "482913" not in out


def test_otp_devanagari_digits():
    out = redact("आपका ओटीपी ४८२९१३ है")
    assert "[OTP]" in out and "४८२९१३" not in out


def test_otp_number_before_keyword():
    out = redact("482913 is your verification code")
    assert "[OTP]" in out and "482913" not in out


def test_card_unspaced_and_spaced():
    assert "[CARD]" in redact("card 4111111111111111 used")
    assert "4111" not in redact("card 4111111111111111 used")
    out = redact("card 4111 1111 1111 1111 used")
    assert "[CARD]" in out and "4111" not in out
    assert "[CARD]" in redact("card 4111-1111-1111-1111")


def test_pan():
    out = redact("PAN ABCDE1234F please")
    assert "[PAN]" in out and "ABCDE1234F" not in out


def test_aadhaar():
    out = redact("Aadhaar 234567890123")
    assert "[AADHAAR]" in out and "234567890123" not in out
    out = redact("Aadhaar 2345 6789 0123")
    assert "[AADHAAR]" in out and "2345" not in out


def test_account_number():
    assert "[ACCT]" in redact("a/c 123456789012345")
    out = redact("account 123456 debited")
    assert "[ACCT]" in out and "123456" not in out
    assert "[ACCT]" in redact("खाता 987654321 में")
    assert "[ACCT]" in redact("transfer to 50100123456789")


def test_evidence_kept():
    s = "pay ₹4,999 to ramesh@okhdfcbank"
    assert redact(s) == s


@pytest.mark.parametrize("s", [
    "call 9876543210 now",
    "call +91 98765 43210 now",
    "call +919876543210 now",
    "call 0091 9876543210 now",
    "call 09876543210 now",
    "call 98765-43210 now",
    "call +91-98765-43210 now",
    "code: call 98765 43210",
])
def test_phone_kept(s):
    assert redact(s) == s


@pytest.mark.parametrize("s", [
    "pay Rs 1,00,000 now",
    "send ₹4999 code",
    "Rs. 123456789 due",
    "refund of ₹50,000 at https://x.in/a?b=1",
])
def test_amount_kept(s):
    assert redact(s) == s


@pytest.mark.parametrize("s", [
    "Your OTP is 482913",
    "आपका ओटीपी 482913 है",
    "card 4111 1111 1111 1111 PAN ABCDE1234F a/c 123456789012",
    "pay ₹4,999 to ramesh@okhdfcbank call +91 98765 43210",
])
def test_idempotent(s):
    assert redact(redact(s)) == redact(s)
