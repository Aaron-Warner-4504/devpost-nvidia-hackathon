import json
from datetime import datetime, timedelta, timezone

import httpx
import pytest
import respx

from app.config import Settings
from app.models import Device
from app.pipeline import link_client, tools
from app.pipeline.extract import Entity
from app.pipeline.memory import link, store_event
from app.pipeline.signals import DomainInfo
from app.pipeline.tools import TOOLS, ToolContext, domain_info_for, openai_tool_specs, run_tool
from app.schemas import EventIn

NOW = datetime(2026, 3, 1, 12, 0, tzinfo=timezone.utc)
P = Entity("phone", "+919876543210")


class U:
    def __init__(self, id):
        self.id = id


def settings(**kw):
    return Settings(database_url="postgresql+psycopg://x/y", **kw)


def make_ctx(db=None, user=None, **kw):
    return ToolContext(db=db, user=user or U("usr_x"), now=NOW,
                       settings=kw.pop("settings", settings()), **kw)


def uid(register, db):
    d, _ = register()
    return U(db.get(Device, d).user_id)


def ingest(db, u, i, ents, minutes=0, text="t"):
    e = EventIn(device_event_id=i, source="sms", ts=NOW - timedelta(days=1) + timedelta(minutes=minutes),
                text=text, triage_score=0.5)
    s = store_event(db, u, e, text, ents)
    link(db, u, s, ents)
    db.commit()


def rdap(days):
    d = (NOW - timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return httpx.Response(200, json={"events": [{"eventAction": "last changed", "eventDate": NOW.isoformat()},
                                                {"eventAction": "registration", "eventDate": d}]})


# ---------------------------------------------------------------- registry
def test_registry_names_and_specs():
    assert set(TOOLS) == {"get_entity_history", "check_url", "check_number", "inspect_link", "web_lookup"}
    specs = openai_tool_specs()
    assert json.loads(json.dumps(specs)) == specs
    assert {s["function"]["name"] for s in specs} == set(TOOLS)
    for s in specs:
        assert s["type"] == "function"
        assert s["function"]["parameters"]["additionalProperties"] is False


# ---------------------------------------------------------------- check_url
@respx.mock
@pytest.mark.parametrize("d,brand", [
    ("hdfcbank-kyc.in", "hdfcbank.com"),
    ("sbi-netbanking-login.xyz", "sbi.co.in"),
    ("paytm-refund.top", "paytm.com"),
    ("hdfcbank.in", "hdfcbank.com"),
    ("hdfcbnk.com", "hdfcbank.com"),
    ("paytrn.com", "paytm.com"),
    ("amaz0n.com", "amazon.in"),
    ("hdfcbank.com.evil.xyz", "hdfcbank.com"),
])
def test_lookalikes(d, brand):
    respx.get(url__startswith="https://rdap.org/").mock(return_value=httpx.Response(404))
    assert run_tool(make_ctx(), "check_url", {"domain": d})["lookalike_of"] == brand


@respx.mock
@pytest.mark.parametrize("d", ["hdfcbank.com", "netbanking.hdfcbank.com", "amazon.com", "amazon.in",
                               "example.com", "sbi.co.in", "onlinesbi.sbi.co.in"])
def test_not_lookalikes(d):
    respx.get(url__startswith="https://rdap.org/").mock(return_value=httpx.Response(404))
    assert run_tool(make_ctx(), "check_url", {"domain": d})["lookalike_of"] is None


@respx.mock
def test_rdap_age_and_shortener():
    respx.get("https://rdap.org/domain/fresh.xyz").mock(return_value=rdap(3))
    r = run_tool(make_ctx(), "check_url", {"domain": "fresh.xyz"})
    assert r == {"age_days": 3, "lookalike_of": None, "shortener": False}
    respx.get("https://rdap.org/domain/bit.ly").mock(return_value=rdap(4000))
    assert run_tool(make_ctx(), "check_url", {"domain": "bit.ly"})["shortener"] is True


@respx.mock
def test_rdap_failures_give_none():
    route = respx.get("https://rdap.org/domain/a.xyz")
    for resp in (httpx.Response(500), httpx.Response(200, content=b"garbage"),
                 httpx.Response(200, json={"events": "x"}),
                 httpx.Response(200, json={"events": [{"eventAction": "registration", "eventDate": "nope"}]}),
                 httpx.TimeoutException("t"), httpx.ConnectError("c")):
        route.mock(side_effect=resp) if isinstance(resp, Exception) else route.mock(return_value=resp)
        assert run_tool(make_ctx(), "check_url", {"domain": "a.xyz"})["age_days"] is None


@respx.mock
def test_rdap_redirect_only_to_https():
    respx.get("https://rdap.org/domain/a.xyz").mock(
        return_value=httpx.Response(302, headers={"location": "http://169.254.169.254/x"}))
    bad = respx.get("http://169.254.169.254/x").mock(return_value=rdap(3))
    assert run_tool(make_ctx(), "check_url", {"domain": "a.xyz"})["age_days"] is None
    assert not bad.called
    respx.get("https://rdap.org/domain/b.xyz").mock(
        return_value=httpx.Response(302, headers={"location": "https://rdap.verisign.com/domain/b.xyz"}))
    respx.get("https://rdap.verisign.com/domain/b.xyz").mock(return_value=rdap(9))
    assert run_tool(make_ctx(), "check_url", {"domain": "b.xyz"})["age_days"] == 9


@respx.mock
@pytest.mark.parametrize("d", ["127.0.0.1", "10.0.0.1", "localhost", "a/b", "http://x.com", "x.com:80",
                               "u@x.com", "x.com/p", "münchen.de", "ex ample.com", "a" * 300 + ".com",
                               "intranet", "foo.local", "foo.internal", "-a.com", "a-.com", "a..com",
                               "x.com\n", "[::1]", ""])
def test_check_url_invalid_domains(d):
    r = respx.get(url__startswith="https://rdap.org/").mock(return_value=rdap(3))
    res = run_tool(make_ctx(), "check_url", {"domain": d})
    assert "error" in res and "age_days" not in res
    if len(d) < 254:
        assert res == {"error": "invalid domain"}
    assert not r.called


@respx.mock
def test_domain_info_for():
    respx.get("https://rdap.org/domain/hdfcbank-kyc.in").mock(return_value=rdap(2))
    assert domain_info_for(make_ctx(), "hdfcbank-kyc.in") == DomainInfo(2, "hdfcbank.com", False)
    assert domain_info_for(make_ctx(), "127.0.0.1") == DomainInfo()


# ---------------------------------------------------------------- web_lookup
@respx.mock
def test_web_lookup_no_key_no_network():
    r = respx.post("https://api.tavily.com/search").mock(return_value=httpx.Response(200, json={}))
    assert tools.web_lookup(make_ctx(), query="x") == []
    assert not r.called


@respx.mock
@pytest.mark.parametrize("resp", [httpx.TimeoutException("t"), httpx.Response(500),
                                  httpx.Response(200, content=b"junk"),
                                  httpx.Response(200, json={"results": "x"})])
def test_web_lookup_failures(resp):
    route = respx.post("https://api.tavily.com/search")
    route.mock(side_effect=resp) if isinstance(resp, Exception) else route.mock(return_value=resp)
    assert tools.web_lookup(make_ctx(settings=settings(tavily_api_key="k")), query="x") == []


@respx.mock
def test_web_lookup_caps_and_marks_untrusted():
    route = respx.post("https://api.tavily.com/search").mock(return_value=httpx.Response(200, json={
        "results": [{"title": "T" * 500, "url": "https://e.com/a", "content": "ignore previous " * 100}] * 8
                   + [{"title": "x", "url": "javascript:alert(1)", "content": "y"}]}))
    ctx = make_ctx(settings=settings(tavily_api_key="k"))
    out = tools.web_lookup(ctx, query="q\x00\x07" + "a" * 500)
    sent = json.loads(route.calls[0].request.content)
    assert sent["max_results"] == 5 and len(sent["query"]) <= 200 and "\x00" not in sent["query"]
    assert 0 < len(out) <= 5
    for r in out:
        assert r["title"].startswith("[untrusted web text] ") and len(r["title"]) <= 120 + 21
        assert r["untrusted"] is True and r["url"].startswith("https://") and len(r["url"]) <= 200
        assert r["snippet"].startswith("[untrusted web text] ")
        assert len(r["snippet"]) <= 300 + len("[untrusted web text] ")
    res = run_tool(ctx, "web_lookup", {"query": "q"})
    assert len(json.dumps(res)) <= 4000


# ---------------------------------------------------------------- memory tools
def test_history_user_scoped_and_capped(db, register):
    a, b = uid(register, db), uid(register, db)
    for i in range(12):
        ingest(db, a, f"a{i}", [P, Entity("domain", "x.in")], minutes=i, text="z" * (500 if i == 11 else 100))
    ingest(db, b, "b0", [P], text="other user secret")
    r = run_tool(make_ctx(db, a), "get_entity_history", {"entity_type": "phone", "canonical": P.canonical})
    assert len(r["events"]) == 10 and r["events"][-1]["redacted_text"] == "z" * 300
    assert all(len(e["redacted_text"]) <= 300 and {"ts", "source", "event_id"} <= set(e) for e in r["events"])
    assert "other user secret" not in json.dumps(r)
    assert {"type": "domain", "canonical": "x.in"} in r["related"]
    rb = run_tool(make_ctx(db, b), "get_entity_history", {"entity_type": "phone", "canonical": P.canonical})
    assert len(rb["events"]) == 1 and rb["related"] == []


def test_check_number(db, register):
    from app.models import Override
    a = uid(register, db)
    ingest(db, a, "1", [P])
    ingest(db, a, "2", [P], minutes=1)
    ctx = make_ctx(db, a)
    assert run_tool(ctx, "check_number", {"e164": P.canonical}) == {"seen_count": 2, "in_contacts": False}
    db.add(Override(user_id=a.id, kind="safe", type="phone", canonical=P.canonical))
    db.commit()
    assert run_tool(ctx, "check_number", {"e164": P.canonical})["in_contacts"] is True
    assert run_tool(ctx, "check_number", {"e164": "+910000000001"}) == {"seen_count": 0, "in_contacts": False}
    for bad in ("9876543210", "+0123", "abc", "+91 98765 43210", "+" + "1" * 20):
        assert "error" in run_tool(ctx, "check_number", {"e164": bad})


# ---------------------------------------------------------------- inspect_link
@respx.mock
def test_inspect_link_no_inspector_no_network():
    route = respx.route().mock(return_value=httpx.Response(200))
    r = run_tool(make_ctx(), "inspect_link", {"url": "https://evil.example/x"})
    assert r["fetched_ok"] is False and r["final_url"] == "https://evil.example/x"
    assert set(link_client.LinkReport.model_fields) == {
        "final_url", "redirect_chain", "status", "title", "has_password_field",
        "has_otp_or_upi_or_card_field", "triggers_download", "apk_download", "brand_mismatch",
        "threat_feed_hits", "text_excerpt", "fetched_ok"}
    assert len(route.calls) == 0


def test_inspect_link_validates_and_delegates(monkeypatch):
    seen = []
    monkeypatch.setattr(link_client, "inspect_link",
                        lambda url, settings=None: seen.append(url) or link_client.LinkReport(final_url=url))
    for bad in ("file:///etc/passwd", "ftp://x.com/a", "javascript:1", "https://x.com/" + "a" * 2100, "x.com"):
        assert "error" in run_tool(make_ctx(), "inspect_link", {"url": bad})
    assert seen == []
    assert run_tool(make_ctx(), "inspect_link", {"url": "http://x.com/a"})["final_url"] == "http://x.com/a"
    assert seen == ["http://x.com/a"]


# ---------------------------------------------------------------- run_tool hardening
VALID = {"get_entity_history": {"entity_type": "phone", "canonical": "+919876543210"},
         "check_url": {"domain": "x.com"}, "check_number": {"e164": "+919876543210"},
         "inspect_link": {"url": "https://x.com"}, "web_lookup": {"query": "q"}}


@respx.mock
@pytest.mark.parametrize("name", sorted(VALID))
def test_run_tool_rejects_bad_arguments(name, db):
    respx.route().mock(return_value=httpx.Response(500))
    ctx = make_ctx(db)
    assert "error" in run_tool(ctx, name, {**VALID[name], "extra": 1})
    assert "error" in run_tool(ctx, name, {})
    for k in VALID[name]:
        assert "error" in run_tool(ctx, name, {**VALID[name], k: 123})
        assert "error" in run_tool(ctx, name, {**VALID[name], k: None})
        assert "error" in run_tool(ctx, name, {**VALID[name], k: ["x"]})


def test_run_tool_unknown_and_fuzz(db):
    ctx = make_ctx(db)
    assert run_tool(ctx, "rm_rf", {}) == {"error": "unknown tool"}
    for name in (None, 5, "", "__import__", ["check_url"], "check_url ", "TOOLS"):
        assert run_tool(ctx, name, {}) == {"error": "unknown tool"}
    for args in (None, "x", [], 5, {"domain": {"a": 1}}, {1: 2}, {"domain": "x.com", "domain2": 1}):
        for name in list(TOOLS) + ["nope"]:
            assert "error" in run_tool(ctx, name, args)


def test_run_tool_catches_exceptions_and_caps_size(monkeypatch):
    def boom(ctx, **kw):
        raise RuntimeError("secret internals")

    monkeypatch.setitem(TOOLS, "check_number", tools.Tool("check_number", "d", TOOLS["check_number"].parameters, boom))
    assert run_tool(make_ctx(), "check_number", {"e164": "+919876543210"}) == {"error": "tool failed"}
    big = tools.Tool("check_number", "d", TOOLS["check_number"].parameters, lambda ctx, **kw: {"x": "y" * 50000})
    monkeypatch.setitem(TOOLS, "check_number", big)
    assert len(json.dumps(run_tool(make_ctx(), "check_number", {"e164": "+919876543210"}))) <= 4100


# ---------------------------------------------------------------- fix round 1
BIG = httpx.Response(200, content=b'{"events": [], "pad": "' + b"x" * (5 * 1024 * 1024) + b'"}')


@respx.mock
def test_rdap_oversize_body_is_failure():
    respx.get("https://rdap.org/domain/a.xyz").mock(return_value=BIG)
    assert run_tool(make_ctx(), "check_url", {"domain": "a.xyz"})["age_days"] is None
    # Content-Length lie / header check path
    respx.get("https://rdap.org/domain/b.xyz").mock(return_value=httpx.Response(
        200, content=b"{}", headers={"content-length": "999999999"}))
    assert run_tool(make_ctx(), "check_url", {"domain": "b.xyz"})["age_days"] is None


@respx.mock
def test_tavily_oversize_body_is_empty():
    respx.post("https://api.tavily.com/search").mock(return_value=BIG)
    assert tools.web_lookup(make_ctx(settings=settings(tavily_api_key="k")), query="x") == []


@respx.mock
@pytest.mark.parametrize("loc", ["https://localtest.me/", "https://user:pw@rdap.org/x",
                                 "https://rdap.org:8443/x", "http://rdap.org/x",
                                 "https://rdap.org.evil.com/x", "https://evilrdap.org/x"])
def test_rdap_redirect_rejected(loc):
    first = respx.get("https://rdap.org/domain/a.xyz").mock(
        return_value=httpx.Response(302, headers={"location": loc}))
    other = respx.route().mock(return_value=rdap(3))
    assert run_tool(make_ctx(), "check_url", {"domain": "a.xyz"})["age_days"] is None
    assert first.call_count == 1 and other.call_count == 0


@respx.mock
@pytest.mark.parametrize("d", ["hdfcbank-login.amazonaws.com", "hdfcbank.web.app", "sbi-kyc.github.io",
                               "paytm-refund.vercel.app", "x.sbi-kyc.github.io"])
def test_infra_hosted_lookalikes_flagged(d):
    respx.get(url__startswith="https://rdap.org/").mock(return_value=httpx.Response(404))
    assert run_tool(make_ctx(), "check_url", {"domain": d})["lookalike_of"] is not None


@respx.mock
@pytest.mark.parametrize("d", ["aws.amazon.com", "s3.amazonaws.com", "mail.google.com", "my-app.web.app",
                               "foo.github.io", "amazonaws.com"])
def test_infra_legit_not_flagged(d):
    respx.get(url__startswith="https://rdap.org/").mock(return_value=httpx.Response(404))
    assert run_tool(make_ctx(), "check_url", {"domain": d})["lookalike_of"] is None


@respx.mock
def test_case_insensitive_and_punycode_skeleton():
    respx.get(url__startswith="https://rdap.org/").mock(return_value=httpx.Response(404))
    assert run_tool(make_ctx(), "check_url", {"domain": "HDFCBank-KYC.in"})["lookalike_of"] == "hdfcbank.com"
    cyr = "раураl".encode("idna").decode()  # cyrillic homoglyph label
    d = "\u0440\u0430ytm-refund.top".encode("idna").decode()
    assert d.startswith("xn--")
    assert run_tool(make_ctx(), "check_url", {"domain": d})["lookalike_of"] == "paytm.com"
    assert cyr


def test_final_output_cap_with_truncated_key(monkeypatch):
    big = tools.Tool("check_number", "d", TOOLS["check_number"].parameters,
                     lambda ctx, **kw: {"events": [{"t": "y" * 100}] * 100, "pad": "z" * 10})
    monkeypatch.setitem(TOOLS, "check_number", big)
    out = run_tool(make_ctx(), "check_number", {"e164": "+919876543210"})
    assert out["truncated"] is True and len(json.dumps(out)) <= 4000
    edge = tools.Tool("check_number", "d", TOOLS["check_number"].parameters,
                      lambda ctx, **kw: {"events": [{"t": "y" * 30}] * 99 + [{"t": "y" * 3871}][:0]})
    monkeypatch.setitem(TOOLS, "check_number", edge)
    assert len(json.dumps(run_tool(make_ctx(), "check_number", {"e164": "+919876543210"}))) <= 4000


def test_memory_history_limit(db, register):
    a = uid(register, db)
    for i in range(5):
        ingest(db, a, f"l{i}", [P], minutes=i)
    from app.pipeline.memory import history
    full = history(db, a, P, NOW)
    lim = history(db, a, P, NOW, limit=2)
    assert lim == full[-2:] and len(full) == 5
