"""Static HTML analysis: fields, title, text, brand mismatch, sanitising."""

import pytest

from detect import analyze_html, brand_mismatch, clean_text, decode_body


def test_login_page_fields():
    f = analyze_html("<title> HDFC  Login </title><input type=PASSWORD><input name=otp>")
    assert f.has_password and f.has_sensitive and f.title == "HDFC Login"


@pytest.mark.parametrize("field", [
    "<input name='otp'>", "<input id='txtOTP'>", "<input placeholder='Enter OTP'>",
    "<input autocomplete='one-time-code'>", "<input name='upi_id'>", "<input placeholder='Your VPA'>",
    "<input name='cardNumber'>", "<input name='card_no'>", "<input autocomplete='cc-number'>",
    "<input autocomplete='cc-csc'>", "<input name='cvv'>", "<input name='CVV2'>", "<input name='mpin'>",
    "<input name='atm_pin'>", "<input placeholder='UPI PIN'>", "<input name='aadhaar'>",
    "<input placeholder='Aadhar number'>", "<input aria-label='Debit card number'>",
    "<label for=x>One Time Password</label><input id=x>",
    "<input placeholder='अपना ओटीपी दर्ज करें'>", "<input placeholder='यूपीआई आईडी'>",
    "<input placeholder='कार्ड नंबर'>", "<input placeholder='सीवीवी'>", "<input placeholder='आधार संख्या'>",
    "<input placeholder='एटीएम पिन'>", "<textarea name='otp'></textarea>",
])
def test_sensitive_fields(field):
    assert analyze_html(f"<form>{field}</form>").has_sensitive is True


@pytest.mark.parametrize("field", [
    "<input name='pincode'>", "<input placeholder='PIN code'>", "<input name='pin_code'>",
    "<input placeholder='पिन कोड'>", "<input name='email'>", "<input type=hidden name=otp>",
    "<input type=submit value='Get OTP'>", "<input name='opinion'>", "<input name='spinner'>",
    "<input name='shipping'>", "<input name='cardholder_note'>",
])
def test_non_sensitive_fields(field):
    assert analyze_html(f"<form>{field}</form>").has_sensitive is False


def test_text_excerpt_is_capped_and_cleaned():
    html = ("<title>t\x00‮</title><script>var secret='x'</script><style>p{}</style><body>"
            + "word​\x07 " * 1000 + "</body>")
    f = analyze_html(html)
    assert len(f.text) <= 1500 and "secret" not in f.text and "\x07" not in f.text
    assert "​" not in f.text and "‮" not in f.title


def test_clean_text():
    assert clean_text("a\x00b\r\n\tc ⁦d⁩", 100) == "a b c d"
    assert clean_text("x" * 50, 10) == "x" * 10
    assert clean_text(None, 10) == ""


def test_meta_refresh_target():
    assert analyze_html("<meta http-equiv='Refresh' content='0; URL=/next'>").meta_refresh == "/next"
    assert analyze_html("<meta http-equiv=refresh content=\"2;url='http://a.com/x'\">").meta_refresh == "http://a.com/x"
    assert analyze_html("<meta http-equiv=refresh content='300; url=/later'>").meta_refresh is None
    assert analyze_html("<meta http-equiv=refresh content='5'>").meta_refresh is None


def test_malicious_html_does_not_crash():
    for h in ["<" * 100000, "<input " + "a=b " * 50000 + ">", "\x00" * 1000, "<svg><script>",
              "<input type=password>" * 20000, ""]:
        analyze_html(h)


def test_decode_body():
    assert decode_body("हिंदी".encode(), "text/html; charset=utf-8") == "हिंदी"
    assert decode_body("é".encode("latin-1"), "text/html; charset=iso-8859-1") == "é"
    assert decode_body(b"\xff\xfe", "text/html; charset=bogus-charset") is not None


@pytest.mark.parametrize("title,text,host,cred,want", [
    ("HDFC Bank NetBanking", "", "hdfc-kyc-update.xyz", False, "HDFC Bank"),
    ("HDFC Bank NetBanking", "", "netbanking.hdfcbank.com", False, None),
    ("Login", "State Bank of India login", "sbi-rewards.in", True, "SBI"),
    ("News", "SBI shares rose today", "moneycontrol.com", False, None),  # text-only, no credential form
    ("Track parcel - India Post", "", "indiapost.gov.in", False, None),
    ("Track parcel - India Post", "", "indiapost-track.top", False, "India Post"),
    ("Amazon Sign-In", "", "www.amazon.in", False, None),
    ("Amazon Sign-In", "", "amazon.in.verify-account.co", False, "Amazon"),
    ("Hello", "nothing", "example.com", True, None),
])
def test_brand_mismatch(title, text, host, cred, want):
    assert brand_mismatch(title, text, host, cred) == want
