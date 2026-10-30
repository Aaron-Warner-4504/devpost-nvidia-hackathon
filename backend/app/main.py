import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app import family, push
from app.config import docs_are_enabled, settings
from app.cors import apply_cors
from app.db_url import check_database_url
from app.pipeline import orchestrator
from app.routes import alerts, dashboard, events, me
from app.routes import family as family_routes


@asynccontextmanager
async def lifespan(_: FastAPI):
    family.check_pepper()  # fail fast in prod without a pairing-code pepper
    check_database_url(settings.database_url, settings.environment)  # prod: TLS to a remote DB
    # alert hooks are registered at startup (not import time) so tests can inject their own
    orchestrator.register_alert_hook(family.family_alert_hook)
    orchestrator.register_alert_hook(push.push_alert_hook)
    _start_review()
    try:
        yield
    finally:
        for hook in (family.family_alert_hook, push.push_alert_hook):
            if hook in orchestrator.alert_hooks:
                orchestrator.alert_hooks.remove(hook)


def _start_review() -> None:
    """Stage-2 worker pool from settings; a periodic sweeper submits jobs that did not fit the queue or
    that a crash / restart left behind (background thread, also runs once at startup)."""
    from datetime import timedelta

    from app.db import SessionLocal
    from app.pipeline import review
    review.configure(workers=settings.review_workers, queue_size=settings.review_queue_size,
                     max_per_user=settings.review_max_queued_per_user)
    if not review.sweeper_needed(settings) or settings.environment == "test":
        return
    period = timedelta(minutes=max(1, settings.review_sweep_minutes))
    review.start_sweeper(SessionLocal, interval=period, older_than=period)


# /docs, /redoc and /openapi.json are off in prod by default (see DOCS_ENABLED); nothing in the app uses them.
_docs_kw = {} if docs_are_enabled(settings) else {"docs_url": None, "redoc_url": None, "openapi_url": None}
app = FastAPI(title="Sentinel API", version="0.1.0", lifespan=lifespan,
              root_path=settings.root_path.strip().rstrip("/"), **_docs_kw)
app.include_router(me.router)
app.include_router(events.router)
app.include_router(alerts.router)
app.include_router(family_routes.router)
app.include_router(dashboard.router)
apply_cors(app, settings.cors_allow_origins, settings.environment)  # invalid value -> refuses to start
log = logging.getLogger("app")


@app.exception_handler(Exception)
async def internal_error(request: Request, exc: Exception):
    """Unexpected failures (e.g. the database is down): a fixed JSON 500 with no internals. Only the
    exception type is logged: messages can echo SQL parameters or message text."""
    log.error("unhandled %s on %s %s", type(exc).__name__, request.method, request.url.path)
    return JSONResponse(status_code=500, content={"detail": "internal error"})


@app.get("/health")
def health():
    return {"status": "ok"}
