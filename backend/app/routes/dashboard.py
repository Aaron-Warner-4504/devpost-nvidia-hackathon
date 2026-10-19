"""Demo web dashboard (Task 16): a static page at /dashboard plus GET /v1/dashboard/timeline.

Both exist only when DEMO_ENABLED=true (default false -> 404). The timeline endpoint has no `user`
parameter: it returns the CALLER's own data, so the dashboard shows the demo user only to whoever holds
the demo user's token. Message text is attacker-controlled: the page renders it with textContent only."""
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.auth import current_user
from app.config import settings
from app.db import get_session
from app.models import Alert, Assessment, Edge, Entity, Event, EventEntity, User
from app.pipeline.orchestrator import alert_out

router = APIRouter()
STATIC = Path(__file__).resolve().parent.parent / "static"
MAX_EVENTS, MAX_ENTITIES, MAX_EDGES, MAX_ALERTS = 200, 500, 1000, 100

SECURITY_HEADERS = {
    "Content-Security-Policy": ("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                                "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
                                "frame-ancestors 'none'"),
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}


def demo_only() -> None:
    if not settings.demo_enabled:
        raise HTTPException(status_code=404, detail="not found")


def _asset(name: str, media_type: str) -> FileResponse:
    return FileResponse(STATIC / name, media_type=media_type, headers=SECURITY_HEADERS)


@router.get("/dashboard", include_in_schema=False, dependencies=[Depends(demo_only)])
def dashboard_page():
    return _asset("dashboard.html", "text/html; charset=utf-8")


@router.get("/dashboard/dashboard.js", include_in_schema=False, dependencies=[Depends(demo_only)])
def dashboard_js():
    return _asset("dashboard.js", "text/javascript; charset=utf-8")


@router.get("/dashboard/dashboard.css", include_in_schema=False, dependencies=[Depends(demo_only)])
def dashboard_css():
    return _asset("dashboard.css", "text/css; charset=utf-8")


@router.get("/v1/dashboard/timeline", dependencies=[Depends(demo_only)])
def timeline(user: User = Depends(current_user), db: Session = Depends(get_session)):
    """The caller's events (oldest first, newest 200), entities, edges and shown alerts (newest first)."""
    events = list(reversed(db.scalars(
        select(Event).where(Event.user_id == user.id).order_by(Event.ts.desc(), Event.id.desc())
        .limit(MAX_EVENTS)).all()))
    ev_ids = [e.id for e in events]
    links = db.execute(select(EventEntity.event_id, EventEntity.entity_id)
                       .where(EventEntity.user_id == user.id, EventEntity.event_id.in_(ev_ids))).all() \
        if ev_ids else []
    by_event: dict[str, list[str]] = {}
    for event_id, entity_id in links:
        by_event.setdefault(event_id, []).append(entity_id)

    entities = db.scalars(select(Entity).where(Entity.user_id == user.id)
                          .order_by(Entity.type, Entity.canonical).limit(MAX_ENTITIES)).all()
    ent_ids = {e.id for e in entities}
    edges = db.scalars(select(Edge).where(Edge.user_id == user.id).order_by(Edge.valid_from, Edge.id)
                       .limit(MAX_EDGES)).all()

    rows = db.execute(
        select(Alert, Assessment).join(Assessment, Assessment.id == Alert.assessment_id)
        .where(Alert.user_id == user.id, Assessment.user_id == user.id, Alert.shown_at.is_not(None),
               or_(Alert.reason.is_(None), Alert.reason != "user_check", Assessment.tier != "info"))
        .order_by(Alert.shown_at.desc(), Alert.id.desc()).limit(MAX_ALERTS)).all()
    alerts = [alert_out(a, s) for a, s in rows]
    latest = alerts[0] if alerts else None

    return {
        "events": [{"id": e.id, "ts": e.ts, "source": e.source, "redacted_text": e.redacted_text,
                    "entities": [i for i in by_event.get(e.id, []) if i in ent_ids]} for e in events],
        "entities": [{"id": e.id, "type": e.type, "canonical": e.canonical} for e in entities],
        "edges": [{"a": g.a, "b": g.b, "relation": g.relation,
                   "evidence_event_ids": list(g.evidence_event_ids or [])}
                  for g in edges if g.a in ent_ids and g.b in ent_ids],
        "alerts": [{"id": a.id, "event_id": a.event_id, "tier": a.tier, "headline": a.headline,
                    "why": [w.model_dump(mode="json") for w in a.why], "signals": a.signals,
                    "created_at": a.created_at} for a in alerts],
        "latest": {"tier": latest.tier, "why": [w.text for w in latest.why]} if latest
        else {"tier": "info", "why": []},
    }
