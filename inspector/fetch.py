"""Pinned, bounded HTTP fetcher.

- Uses httpcore directly with a network backend that can ONLY connect to the IP validated for
  the (host, port) of the current hop. TLS SNI and certificate checks still use the hostname.
- Redirects (HTTP 3xx and short meta refresh) are followed manually, max 5, and every hop is
  re-parsed, re-resolved and re-validated.
- No cookies, no credentials, no proxy (httpcore ignores proxy env vars), identity encoding.
- Body capped at 2 MB of DECOMPRESSED bytes (gzip/deflate handled with a bounded decompressor),
  raw bytes capped too; per-read idle timeout; downloads are sniffed (<= 4 KB) and aborted.
- Nothing is written to disk.
"""

from __future__ import annotations

import re
import ssl
import zlib
from dataclasses import dataclass, field
from typing import Callable
from urllib.parse import unquote, urljoin, urlsplit

import certifi
import httpcore

from detect import analyze_html, decode_body
from guard import Blocked, Policy, Resolver, Target, default_resolver, resolve_pinned

MAX_BODY = 2 * 1024 * 1024
MAX_RAW = 4 * 1024 * 1024
SNIFF = 4096
MAX_HOPS = 5
MAX_LOCATION = 2048
_REDIRECTS = frozenset({301, 302, 303, 307, 308})
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0.0.0 Mobile Safari/537.36")
_HEADERS = [(b"User-Agent", UA.encode()),
            (b"Accept", b"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            (b"Accept-Language", b"en-IN,en;q=0.9,hi;q=0.8"),
            (b"Accept-Encoding", b"gzip, deflate"),  # never br/zstd: we must be able to decode
            (b"Connection", b"close")]
APK_TYPES = frozenset({"application/vnd.android.package-archive"})
DOWNLOAD_TYPES = APK_TYPES | frozenset({
    "application/octet-stream", "application/zip", "application/x-zip-compressed", "application/java-archive",
    "application/x-msdownload", "application/x-msdos-program", "application/vnd.microsoft.portable-executable",
    "application/x-rar-compressed", "application/vnd.rar", "application/x-7z-compressed",
    "application/x-apple-diskimage", "application/x-msi", "application/x-sh", "application/x-executable",
    "application/x-elf", "application/x-iso9660-image", "application/gzip", "application/x-tar"})
APK_EXTS = (".apk", ".xapk", ".apks", ".apkm")
DOWNLOAD_EXTS = APK_EXTS + (".exe", ".msi", ".zip", ".rar", ".7z", ".dmg", ".jar", ".bat", ".scr",
                            ".cmd", ".ps1", ".vbs", ".iso", ".bin", ".sh")
_FILENAME = re.compile(r"filename\*?\s*=\s*(?:[\w-]+'[\w-]*')?\"?([^\";]{1,255})", re.I)


def _ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context(cafile=certifi.where())
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    return ctx


_SSL = _ssl_context()


class PinnedBackend(httpcore.AsyncNetworkBackend):
    """Network backend that maps (host, port) -> pre-validated IP and refuses anything else."""

    def __init__(self, pins: dict[tuple[str, int], str], inner=None,
                 on_connect: Callable[[str], None] | None = None):
        self._pins = {(h.lower(), p): ip for (h, p), ip in pins.items()}
        self._inner = inner or httpcore.AnyIOBackend()
        self._on_connect = on_connect

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        ip = self._pins.get((str(host).lower(), port))
        if ip is None:
            raise httpcore.ConnectError("connection to an unpinned host refused")
        if self._on_connect:
            self._on_connect(ip)
        return await self._inner.connect_tcp(ip, port, timeout=timeout, local_address=local_address,
                                             socket_options=socket_options)

    async def connect_unix_socket(self, path, timeout=None, socket_options=None):
        raise httpcore.ConnectError("unix sockets are disabled")

    async def sleep(self, seconds):
        await self._inner.sleep(seconds)


@dataclass
class FetchResult:
    ok: bool = False
    chain: list[str] = field(default_factory=list)
    final_url: str = ""
    status: int | None = None
    content_type: str = ""
    body: bytes = b""
    truncated: bool = False
    triggers_download: bool = False
    apk_download: bool = False
    undecodable: bool = False  # body could not be decoded: page content unknown (not "clean")
    error: str | None = None


def _blocked_label(url: object) -> str:
    """Safe chain entry for a hop that failed parsing: never echo the attacker's Location."""
    try:
        scheme = urlsplit(url).scheme.lower() if isinstance(url, str) else ""
    except ValueError:
        scheme = ""
    return "<blocked: host>" if scheme in ("http", "https") else "<blocked: scheme>"


def _header(headers, name: bytes) -> str:
    for k, v in headers:
        if k.lower() == name:
            return v.decode("latin-1")[:4096]
    return ""


def apk_magic(sniff: bytes) -> bool:
    return sniff.startswith(b"PK\x03\x04") and (b"AndroidManifest.xml" in sniff or b"classes.dex" in sniff)


def _download_info(t: Target, headers) -> tuple[bool, bool]:
    """(is_download, looks_like_apk) from headers and URL alone."""
    ctype = _header(headers, b"content-type").split(";")[0].strip().lower()
    cd = _header(headers, b"content-disposition")
    m = _FILENAME.search(cd)
    fname = unquote(m.group(1)).strip().lower() if m else ""
    path = unquote(urlsplit(t.url).path).lower()
    apk = ctype in APK_TYPES or path.endswith(APK_EXTS) or fname.endswith(APK_EXTS)
    dl = (apk or "attachment" in cd.lower() or ctype in DOWNLOAD_TYPES
          or path.endswith(DOWNLOAD_EXTS) or fname.endswith(DOWNLOAD_EXTS))
    return dl, apk


async def _read_body(resp, headers, max_bytes: int) -> tuple[bytes, bool, bool]:
    """-> (body, truncated, decodable). Undecodable (unsupported/stacked encoding, zlib error)
    returns decodable=False so the caller never mistakes it for a clean empty page."""
    enc = _header(headers, b"content-encoding").strip().lower()
    if enc in ("gzip", "x-gzip"):
        mode, dec = "gzip", zlib.decompressobj(16 + zlib.MAX_WBITS)
    elif enc == "deflate":
        mode, dec = "deflate", None  # zlib-wrapped or raw: decided from the first two bytes
    elif enc in ("", "identity"):
        mode, dec = None, None
    else:
        return b"", False, False
    buf, raw, pending = bytearray(), 0, b""
    async for chunk in resp.aiter_stream():
        raw += len(chunk)
        if mode == "deflate" and dec is None:
            pending += chunk
            if len(pending) < 2:
                continue
            zlib_hdr = (pending[0] & 0x0F) == 8 and ((pending[0] << 8) | pending[1]) % 31 == 0
            dec = zlib.decompressobj(zlib.MAX_WBITS if zlib_hdr else -zlib.MAX_WBITS)
            chunk, pending = pending, b""
        if dec is not None:
            try:
                buf += dec.decompress(chunk, max_bytes - len(buf) + 1)
            except zlib.error:
                return b"", False, False
        else:
            buf += chunk
        if len(buf) > max_bytes or raw > MAX_RAW:
            return bytes(buf[:max_bytes]), True, True
    if mode == "deflate" and dec is None and pending:
        return b"", False, False
    return bytes(buf), False, True


async def _sniff(resp) -> bytes:
    buf = bytearray()
    async for chunk in resp.aiter_stream():
        buf += chunk[: SNIFF - len(buf)]
        if len(buf) >= SNIFF:
            break
    return bytes(buf)


def _is_html(ctype: str, body: bytes) -> bool:
    if ctype in ("text/html", "application/xhtml+xml"):
        return True
    return ctype in ("", "text/plain") and body.lstrip()[:1] == b"<"


async def fetch(url: str, *, resolver: Resolver = default_resolver, policy: Policy = Policy(),
                max_hops: int = MAX_HOPS, max_bytes: int = MAX_BODY, read_timeout: float = 4.0,
                connect_timeout: float = 4.0, ssl_context: ssl.SSLContext | None = None,
                on_connect: Callable[[str], None] | None = None,
                result: FetchResult | None = None) -> FetchResult:
    """Fetch `url` safely. Never raises for network/policy problems (returns ok=False with
    `error`); cancellation/timeouts from an enclosing asyncio.timeout propagate. Pass `result` to
    observe progress (chain) even if the caller's deadline cancels this coroutine."""
    res = result or FetchResult()
    res.final_url = url if isinstance(url, str) else ""
    timeouts = {"connect": connect_timeout, "read": read_timeout, "write": read_timeout,
                "pool": connect_timeout}
    current, hops = url, 0
    meta_prev: dict | None = None  # page we left via meta refresh (restored if the target is blocked)

    def back_to_meta_page() -> FetchResult:
        # real browsers ignore javascript:/data:/unreachable refresh targets: keep the page we had
        for k, v in meta_prev.items():
            setattr(res, k, v)
        res.ok, res.error = True, None
        return res

    while True:
        try:
            t = policy.parse(current)
        except Blocked as e:
            if meta_prev is not None:
                return back_to_meta_page()
            res.chain.append(_blocked_label(current))
            res.error = f"blocked: {e}"
            return res
        res.chain.append(t.url)
        try:
            ip = await resolve_pinned(t, resolver, policy)
        except Blocked as e:
            if meta_prev is not None:
                res.chain.pop()
                return back_to_meta_page()
            res.final_url = t.url
            res.error = f"blocked: {e}"
            return res
        res.final_url = t.url

        nxt: str | None = None
        via_meta = False
        try:
            pool = httpcore.AsyncConnectionPool(
                ssl_context=ssl_context or _SSL, max_connections=1, http1=True, http2=False, retries=0,
                network_backend=PinnedBackend({(t.host, t.port): ip}, on_connect=on_connect))
            async with pool:
                async with pool.stream("GET", t.url, headers=[(b"Host", t.netloc.encode()), *_HEADERS],
                                       extensions={"timeout": timeouts}) as resp:
                    headers = resp.headers
                    loc = _header(headers, b"location").strip()
                    if resp.status in _REDIRECTS and loc:
                        if len(loc) > MAX_LOCATION:
                            res.error = "bad redirect"
                            return res
                        nxt = urljoin(t.url, loc)
                    else:
                        res.status = resp.status
                        res.content_type = _header(headers, b"content-type").split(";")[0].strip().lower()
                        dl, apk = _download_info(t, headers)
                        if dl:
                            res.body = await _sniff(resp)
                            res.triggers_download = True
                            res.apk_download = apk or apk_magic(res.body)
                        else:
                            res.body, res.truncated, ok = await _read_body(resp, headers, max_bytes)
                            res.undecodable = not ok
                            if res.undecodable:
                                pass
                            elif apk_magic(res.body[:SNIFF]):
                                res.triggers_download = res.apk_download = True
                                res.body = res.body[:SNIFF]
                            elif (_is_html(res.content_type, res.body) and 200 <= resp.status < 300
                                  and hops < max_hops):
                                facts = analyze_html(decode_body(res.body, _header(headers, b"content-type")))
                                if facts.meta_refresh:
                                    nxt, via_meta = urljoin(t.url, facts.meta_refresh), True
        except httpcore.TimeoutException:
            res.error = "timeout"
            return res
        except (httpcore.ProtocolError, httpcore.NetworkError, httpcore.UnsupportedProtocol,
                ssl.SSLError, OSError) as e:
            res.error = f"network: {type(e).__name__}"
            return res
        except Exception as e:  # never let a parser/protocol surprise escape
            res.error = f"error: {type(e).__name__}"
            return res

        if nxt is None:
            res.ok = True
            return res
        if hops >= max_hops:
            res.error = "too many redirects"
            return res
        hops += 1
        meta_prev = ({k: getattr(res, k) for k in ("status", "content_type", "body", "truncated",
                                                   "final_url", "undecodable")} if via_meta else None)
        res.status, res.content_type, res.body, res.truncated = None, "", b"", False
        current = nxt


__all__ = ["fetch", "FetchResult", "PinnedBackend", "MAX_BODY", "UA", "_is_html", "apk_magic"]
