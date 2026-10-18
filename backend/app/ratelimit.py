"""DB-backed rate limits for unauthenticated endpoints (shared by every API process).

Keys are sha256 hashes of `kind:value` (e.g. the client IP); raw IPs are never stored. Rows older
than 24 h are purged on the same write path, so the table stays small without a scheduler.
"""

from __future__ import annotations

import hashlib
import ipaddress
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException, Request
from sqlalchemy import delete, func, select, text
from sqlalchemy.orm import Session

from app.config import settings
from app.models import RateLimitEvent

RETENTION = timedelta(hours=24)
TOO_MANY = "too many requests, try again later"


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def normalize_ip(raw: str | None) -> str | None:
    """Canonical rate-limit identity for an address: IPv4-mapped IPv6 -> IPv4, IPv6 -> its /64 network
    (one routed /64 must not mint unlimited keys). None if `raw` is not an IP address."""
    try:
        ip = ipaddress.ip_address((raw or "").strip())
    except ValueError:
        return None
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return str(ip.ipv4_mapped)
        return str(ipaddress.IPv6Network((ip, 64), strict=False))
    return str(ip)


def _peer(request: Request) -> str:
    host = request.client.host if request.client else None
    if not host:
        return "unknown"
    return normalize_ip(host) or host  # non-IP peers (e.g. the test client) are used as-is


def client_ip(request: Request) -> str:
    """The socket peer by default. With TRUST_PROXY_HEADERS, the X-Forwarded-For entry TRUSTED_PROXY_COUNT
    positions from the right (the one our proxy appended; anything further left is client-controlled).
    Too few entries or a non-IP entry -> the socket peer."""
    if settings.trust_proxy_headers:
        raw = request.headers.get("x-forwarded-for")
        n = settings.trusted_proxy_count
        if raw is not None and n >= 1:
            parts = [p.strip() for p in raw.split(",")]
            if len(parts) >= n:
                ip = normalize_ip(parts[-n])
                if ip:
                    return ip
    return _peer(request)


def hit(db: Session, kind: str, value: str, *, limit: int, window: timedelta) -> None:
    """Record one event for (kind, value); raise 429 (without recording) once `limit` events fall in the
    rolling window. Commits."""
    now = utcnow()
    key = hashlib.sha256(f"{kind}:{value}".encode()).hexdigest()
    db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:k))"), {"k": f"ratelimit:{key}"})
    db.execute(delete(RateLimitEvent).where(RateLimitEvent.created_at <= now - RETENTION))
    n = db.scalar(select(func.count()).select_from(RateLimitEvent).where(
        RateLimitEvent.kind == kind, RateLimitEvent.key == key, RateLimitEvent.created_at > now - window)) or 0
    if n >= limit:
        db.commit()
        raise HTTPException(status_code=429, detail=TOO_MANY)
    db.add(RateLimitEvent(kind=kind, key=key, created_at=now))
    db.commit()
