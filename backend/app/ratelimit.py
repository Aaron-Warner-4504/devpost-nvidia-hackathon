"""DB-backed rate limits for unauthenticated endpoints (shared by every API process).

Keys are sha256 hashes of `kind:value` (e.g. the client IP); raw IPs are never stored. Rows older
than 24 h are purged on the same write path, so the table stays small without a scheduler.
"""

from __future__ import annotations

import hashlib
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


def client_ip(request: Request) -> str:
    """The peer address; X-Forwarded-For (its first hop) only when TRUST_PROXY_HEADERS is set, because
    any client can send that header."""
    if settings.trust_proxy_headers:
        fwd = (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
        if fwd:
            return fwd
    return request.client.host if request.client else "unknown"


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
