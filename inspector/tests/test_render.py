"""Chromium rendering path. Skips cleanly if Chromium cannot be launched here."""

import asyncio

import pytest
from fastapi.testclient import TestClient

from app import create_app
from render import Renderer

SECRET = "s3cret-" + "x" * 25
H = {"X-Inspector-Secret": SECRET}


def _chromium_ok() -> bool:
    async def probe():
        r = Renderer()
        try:
            return await r.render("<p>x</p>", timeout=10) is not None
        finally:
            await r.close()
    try:
        return asyncio.run(probe())
    except Exception:
        return False


pytestmark = pytest.mark.skipif(not _chromium_ok(), reason="Chromium not launchable here")


class NoFeeds:
    async def check(self, urls):
        return []


@pytest.fixture
def client(server, resolver):
    app = create_app(secret=SECRET, resolver=resolver, feeds=NoFeeds(), render=True,
                     _test_allow=frozenset({("127.0.0.1", server.port)}))
    with TestClient(app) as c:
        yield c


def test_js_built_form_detected_only_when_rendered(client, server, resolver):
    url = server.url("phish.sentinel-test.com", "/jsform")
    b = client.post("/inspect", json={"url": url}, headers=H).json()
    assert b["fetched_ok"] and b["has_password_field"] and b["has_otp_or_upi_or_card_field"]
    app = create_app(secret=SECRET, resolver=resolver, feeds=NoFeeds(), render=False,
                     _test_allow=frozenset({("127.0.0.1", server.port)}))
    with TestClient(app) as c:
        s = c.post("/inspect", json={"url": url}, headers=H).json()
    assert s["fetched_ok"] and not s["has_password_field"]


def test_chromium_makes_no_network_requests(client, server):
    before = len(server.hits)
    b = client.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/external")},
                    headers=H).json()
    assert b["fetched_ok"] and b["title"] == "ext"
    import time
    time.sleep(0.5)
    assert server.hits[before:] == ["/external"]  # only our own pinned fetch


def test_rendered_login_page(client, server):
    b = client.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/login")}, headers=H).json()
    assert b["has_password_field"] and b["has_otp_or_upi_or_card_field"] and b["title"].startswith("HDFC")


async def test_infinite_loop_page_is_bounded():
    r = Renderer()
    try:
        import time
        t = time.monotonic()
        out = await r.render("<title>a</title><script>while(true){}</script><input type=password>", timeout=1.5)
        assert time.monotonic() - t < 6
        assert out is None or isinstance(out, str)
        # the renderer recovers for the next page
        again = await r.render("<input type=password>", timeout=5)
        assert again is not None and "password" in again
    finally:
        await r.close()


async def test_renderer_cannot_navigate_away():
    r = Renderer()
    try:
        for nav in ("setTimeout(()=>location.replace('http://example.com/'),10)",
                    "setTimeout(()=>window.open('http://example.com/'),10)"):
            out = await r.render(f"<p id=still>here</p><input type=password><script>{nav}</script>", timeout=3)
            assert out is not None and "still" in out and "password" in out
        # meta refresh to elsewhere is answered locally, the document stays
        out = await r.render("<meta http-equiv=refresh content='0;url=http://x.com'><b id=m>m</b>", timeout=3)
        assert out is not None and 'id="m"' in out
        # a synchronous navigation during parsing stops Chromium's parser; render must still return
        # promptly (the static pass, which is unioned in, still sees the rest of the page)
        await r.render("<script>location.href='http://example.com/'</script><p>x</p>", timeout=2)
    finally:
        await r.close()


def test_js_cloaking_cannot_hide_fields(client, server):
    """The page deletes its form and blanks itself with JS; the static pass is unioned in."""
    b = client.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/cloak")}, headers=H).json()
    assert b["fetched_ok"] and b["has_password_field"] and b["has_otp_or_upi_or_card_field"]
