import hashlib
import secrets

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import get_session
from app.models import Device, User


def new_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def current_user(request: Request, session: Session = Depends(get_session)) -> User:
    scheme, _, token = (request.headers.get("authorization") or "").partition(" ")
    token = token.strip()
    if scheme.lower() == "bearer" and token:
        user = session.scalar(
            select(User).join(Device, Device.user_id == User.id)
            .where(Device.token_hash == hash_token(token))
        )
        if user is not None:
            return user
    raise HTTPException(status_code=401, detail="missing or invalid bearer token")
