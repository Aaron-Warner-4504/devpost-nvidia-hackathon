import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app import family, push
from app.pipeline import orchestrator
from app.routes import alerts, events, me
from app.routes import family as family_routes


@asynccontextmanager
async def lifespan(_: FastAPI):
    family.check_pepper()  # fail fast in prod without a pairing-code pepper
    # alert hooks are registered at startup (not import time) so tests can inject their own
    orchestrator.register_alert_hook(family.family_alert_hook)
    orchestrator.register_alert_hook(push.push_alert_hook)
    try:
        yield
    finally:
        for hook in (family.family_alert_hook, push.push_alert_hook):
            if hook in orchestrator.alert_hooks:
                orchestrator.alert_hooks.remove(hook)


app = FastAPI(title="Sentinel API", version="0.1.0", lifespan=lifespan)
app.include_router(me.router)
app.include_router(events.router)
app.include_router(alerts.router)
app.include_router(family_routes.router)
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
