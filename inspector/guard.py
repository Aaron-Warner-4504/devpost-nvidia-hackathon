"""SSRF guard: strict URL parsing, address classification and pinned resolution.

Every hop (first URL, each redirect, each meta refresh) goes through `Policy.parse` and
`resolve_pinned`. The resolved IP is then the ONLY address the HTTP client may connect to
(see fetch.PinnedBackend), so DNS rebinding between check and connect is impossible.

There is deliberately no environment variable or request field that relaxes any check. The only
exception is `Policy(test_allow=...)`, a set of exact (ip, port) pairs that can be passed in code
(create_app's private `_test_allow` parameter) so tests can reach a server on 127.0.0.1.
"""

from __future__ import annotations

import asyncio
import ipaddress
import re
import socket
from dataclasses import dataclass, field
from typing import Awaitable, Callable
from urllib.parse import quote, urlsplit

import idna

ALLOWED_PORTS = frozenset({80, 443, 8080, 8443})
MAX_URL = 2048
MAX_ADDRS = 32
_DEFAULT_PORT = {"http": 80, "https": 443}
_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
_TLD = re.compile(r"[a-z]{2,63}|xn--[a-z0-9-]{1,59}")
# Special-use / internal names that must never be fetched, whatever DNS says.
_BLOCKED_TLDS = frozenset({"localhost", "local", "internal", "intranet", "lan", "home", "corp",
                           "private", "localdomain", "test", "example", "invalid", "onion", "arpa",
                           "alt", "kubernetes", "cluster", "svc"})
_BLOCKED_SUFFIXES = (".svc.cluster.local", ".compute.internal", ".ec2.internal")
_NAT64 = (ipaddress.ip_network("64:ff9b::/96"), ipaddress.ip_network("64:ff9b:1::/48"))
_PATH_SAFE = "/%:@!$&'()*+,;=-._~"
_QUERY_SAFE = _PATH_SAFE + "?"

Resolver = Callable[[str, int], Awaitable[list[str]]]


class Blocked(Exception):
    """The URL or one of its addresses is not allowed."""


@dataclass(frozen=True)
class Target:
    url: str      # normalised URL (no userinfo, no fragment, IDNA host, encoded path)
    scheme: str
    host: str     # lower-case ASCII hostname, never an IP literal
    port: int
    target: str   # request target: path + query

    @property
    def netloc(self) -> str:
        return self.host if self.port == _DEFAULT_PORT[self.scheme] else f"{self.host}:{self.port}"


# ------------------------------------------------------------------ addresses
def _embedded_v4(a: ipaddress.IPv6Address) -> list[ipaddress.IPv4Address]:
    out: list[ipaddress.IPv4Address] = []
    if a.ipv4_mapped:
        out.append(a.ipv4_mapped)
    if a.sixtofour:
        out.append(a.sixtofour)
    if a.teredo:
        out.extend(a.teredo)
    if any(a in n for n in _NAT64) or int(a) >> 32 == 0:  # NAT64 / IPv4-compatible ::a.b.c.d
        out.append(ipaddress.IPv4Address(int(a) & 0xFFFFFFFF))
    return out


def is_public_ip(ip: object) -> bool:
    """True only for a globally routable unicast address (and, for IPv6 forms that embed an
    IPv4 address, only if the embedded address is global too)."""
    if not isinstance(ip, str) or not ip or ip.strip() != ip:
        return False
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if isinstance(a, ipaddress.IPv6Address):
        if a.scope_id:
            return False
        if any(not _v4_ok(v) for v in _embedded_v4(a)):
            return False
        if a.ipv4_mapped or a.sixtofour or a.teredo:
            return False  # transition forms are never needed to fetch a web page
    return _base_ok(a)


def _v4_ok(a: ipaddress.IPv4Address) -> bool:
    return _base_ok(a)


def _base_ok(a: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return bool(a.is_global and not (a.is_private or a.is_loopback or a.is_link_local
                                     or a.is_multicast or a.is_reserved or a.is_unspecified
                                     or getattr(a, "is_site_local", False)))


# ------------------------------------------------------------------ URLs
def _host(raw: str) -> str:
    if not raw or raw.startswith("[") or "%" in raw or "\\" in raw:
        raise Blocked("bad host")
    host = raw[:-1] if raw.endswith(".") else raw
    if not host.isascii():
        # UTS46 non-transitional (as browsers do): eß.com -> xn--e-qfa.com; also maps fullwidth
        # digits, ideographic dots and circled letters, so those spellings hit the checks below
        try:
            host = idna.encode(host, uts46=True, transitional=False).decode("ascii")
        except (idna.IDNAError, UnicodeError, ValueError):
            raise Blocked("bad idn host") from None
        host = host[:-1] if host.endswith(".") else host
    host = host.lower()
    if len(host) > 253:
        raise Blocked("host too long")
    try:
        ipaddress.ip_address(host)
        raise Blocked("ip literal")
    except ValueError:
        pass
    labels = host.split(".")
    if len(labels) < 2 or not all(_LABEL.fullmatch(x) for x in labels):
        raise Blocked("bad host syntax")
    if not _TLD.fullmatch(labels[-1]):
        raise Blocked("bad tld")  # also rejects every numeric / hex / octal IP spelling
    if labels[-1] in _BLOCKED_TLDS or host.endswith(_BLOCKED_SUFFIXES):
        raise Blocked("internal name")
    return host


def parse_url(url: object, allowed_ports: frozenset[int] = ALLOWED_PORTS) -> Target:
    if not isinstance(url, str) or not url or len(url) > MAX_URL:
        raise Blocked("bad url")
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in url) or "\\" in url:
        raise Blocked("control characters")
    try:
        sp = urlsplit(url.strip(" "))
    except ValueError:
        raise Blocked("unparseable") from None
    scheme = sp.scheme.lower()
    if scheme not in _DEFAULT_PORT:
        raise Blocked("scheme")
    netloc = sp.netloc
    if not netloc or "@" in netloc or " " in netloc:
        raise Blocked("authority")
    hostpart, sep, portpart = netloc.rpartition(":") if not netloc.startswith("[") else (netloc, "", "")
    if not sep:
        hostpart, portpart = netloc, ""
    if sep and not (portpart.isdigit() and portpart.isascii() and len(portpart) <= 5):
        raise Blocked("port")
    port = int(portpart) if portpart else _DEFAULT_PORT[scheme]
    if port not in allowed_ports:
        raise Blocked("port not allowed")
    host = _host(hostpart)
    path = quote(sp.path or "/", safe=_PATH_SAFE)
    if not path.startswith("/"):
        path = "/" + path
    target = path + ("?" + quote(sp.query, safe=_QUERY_SAFE) if sp.query else "")
    netloc_out = host if port == _DEFAULT_PORT[scheme] else f"{host}:{port}"
    norm = f"{scheme}://{netloc_out}{target}"
    if len(norm) > MAX_URL:
        raise Blocked("url too long")
    return Target(norm, scheme, host, port, target)


# ------------------------------------------------------------------ policy
@dataclass(frozen=True)
class Policy:
    """Address policy. `test_allow` holds exact (ip, port) pairs that tests may reach; it is
    empty unless passed in code and is never read from the environment."""
    test_allow: frozenset[tuple[str, int]] = field(default_factory=frozenset)

    def parse(self, url: object) -> Target:
        return parse_url(url, ALLOWED_PORTS | {p for _, p in self.test_allow})

    def allows(self, ip: str, port: int) -> bool:
        if (ip, port) in self.test_allow:
            return True
        return port in ALLOWED_PORTS and is_public_ip(ip)


async def default_resolver(host: str, port: int) -> list[str]:
    infos = await asyncio.get_running_loop().getaddrinfo(
        host, port, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
    return [str(i[4][0]) for i in infos]


async def resolve_pinned(t: Target, resolver: Resolver, policy: Policy) -> str:
    """Resolve once, require EVERY answer to be allowed, and return the single IP to pin."""
    try:
        addrs = await resolver(t.host, t.port)
    except Exception:
        raise Blocked("dns failure") from None
    if not isinstance(addrs, (list, tuple)) or not addrs:
        raise Blocked("no addresses")
    addrs = list(dict.fromkeys(addrs))[:MAX_ADDRS]
    for a in addrs:
        if not isinstance(a, str) or not policy.allows(a, t.port):
            raise Blocked("non-public address")
    v4 = [a for a in addrs if ":" not in a]
    return (v4 or addrs)[0]
