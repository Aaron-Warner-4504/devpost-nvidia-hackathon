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


async def test_huge_dom_is_bounded():
    import time
    r = Renderer()
    try:
        t = time.monotonic()
        out = await r.render("<title>h</title><input type=password><script>"
                             "document.body.textContent='x'.repeat(60000000);</script>", timeout=3)
        assert time.monotonic() - t < 8
        assert out is None or len(out) <= 2 * 1024 * 1024
        many = await r.render("<body><script>for(let i=0;i<60000;i++)document.body.appendChild(document.createElement('i'))"
                              "</script>", timeout=3)
        assert many is None
        again = await r.render("<input type=password>", timeout=5)
        assert again is not None and "password" in again
    finally:
        await r.close()


async def test_dom_read_ignores_page_monkeypatching():
    r = Renderer()
    try:
        out = await r.render("<input type=password><script>Object.defineProperty(Element.prototype,'outerHTML',"
                             "{get(){return '<p>nothing here</p>'}});String.prototype.slice=()=>'';</script>", timeout=3)
        assert out is not None and "password" in out
    finally:
        await r.close()


def test_huge_dom_page_end_to_end(client, server):
    b = client.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/huge-dom")}, headers=H).json()
    assert b["fetched_ok"] and b["has_password_field"] and len(b["text_excerpt"]) <= 1500


def _descendants(pid: int) -> list[int]:
    import os
    kids: dict[int, list[int]] = {}
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                with open(f"/proc/{d}/stat") as f:
                    ppid = int(f.read().rsplit(")", 1)[1].split()[1])
                kids.setdefault(ppid, []).append(int(d))
            except (OSError, ValueError, IndexError):
                pass
    out, todo = [], [pid]
    while todo:
        for k in kids.get(todo.pop(), []):
            out.append(k)
            todo.append(k)
    return out


@pytest.mark.skipif(not __import__("sys").platform.startswith("linux"), reason="needs /proc")
def test_driver_and_chromium_environ_hold_no_secrets(monkeypatch, server, resolver):
    import os
    secrets = {"INSPECTOR_SECRET": SECRET, "SAFE_BROWSING_API_KEY": "sb-key-" + "q" * 30,
               "URLHAUS_AUTH_KEY": "uh-key-" + "w" * 30}
    for k, v in secrets.items():
        monkeypatch.setenv(k, v)
    app = create_app(resolver=resolver, render=True, _test_allow=frozenset({("127.0.0.1", server.port)}))
    with TestClient(app) as c:
        b = c.post("/inspect", json={"url": server.url("phish.sentinel-test.com", "/jsform")}, headers=H).json()
        assert b["has_password_field"]  # the render stack really ran
        procs = _descendants(os.getpid())
        envs = {}
        for p in procs:
            try:
                with open(f"/proc/{p}/environ", "rb") as f:
                    envs[p] = f.read()
            except OSError:
                pass
        names = []
        for p in envs:
            try:
                names.append(open(f"/proc/{p}/cmdline", "rb").read().split(b"\0")[0].decode(errors="replace"))
            except OSError:
                pass
        assert any("node" in n for n in names) and any("chrome" in n for n in names), names
        for p, env in envs.items():
            for v in secrets.values():
                assert v.encode() not in env, (p, "secret leaked into child environment")
