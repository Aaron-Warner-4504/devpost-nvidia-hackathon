"""SSRF guard: URL syntax, address classification, resolution and pinning."""

import pytest

from guard import ALLOWED_PORTS, Blocked, Policy, is_public_ip, parse_url, resolve_pinned
from fetch import PinnedBackend


# ---------------------------------------------------------------- address classifier
@pytest.mark.parametrize("ip", [
    "127.0.0.1", "127.255.255.254", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
    "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.254", "0.0.0.0", "0.1.2.3",
    "224.0.0.1", "239.255.255.250", "255.255.255.255", "240.0.0.1", "192.0.0.8", "192.0.2.1",
    "198.18.0.1", "198.51.100.1", "203.0.113.1",
    "::1", "::", "fe80::1", "fd00::1", "fc00::1", "fd00:ec2::254", "ff02::1",
    "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::127.0.0.1",
    "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b:1::a00:1",
    "2002:7f00:1::1", "2002:a00:1::1", "2002:a9fe:a9fe::1",
    "2001:0:4136:e378:8000:63bf:80ff:fffe",  # Teredo embedding 127.0.0.1 (obfuscated)
    "2001:db8::1", "100::1",
])
def test_non_global_addresses_are_rejected(ip):
    assert is_public_ip(ip) is False


@pytest.mark.parametrize("ip", ["8.8.8.8", "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111",
                                "2a00:1450:4001:830::200e"])
def test_public_addresses_are_accepted(ip):
    assert is_public_ip(ip) is True


@pytest.mark.parametrize("ip", ["", "localhost", "999.1.1.1", "1.2.3", "fe80::1%eth0", None, "1.2.3.4 "])
def test_garbage_is_not_public(ip):
    assert is_public_ip(ip) is False


# ---------------------------------------------------------------- URL syntax
@pytest.mark.parametrize("url", [
    "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://0x7f.0.0.1/",
    "http://127.1/", "http://127.0.0.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/",
    "http://[::ffff:7f00:1]/", "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/",
    "http://8.8.8.8/",  # literal IPs are rejected outright, even public ones
    "http://localhost/", "http://LOCALHOST./", "http://foo.localhost/", "http://metadata.google.internal/",
    "http://metadata/", "http://instance-data/", "http://printer.local/", "http://x.internal/",
    "http://user:pass@example.com/", "http://user@example.com/", "http://@example.com/",
    "ftp://example.com/", "file:///etc/passwd", "gopher://example.com/", "javascript:alert(1)",
    "http://example.com:22/", "http://example.com:6379/", "http://example.com:0/", "http://example.com:99999/",
    "http:///path", "http://", "example.com", "//example.com/",
    "http://exa mple.com/", "http://example.com\\@evil.com/", "http://exam\nple.com/",
    "http://" + "a" * 64 + ".com/", "http://" + ("a" * 60 + ".") * 5 + "com/",
    "http://example.com/" + "a" * 2100, "http://-bad-.com/", "http://example.123/",
    "http://exa%6dple.com/", "http://example.com%2f@evil.com/",
])
def test_bad_urls_rejected(url):
    with pytest.raises(Blocked):
        parse_url(url)


def test_good_urls_normalised():
    t = parse_url("HTTPS://Example.COM./a b?q=1#frag")
    assert (t.scheme, t.host, t.port) == ("https", "example.com", 443)
    assert t.url == "https://example.com/a%20b?q=1" and t.target == "/a%20b?q=1"
    assert parse_url("http://example.com:8080").target == "/"
    assert parse_url("http://example.com:8080").url == "http://example.com:8080/"
    assert parse_url("http://bücher.de/").host == "xn--bcher-kva.de"
    assert ALLOWED_PORTS == frozenset({80, 443, 8080, 8443})


# ---------------------------------------------------------------- resolution
async def test_resolution_rejects_any_private_answer(resolver):
    pol = Policy()
    with pytest.raises(Blocked):  # one public + one private answer
        await resolve_pinned(parse_url("http://mixed.sentinel-test.com/"), resolver, pol)
    with pytest.raises(Blocked):
        await resolve_pinned(parse_url("http://intranet.sentinel-test.com/"), resolver, pol)
    with pytest.raises(Blocked):  # nxdomain
        await resolve_pinned(parse_url("http://nope.sentinel-test.com/"), resolver, pol)


async def test_resolution_accepts_public_and_pins_first(resolver):
    resolver.table["pub.sentinel-test.com"] = ["93.184.216.34", "2606:2800:220:1::1"]
    assert await resolve_pinned(parse_url("http://pub.sentinel-test.com/"), resolver, Policy()) == "93.184.216.34"


async def test_resolver_returning_garbage_is_blocked(resolver):
    resolver.table["junk.sentinel-test.com"] = ["not-an-ip"]
    resolver.table["empty.sentinel-test.com"] = []
    for h in ("junk", "empty"):
        with pytest.raises(Blocked):
            await resolve_pinned(parse_url(f"http://{h}.sentinel-test.com/"), resolver, Policy())


async def test_test_allowlist_is_exact_ip_and_port(resolver):
    pol = Policy(test_allow=frozenset({("127.0.0.1", 5555)}))
    with pytest.raises(Blocked):  # the default parser never accepts the test port
        parse_url("http://phish.sentinel-test.com:5555/")
    assert await resolve_pinned(pol.parse("http://phish.sentinel-test.com:5555/"), resolver, pol) == "127.0.0.1"
    with pytest.raises(Blocked):  # same IP, other port: still blocked
        await resolve_pinned(pol.parse("http://phish.sentinel-test.com/"), resolver, pol)
    with pytest.raises(Blocked):  # allowlisted port but a different private IP
        await resolve_pinned(pol.parse("http://intranet.sentinel-test.com:5555/"), resolver, pol)
    with pytest.raises(Blocked):  # allowlisted port but a public IP is not on an allowed port
        resolver.table["pub.sentinel-test.com"] = ["93.184.216.34"]
        await resolve_pinned(pol.parse("http://pub.sentinel-test.com:5555/"), resolver, pol)
    with pytest.raises(Blocked):  # never by IP literal or blocked name
        pol.parse("http://127.0.0.1:5555/")
    with pytest.raises(Blocked):
        pol.parse("http://localhost:5555/")


def test_no_env_or_default_disables_the_guard(monkeypatch):
    for k in ("INSPECTOR_ALLOW_PRIVATE", "ALLOW_PRIVATE", "INSPECTOR_TEST_ALLOW"):
        monkeypatch.setenv(k, "1")
    assert Policy().test_allow == frozenset()
    assert Policy().allows("127.0.0.1", 80) is False


# ---------------------------------------------------------------- pinning
class _Inner:
    def __init__(self):
        self.connected = []

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        self.connected.append((host, port))
        return "stream"


async def test_pinned_backend_connects_only_to_pinned_ip():
    inner = _Inner()
    b = PinnedBackend({("example.com", 443): "93.184.216.34"}, inner=inner)
    assert await b.connect_tcp("example.com", 443) == "stream"
    assert inner.connected == [("93.184.216.34", 443)]
    with pytest.raises(Exception):
        await b.connect_tcp("other.com", 443)
    with pytest.raises(Exception):
        await b.connect_tcp("example.com", 80)
    with pytest.raises(Exception):
        await b.connect_unix_socket("/var/run/docker.sock")
    assert inner.connected == [("93.184.216.34", 443)]
