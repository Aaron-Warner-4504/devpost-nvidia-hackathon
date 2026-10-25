"""Client IP derivation for pre-auth rate limits (app/ratelimit.py)."""
import pytest
from starlette.requests import Request

from app import ratelimit
from app.config import settings


def req(peer="10.0.0.1", xff=None):
    headers = [(b"x-forwarded-for", xff.encode())] if xff is not None else []
    return Request({"type": "http", "method": "POST", "path": "/", "headers": headers,
                    "client": (peer, 5000) if peer else None})


@pytest.fixture
def trusted(monkeypatch):
    monkeypatch.setattr(settings, "trust_proxy_headers", True)
    monkeypatch.setattr(settings, "trusted_proxy_count", 1)


def test_header_ignored_by_default(monkeypatch):
    monkeypatch.setattr(settings, "trust_proxy_headers", False)
    assert ratelimit.client_ip(req(xff="1.2.3.4, 5.6.7.8")) == "10.0.0.1"


def test_forged_leftmost_entry_ignored(trusted, monkeypatch):
    assert ratelimit.client_ip(req(xff="1.2.3.4, 5.6.7.8")) == "5.6.7.8"
    monkeypatch.setattr(settings, "trusted_proxy_count", 2)
    assert ratelimit.client_ip(req(xff="1.2.3.4, 5.6.7.8")) == "1.2.3.4"


def test_fewer_entries_than_count_falls_back_to_peer(trusted, monkeypatch):
    monkeypatch.setattr(settings, "trusted_proxy_count", 3)
    assert ratelimit.client_ip(req(xff="1.2.3.4, 5.6.7.8")) == "10.0.0.1"


@pytest.mark.parametrize("xff", ["garbage", "", " , ", "1.2.3.4, not-an-ip", "999.1.1.1", "<script>"])
def test_garbage_header_falls_back_to_peer(trusted, xff):
    assert ratelimit.client_ip(req(xff=xff)) == "10.0.0.1"


def test_v4_mapped_equals_plain_v4(trusted, monkeypatch):
    assert ratelimit.client_ip(req(xff="::ffff:203.0.113.5")) == ratelimit.client_ip(req(xff="203.0.113.5")) \
        == "203.0.113.5"
    monkeypatch.setattr(settings, "trust_proxy_headers", False)
    assert ratelimit.client_ip(req(peer="::ffff:203.0.113.5")) == "203.0.113.5"


def test_ipv6_collapsed_to_slash_64(trusted):
    a = ratelimit.client_ip(req(xff="2001:db8:1:2::1"))
    b = ratelimit.client_ip(req(xff="2001:db8:1:2:ffff:eeee:dddd:cccc"))
    c = ratelimit.client_ip(req(xff="2001:db8:1:3::1"))
    assert a == b == "2001:db8:1:2::/64" and c != a


def test_peer_garbage_or_missing():
    assert ratelimit.client_ip(req(peer="testclient")) == "testclient"
    assert ratelimit.client_ip(req(peer=None)) == "unknown"


def test_default_trusted_proxy_count_is_one():
    from app.config import Settings
    s = Settings(database_url="postgresql://x/y")
    assert s.trusted_proxy_count == 1 and s.trust_proxy_headers is False


def test_public_peer_cannot_forge_forwarded_for(trusted):
    assert ratelimit.client_ip(req(peer="203.0.113.9", xff="6.6.6.6")) == "203.0.113.9"
    assert ratelimit.client_ip(req(peer="2001:db8::1", xff="6.6.6.6")) == "2001:db8::/64"
    assert ratelimit.client_ip(req(peer="::ffff:203.0.113.9", xff="6.6.6.6")) == "203.0.113.9"


@pytest.mark.parametrize("peer", ["127.0.0.1", "::1", "10.1.2.3", "172.18.0.1", "192.168.1.5", "::ffff:127.0.0.1"])
def test_private_or_loopback_peer_xff_is_honoured(trusted, peer):
    # docker bridge scenario: a NAT'd loopback connection appears from the bridge gateway, e.g. 172.18.0.1
    assert ratelimit.client_ip(req(peer=peer, xff="1.2.3.4, 5.6.7.8")) == "5.6.7.8"


def test_custom_cidrs(trusted, monkeypatch):
    monkeypatch.setattr(settings, "trusted_proxy_cidrs", "203.0.113.0/24")
    assert ratelimit.client_ip(req(peer="203.0.113.9", xff="5.6.7.8")) == "5.6.7.8"
    assert ratelimit.client_ip(req(peer="10.0.0.1", xff="5.6.7.8")) == "10.0.0.1"


def test_non_ip_peer_is_not_trusted(trusted):
    assert ratelimit.client_ip(req(peer="testclient", xff="5.6.7.8")) == "testclient"


def test_invalid_cidr_is_a_clear_startup_error():
    from pydantic import ValidationError

    from app.config import Settings
    with pytest.raises(ValidationError, match="TRUSTED_PROXY_CIDRS.*not-a-cidr"):
        Settings(database_url="postgresql://x/y", trusted_proxy_cidrs="10.0.0.0/8,not-a-cidr")


def test_default_cidrs_cover_loopback_and_private():
    from app.config import Settings
    s = Settings(database_url="postgresql://x/y")
    assert s.trusted_proxy_cidrs.split(",") == ["127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]
