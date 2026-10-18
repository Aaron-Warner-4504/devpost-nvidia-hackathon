"""The /inspect API end to end (static analysis path; Chromium is covered in test_render.py)."""

import asyncio
import json
import os
import tempfile
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from app import create_app

SECRET = "s3cret-" + "x" * 25
H = {"X-Inspector-Secret": SECRET}
FIELDS = {"final_url", "redirect_chain", "status", "title", "has_password_field",
          "has_otp_or_upi_or_card_field", "triggers_download", "apk_download", "brand_mismatch",
          "threat_feed_hits", "text_excerpt", "fetched_ok"}


class FakeFeeds:
    def __init__(self, hits=None):
        self.seen: list[list[str]] = []
        self.hits = hits or {}

    async def check(self, urls):
        self.seen.append(list(urls))
        return sorted({h for u in urls for h in self.hits.get(u, [])})


def make(server, resolver, **kw):
    kw.setdefault("feeds", FakeFeeds())
    kw.setdefault("render", False)
    return create_app(secret=SECRET, resolver=resolver,
                      _test_allow=frozenset({("127.0.0.1", server.port)}), **kw)


@pytest.fixture
def client(server, resolver):
    with TestClient(make(server, resolver)) as c:
        yield c


def inspect(c, url, headers=H):
    r = c.post("/inspect", json={"url": url}, headers=headers)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == FIELDS
    return body


# ---------------------------------------------------------------- startup / auth
@pytest.mark.parametrize("bad", [None, "", "short", " " * 20, "x" * 15])
def test_refuses_to_start_without_strong_secret(monkeypatch, bad):
    monkeypatch.delenv("INSPECTOR_SECRET", raising=False)
    if bad is not None:
        monkeypatch.setenv("INSPECTOR_SECRET", bad)
    with pytest.raises(RuntimeError):
        create_app()


def test_secret_from_env(monkeypatch):
    monkeypatch.setenv("INSPECTOR_SECRET", SECRET)
    with TestClient(create_app(render=False)) as c:
        assert c.post("/inspect", json={"url": "http://localhost/"}, headers=H).status_code == 200


def test_health_is_unauthenticated_and_leaks_nothing(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json() == {"ok": True}


@pytest.mark.parametrize("headers", [{}, {"X-Inspector-Secret": "wrong"}, {"X-Inspector-Secret": SECRET + "x"},
                                     {"X-Inspector-Secret": SECRET[:-1]}, {"Authorization": SECRET}])
def test_inspect_requires_secret(client, server, headers):
    before = len(server.hits)
    r = client.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/login")}, headers=headers)
    assert r.status_code == 401 and SECRET not in r.text
    assert len(server.hits) == before


def test_request_size_and_shape_limits(client):
    assert client.post("/inspect", content=b'{"url":"' + b"a" * 5000 + b'"}', headers={
        **H, "content-type": "application/json"}).status_code == 413
    assert client.post("/inspect", content=b"not json", headers={**H, "content-type": "application/json"}).status_code == 422
    assert client.post("/inspect", json={"url": 5}, headers=H).status_code == 422
    assert client.post("/inspect", json={"url": "http://a.com", "allow_private": True}, headers=H).status_code == 422
    assert client.post("/inspect", json=["http://a.com"], headers=H).status_code == 422


def test_docs_disabled(client):
    for p in ("/docs", "/openapi.json", "/redoc"):
        assert client.get(p).status_code == 404


# ---------------------------------------------------------------- behaviour
def test_login_page_with_password_and_otp(client, server):
    url = server.url("phish.sentinel-test.com", "/login")
    b = inspect(client, url)
    assert b["fetched_ok"] and b["status"] == 200 and b["final_url"] == url
    assert b["has_password_field"] and b["has_otp_or_upi_or_card_field"]
    assert b["title"] == "HDFC Bank NetBanking - Verify" and "Login to continue" in b["text_excerpt"]
    assert b["brand_mismatch"] == "HDFC Bank"
    assert not b["triggers_download"] and not b["apk_download"]


def test_hindi_otp_field(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/hindi"))
    assert b["fetched_ok"] and b["has_otp_or_upi_or_card_field"] and not b["has_password_field"]


def test_plain_page_is_clean(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/plain"))
    assert b["fetched_ok"] and not b["has_password_field"] and not b["has_otp_or_upi_or_card_field"]
    assert b["brand_mismatch"] is None and b["threat_feed_hits"] == []


def test_shortener_redirect_chain_resolved(client, server):
    b = inspect(client, server.url("short.sentinel-test.com", "/s/abc"))
    assert b["fetched_ok"] and b["final_url"] == server.url("hop.sentinel-test.com", "/login")
    assert b["redirect_chain"] == [server.url("short.sentinel-test.com", "/s/abc"),
                                   server.url("hop.sentinel-test.com", "/hop"),
                                   server.url("hop.sentinel-test.com", "/login")]
    assert b["has_password_field"]


@pytest.mark.parametrize("url", ["http://169.254.169.254/", "http://169.254.169.254/latest/meta-data/",
                                 "http://localhost", "http://localhost/", "http://127.0.0.1/",
                                 "http://[::1]/", "http://2130706433/", "http://0x7f000001/",
                                 "http://metadata.google.internal/", "file:///etc/passwd", "not a url"])
def test_private_targets_rejected(client, url):
    b = inspect(client, url)
    assert b["fetched_ok"] is False and b["status"] is None and b["title"] is None


@pytest.mark.parametrize("path", ["/to-metadata", "/to-localhost", "/to-private-host", "/to-loopback-ip"])
def test_redirect_into_private_rejected(client, server, path):
    b = inspect(client, server.url("phish.sentinel-test.com", path))
    assert b["fetched_ok"] is False and b["has_password_field"] is False
    assert b["redirect_chain"][0] == server.url("phish.sentinel-test.com", path)


def test_apk_download_detected_nothing_written(client, server):
    before = {d: sorted(os.listdir(d)) for d in (tempfile.gettempdir(), os.getcwd())}
    b = inspect(client, server.url("files.sentinel-test.com", "/app.apk"))
    assert b["fetched_ok"] and b["triggers_download"] and b["apk_download"]
    assert b["text_excerpt"] == "" and b["title"] is None
    after = {d: sorted(os.listdir(d)) for d in (tempfile.gettempdir(), os.getcwd())}
    assert after == before


def test_big_page_truncated_still_analysed(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/big"))
    assert b["fetched_ok"] and b["has_password_field"] and not b["has_otp_or_upi_or_card_field"]
    assert len(b["text_excerpt"]) <= 1500


def test_timeout_returns_not_fetched(server, resolver):
    with TestClient(make(server, resolver, total_timeout=1.5)) as c:
        for path in ("/slow", "/trickle"):
            t = time.monotonic()
            b = inspect(c, server.url("phish.sentinel-test.com", path))
            assert time.monotonic() - t < 4
            assert b["fetched_ok"] is False


def test_threat_feeds_original_and_final(server, resolver):
    start = server.url("short.sentinel-test.com", "/s/abc")
    final = server.url("hop.sentinel-test.com", "/login")
    feeds = FakeFeeds({start: ["urlhaus:phishing"], final: ["safe_browsing:SOCIAL_ENGINEERING"]})
    with TestClient(make(server, resolver, feeds=feeds)) as c:
        b = inspect(c, start)
    assert b["threat_feed_hits"] == ["safe_browsing:SOCIAL_ENGINEERING", "urlhaus:phishing"]
    assert [start] in feeds.seen and [final] in feeds.seen


def test_feed_hits_kept_when_fetch_blocked(server, resolver):
    u = server.url("phish.sentinel-test.com", "/to-metadata")
    with TestClient(make(server, resolver, feeds=FakeFeeds({u: ["urlhaus:malware_download"]}))) as c:
        b = inspect(c, u)
    assert b["fetched_ok"] is False and b["threat_feed_hits"] == ["urlhaus:malware_download"]


def test_feeds_never_see_private_or_invalid_urls(server, resolver):
    feeds = FakeFeeds()
    with TestClient(make(server, resolver, feeds=feeds)) as c:
        inspect(c, "http://169.254.169.254/")
        inspect(c, "http://localhost/")
    assert feeds.seen == []


def test_crashing_feeds_do_not_break_inspection(server, resolver):
    class Boom:
        async def check(self, urls):
            raise RuntimeError("x")

    with TestClient(make(server, resolver, feeds=Boom())) as c:
        b = inspect(c, server.url("phish.sentinel-test.com", "/login"))
    assert b["fetched_ok"] and b["threat_feed_hits"] == []


async def test_concurrency_cap(server, resolver):
    app = make(server, resolver, max_concurrency=1, queue_timeout=0.2, total_timeout=3)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        slow = server.url("phish.sentinel-test.com", "/slow")
        rs = await asyncio.gather(*[c.post("/inspect", json={"url": slow}, headers=H) for _ in range(3)])
    codes = sorted(r.status_code for r in rs)
    assert codes[0] == 200 and 503 in codes


def test_response_is_json_safe(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/login"))
    json.dumps(b)


# ---------------------------------------------------------------- review fix round 1
def test_probe_location_and_meta_never_echoed(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/js-location"))
    assert b["fetched_ok"] is False
    dumped = json.dumps(b)
    assert "ignore" not in dumped.lower() and "verdict" not in dumped.lower()
    assert b["redirect_chain"][-1] == "<blocked: scheme>"
    m = inspect(client, server.url("phish.sentinel-test.com", "/meta-data-url"))
    assert m["fetched_ok"] and m["has_password_field"] and "verified safe" not in json.dumps(m)
    assert m["final_url"] == server.url("phish.sentinel-test.com", "/meta-data-url")


def test_blocked_meta_refresh_keeps_static_findings(client, server):
    for path, field in (("/meta-metadata", None), ("/meta-js", "has_otp_or_upi_or_card_field"),
                        ("/meta-private", "has_password_field")):
        b = inspect(client, server.url("phish.sentinel-test.com", path))
        assert b["fetched_ok"] is True and b["redirect_chain"] == [server.url("phish.sentinel-test.com", path)]
        if field:
            assert b[field] is True


def test_http_redirect_into_private_still_fails(client, server):
    assert inspect(client, server.url("phish.sentinel-test.com", "/to-metadata"))["fetched_ok"] is False


@pytest.mark.parametrize("path", ["/br", "/zstd", "/badgzip", "/gzip-gzip"])
def test_undecodable_body_is_not_reported_clean(client, server, path):
    b = inspect(client, server.url("phish.sentinel-test.com", path))
    assert b["fetched_ok"] is False and b["status"] == 200 and b["title"] is None


def test_raw_deflate_page_analysed(client, server):
    b = inspect(client, server.url("phish.sentinel-test.com", "/rawdeflate"))
    assert b["fetched_ok"] and b["has_password_field"]


class StuckRenderer:
    async def start(self):
        return True

    async def close(self):
        pass

    async def render(self, html, timeout=3.0):
        await asyncio.sleep(60)  # ignores its timeout


def test_render_timeout_keeps_static_findings(server, resolver):
    with TestClient(make(server, resolver, renderer=StuckRenderer(), total_timeout=3)) as c:
        t = time.monotonic()
        b = inspect(c, server.url("phish.sentinel-test.com", "/login"))
        assert time.monotonic() - t < 4
    assert b["fetched_ok"] and b["has_password_field"] and b["has_otp_or_upi_or_card_field"]
    assert b["title"].startswith("HDFC")


SECRETS = {"INSPECTOR_SECRET": SECRET, "SAFE_BROWSING_API_KEY": "sb-key-" + "q" * 30,
           "URLHAUS_AUTH_KEY": "uh-key-" + "w" * 30}


def test_secrets_removed_from_environment_but_kept_in_objects(monkeypatch):
    for k, v in SECRETS.items():
        monkeypatch.setenv(k, v)
    app = create_app(render=False)
    assert not set(SECRETS) & set(os.environ)
    feeds = app.state.inspector.feeds
    assert feeds.sb_key == SECRETS["SAFE_BROWSING_API_KEY"] and feeds.urlhaus_key == SECRETS["URLHAUS_AUTH_KEY"]
    with TestClient(app) as c:
        assert c.post("/inspect", json={"url": "http://localhost/"}, headers=H).status_code == 200
