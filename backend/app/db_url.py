"""DATABASE_URL validation. Messages never contain the URL or password."""
from __future__ import annotations

from sqlalchemy.engine import make_url

TLS_MODES = {"require", "verify-ca", "verify-full"}
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", ""}


def check_database_url(url: str | None, environment: str) -> None:
    """Raise ValueError if the URL is unusable; in prod a non-local host needs sslmode=require|verify-*."""
    if not url:
        raise ValueError("DATABASE_URL is not set")
    try:
        u = make_url(url)
    except Exception:
        raise ValueError("DATABASE_URL is not a valid URL") from None
    if u.get_backend_name() != "postgresql":
        raise ValueError("DATABASE_URL must be a postgresql:// URL (e.g. postgresql+psycopg://...)")
    if environment == "prod" and (u.host or "") not in LOCAL_HOSTS:
        mode = u.query.get("sslmode")
        if isinstance(mode, tuple):
            mode = mode[-1]
        if mode not in TLS_MODES:
            raise ValueError("DATABASE_URL must include sslmode=require, verify-ca or verify-full "
                             "when ENVIRONMENT=prod and the database host is not local")
