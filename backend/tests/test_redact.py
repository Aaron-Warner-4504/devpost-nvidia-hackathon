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


@pytest.mark.parametrize("s", [
    "Aadhaar 916234567890",
    "my aadhaar is 916 234 567 890",
    "आधार 9162-3456-7890",
    "UID: 916234567890",
    "id 916234567890 given",
    "id 9162 3456 7890 given",
])
def test_aadhaar_starting_91_masked(s):
    out = redact(s)
    assert "[AADHAAR]" in out and "9162" not in out and "916" not in out


@pytest.mark.parametrize("s", [
    "ping 91 98765 43210",
    "ping 91-9876543210",
    "call 919876543210 now",
    "संपर्क 919876543210",
    "WhatsApp: 919876543210",
])
def test_phone12_kept(s):
    assert redact(s) == s


def test_currency_anchored():
    assert "[ACCT]" in redact("ours 482913012345 ok") or "[AADHAAR]" in redact("ours 482913012345 ok")
    assert "482913012345" not in redact("ours 482913012345 ok")
    assert "482913012345" not in redact("Fnrs 482913012345")
    assert redact("Rs 1,00,000") == "Rs 1,00,000"
    assert redact("₹4,999") == "₹4,999"
    assert redact("Rs. 123456789 due") == "Rs. 123456789 due"


def test_pan_lowercase():
    out = redact("pan abcde1234f ok")
    assert "[PAN]" in out and "abcde1234f" not in out


@pytest.mark.parametrize("s", [
    "OTP:\n482913",
    "Your one time password is 482913",
    "passcode 482913",
    "वन टाइम पासवर्ड 482913",
    "OTP 1234 5678",
    "OTP 123-456",
    "482913 passcode",
])
def test_otp_variants(s):
    out = redact(s)
    assert "[OTP]" in out
    assert not any(ch.isdigit() for ch in out)
    assert redact(out) == out


def test_split_otp_single_token():
    assert redact("OTP 1234 5678") == "OTP [OTP]"
    assert redact("OTP 123-456") == "OTP [OTP]"


@pytest.mark.parametrize("s", [
    "contact 9162 3456 7890",
    "mobile 9162-3456-7890",
    "call 9162 3456 7890",
])
def test_grouped_aadhaar_not_exempted_by_phone_keyword(s):
    out = redact(s)
    assert "[AADHAAR]" in out and "9162" not in out


@pytest.mark.parametrize("s", ["recall 916234567890", "decontact 916234567890"])
def test_phone_keyword_word_boundary(s):
    out = redact(s)
    assert "[AADHAAR]" in out and "916234567890" not in out


def test_idempotent_aadhaar_with_phone():
    s = "aadhaar number 9162 3456 7890 call +91 9876543210"
    out = redact(s)
    assert "+91 9876543210" in out and "[AADHAAR]" in out
    assert redact(out) == out
