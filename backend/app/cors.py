"""Dev-only CORS allow-list for a browser test console. Off by default (no middleware at all)."""
from urllib.parse import urlsplit

from fastapi import FastAPI
from starlette.middleware.cors import CORSMiddleware

DEV_ENVS = {"dev", "test"}


def parse_cors_origins(raw: str, environment: str) -> list[str]:
    """Comma-separated origins -> validated list. Empty -> []. Raises ValueError on unsafe values."""
    origins = [o.strip().rstrip("/") for o in raw.split(",") if o.strip()]
    dev = environment in DEV_ENVS
    for o in origins:
        if o == "*":
            if not dev:
                raise ValueError("CORS_ALLOW_ORIGINS must not contain '*' outside ENVIRONMENT=dev/test")
            continue
        u = urlsplit(o)
        if u.scheme not in ("http", "https") or not u.netloc or u.path or u.query or u.fragment:
            raise ValueError(f"CORS_ALLOW_ORIGINS entry {o!r} must look like https://host[:port]")
        if not dev and u.scheme != "https":
            raise ValueError(f"CORS_ALLOW_ORIGINS entry {o!r} must be https outside ENVIRONMENT=dev/test")
    return origins


def apply_cors(app: FastAPI, raw: str, environment: str) -> list[str]:
    origins = parse_cors_origins(raw, environment)
    if origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type"],
            allow_credentials=False,
            expose_headers=[],
            max_age=600,
        )
    return origins
