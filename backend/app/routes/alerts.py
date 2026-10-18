from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_session
from app.models import Alert, Assessment, User
from app.pipeline.orchestrator import alert_out
from app.schemas import AlertOut

router = APIRouter()
MAX_ALERTS = 100


@router.get("/v1/alerts", response_model=list[AlertOut])
def list_alerts(since: datetime | None = None, user: User = Depends(current_user),
                db: Session = Depends(get_session)):
    """Alerts that were shown to this user (shown after `since` if given), newest first, max 100."""
    q = (select(Alert, Assessment).join(Assessment, Assessment.id == Alert.assessment_id)
         .where(Alert.user_id == user.id, Assessment.user_id == user.id, Alert.shown_at.is_not(None)))
    if since is not None:
        q = q.where(Alert.shown_at > (since if since.tzinfo else since.replace(tzinfo=timezone.utc)))
    rows = db.execute(q.order_by(Alert.shown_at.desc(), Alert.id.desc()).limit(MAX_ALERTS)).all()
    return [alert_out(a, s) for a, s in rows]
