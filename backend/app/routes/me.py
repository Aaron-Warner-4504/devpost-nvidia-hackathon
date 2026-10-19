from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import ratelimit
from app.auth import current_device, current_user, hash_token, new_token
from app.config import settings
from app.db import get_session
from app.models import Consent, Device, User
from app.schemas import DeviceRegistered, Source

router = APIRouter()


class ConsentIn(BaseModel):
    source: Source
    granted: bool


class FcmIn(BaseModel):
    token: str = Field(min_length=1, max_length=4096)


def _consents(session: Session, user_id: str) -> dict[str, bool]:
    rows = session.scalars(select(Consent).where(Consent.user_id == user_id)).all()
    return {c.source: c.revoked_at is None for c in rows}


@router.post("/v1/devices/register", response_model=DeviceRegistered)
def register_device(request: Request, session: Session = Depends(get_session)):
    """Unauthenticated: limited to REGISTER_PER_IP_PER_HOUR per client IP (429), which also bounds how many
    accounts one source can create to spread pairing-code guesses."""
    ratelimit.hit(session, "register", ratelimit.client_ip(request), limit=settings.register_per_ip_per_hour,
                  window=timedelta(hours=1))
    token = new_token()
    user = User()
    session.add(user)
    session.flush()
    device = Device(user_id=user.id, token_hash=hash_token(token))
    session.add(device)
    session.commit()
    return DeviceRegistered(device_id=device.id, token=token)


@router.post("/v1/devices/fcm")
def set_fcm_token(body: FcmIn, device: Device = Depends(current_device), session: Session = Depends(get_session)):
    """Store (or replace) this device's own push token; re-posting also clears an earlier invalid mark."""
    device.fcm_token = body.token
    device.fcm_invalid_at = None
    session.commit()
    return {"ok": True}


@router.get("/v1/me")
def get_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return {"id": user.id, "consents": _consents(session, user.id)}


@router.post("/v1/me/consents")
def set_consent(body: ConsentIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    now = datetime.now(timezone.utc)
    c = session.scalar(select(Consent).where(Consent.user_id == user.id, Consent.source == body.source))
    if c is None:
        c = Consent(user_id=user.id, source=body.source, granted_at=now)
        session.add(c)
    if body.granted:
        c.granted_at, c.revoked_at = now, None
    else:
        c.revoked_at = now
    session.commit()
    return {"source": body.source, "granted": body.granted}


@router.delete("/v1/me", status_code=204)
def delete_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    # DB-level ON DELETE CASCADE removes every user-owned row.
    session.execute(delete(User).where(User.id == user.id))
    session.commit()
