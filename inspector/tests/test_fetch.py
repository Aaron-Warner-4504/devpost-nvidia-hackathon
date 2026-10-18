"""Pinned fetcher against the local test server."""

import os
import tempfile

import pytest

from fetch import MAX_BODY, fetch
from guard import Policy


@pytest.fixture
def pol(server):
    return Policy(test_allow=frozenset({("127.0.0.1", server.port)}))


async def go(server, resolver, pol, host, path, **kw):
    return await fetch(server.url(host, path), resolver=resolver, policy=pol, **kw)


async def test_plain_fetch(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/login")
    assert r.ok and r.status == 200 and b"type='password'" in r.body
    assert r.chain == [server.url("phish.sentinel-test.com", "/login")]
    assert r.final_url == r.chain[-1]


async def test_redirect_chain_from_shortener(server, resolver, pol):
    r = await go(server, resolver, pol, "short.sentinel-test.com", "/s/abc")
    assert r.ok and r.status == 200
    assert r.chain == [server.url("short.sentinel-test.com", "/s/abc"),
                       server.url("hop.sentinel-test.com", "/hop"),
                       server.url("hop.sentinel-test.com", "/login")]
    assert r.final_url == server.url("hop.sentinel-test.com", "/login")


async def test_meta_refresh_is_followed_as_a_hop(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/meta")
    assert r.ok and r.final_url.endswith("/login") and len(r.chain) == 2


@pytest.mark.parametrize("path", ["/to-metadata", "/to-localhost", "/to-loopback-ip", "/to-decimal",
                                  "/to-private-host"])
async def test_redirect_into_private_space_is_blocked(server, resolver, pol, path):
    before = list(server.hits)
    r = await go(server, resolver, pol, "phish.sentinel-test.com", path)
    assert not r.ok and r.error and r.error.startswith("blocked")
    assert server.hits[len(before):] == [path]  # nothing but the first hop reached any server


@pytest.mark.parametrize("url", ["http://169.254.169.254/", "http://localhost/", "http://127.0.0.1/",
                                 "http://[::1]/", "http://metadata.google.internal/computeMetadata/v1/",
                                 "http://intranet.sentinel-test.com/", "http://mixed.sentinel-test.com/"])
async def test_direct_private_targets_blocked(resolver, url):
    r = await fetch(url, resolver=resolver, policy=Policy())
    assert not r.ok and r.error.startswith("blocked")


async def test_dns_rebinding_cannot_switch_address(server, resolver, pol):
    connected = []
    r = await go(server, resolver, pol, "rebind.sentinel-test.com", "/login", on_connect=connected.append)
    assert r.ok  # first answer (allowlisted 127.0.0.1) is pinned
    assert resolver.calls.count("rebind.sentinel-test.com") == 1  # never re-resolved by the client
    assert connected == ["127.0.0.1"]


async def test_rebinding_on_a_later_hop_is_rechecked(server, resolver, pol):
    resolver.calls.append("rebind.sentinel-test.com")  # 2nd lookup returns 10.1.2.3
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/to-rebind")
    assert not r.ok and r.error.startswith("blocked")


async def test_too_many_redirects(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/loop")
    assert not r.ok and r.error == "too many redirects" and len(r.chain) == 6
    ok = await go(server, resolver, pol, "phish.sentinel-test.com", "/chain/5")
    assert ok.ok and len(ok.chain) == 6
    bad = await go(server, resolver, pol, "phish.sentinel-test.com", "/chain/6")
    assert not bad.ok


def _snapshot():
    dirs = [tempfile.gettempdir(), os.getcwd()]
    return {d: sorted(os.listdir(d)) for d in dirs}


@pytest.mark.parametrize("path,apk", [("/app.apk", True), ("/download?id=1", True), ("/apk-octet", True),
                                      ("/report.zip", False)])
async def test_downloads_detected_and_aborted(server, resolver, pol, path, apk):
    before = _snapshot()
    r = await go(server, resolver, pol, "files.sentinel-test.com", path)
    assert r.ok and r.triggers_download is True and r.apk_download is apk
    assert len(r.body) <= 4096  # only a small sniff was read
    assert _snapshot() == before  # nothing written to disk


async def test_large_page_is_truncated(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/big")
    assert r.ok and r.truncated and len(r.body) == MAX_BODY == 2 * 1024 * 1024
    assert b"type=password" in r.body and b"upi_pin" not in r.body


async def test_gzip_bomb_is_capped(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/gzbomb")
    assert r.ok and r.truncated and len(r.body) <= MAX_BODY
    g = await go(server, resolver, pol, "phish.sentinel-test.com", "/gzlogin")
    assert g.ok and b"type='password'" in g.body


async def test_read_idle_timeout(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/slow", read_timeout=0.5)
    assert not r.ok and r.error == "timeout"


async def test_404_still_fetched(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/notfound-phish")
    assert r.ok and r.status == 404 and b"password" in r.body


# ---------------------------------------------------------------- review fix round 1
async def test_blocked_location_is_not_echoed(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/js-location")
    assert not r.ok and r.chain[-1] == "<blocked: scheme>"
    assert not any("ignore" in c.lower() or "SAFE" in c for c in r.chain) and "ignore" not in r.final_url.lower()
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/to-decimal")
    assert r.chain[-1] == "<blocked: host>"


@pytest.mark.parametrize("path,marker", [("/meta-data-url", b"password"), ("/meta-js", b"otp"),
                                         ("/meta-metadata", b"169.254")])
async def test_blocked_meta_refresh_keeps_current_page(server, resolver, pol, path, marker):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", path)
    assert r.ok and r.error is None and r.status == 200 and marker in r.body
    assert r.chain == [server.url("phish.sentinel-test.com", path)] and r.final_url == r.chain[0]


async def test_meta_refresh_to_private_resolving_host_keeps_page(server, resolver, pol):
    resolver.table["meta-private.sentinel-test.com"] = ["10.9.9.9"]
    before = len(server.hits)
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/meta-private")
    assert r.ok and r.final_url == server.url("phish.sentinel-test.com", "/meta-private")
    assert len(r.chain) == 1 and b"password" in r.body and len(server.hits) == before + 1
    ok = await go(server, resolver, pol, "phish.sentinel-test.com", "/meta")  # allowed targets still followed
    assert ok.ok and ok.final_url.endswith("/login")


@pytest.mark.parametrize("path", ["/br", "/zstd", "/badgzip", "/gzip-gzip"])
async def test_undecodable_body_is_flagged_not_clean(server, resolver, pol, path):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", path)
    assert r.ok and r.status == 200 and r.undecodable and r.body == b""


@pytest.mark.parametrize("path", ["/rawdeflate", "/zdeflate", "/gzlogin"])
async def test_deflate_variants_decoded(server, resolver, pol, path):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", path)
    assert r.ok and not r.undecodable and b"type='password'" in r.body


async def test_accept_encoding_never_offers_br_or_zstd(server, resolver, pol):
    r = await go(server, resolver, pol, "phish.sentinel-test.com", "/echo-headers")
    assert b"<title>gzip, deflate</title>" in r.body
