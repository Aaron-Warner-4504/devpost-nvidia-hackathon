"""Family alerts: push notifier abstraction, pairing-code helpers and the critical-alert family hook.

Roles: the PROTECTED user (the person Sentinel watches) creates a pairing invite with explicit consent;
a GUARDIAN (family member, running their own app install = their own user / device / push token) accepts
it. The resulting TrustedContact row is the only thing that lets alerts reach anyone but the user.

What a guardian receives (and nothing else): title "Sentinel alert", the alert HEADLINE (redacted again,
numbers / ids / links masked, max 140 chars) and data {"kind": "family_critical", "alert_tier": "critical"}.
Never the message text, sender, entities, why items, signals, ids or the protected user's name.

When: only for a shown, interrupting `critical` alert of the protected user (never caution / high /
info, never /v1/check results, never alerts suppressed or degraded by the alert policy), exactly one
push per guardian per alert, and at most FAMILY_PINGS_PER_HOUR pushes per guardian per rolling hour
(across everyone they watch; a slot is used even if delivery fails). A failing notifier never breaks
the pipeline or the other guardians; an unregistered token marks the link `fcm_invalid_at` and is
skipped until the guardian pairs again.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import os
import re
import secrets
import warnings
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from typing import Callable, Protocol

from sqlalchemy import delete, func, select, text, update
from sqlalchemy.orm import Session

from app.config import settings
from app.models import FamilyPing, TrustedContact, User  # noqa: F401  (User re-exported for callers)
from app.pipeline.redact import redact

log = logging.getLogger(__name__)

# ---------------------------------------------------------------- clock (monkeypatched in tests)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


# ---------------------------------------------------------------- notifier


class Notifier(Protocol):
    """Generic push sender (also used for the user's own device in Task 17). Never raises; returns
    whether the push was accepted for delivery."""

    def send(self, token: str, title: str, body: str, data: dict[str, str] | None = None) -> bool: ...


class NoopNotifier:
    """Used when no push credentials are configured: nothing is sent."""

    def send(self, token: str, title: str, body: str, data: dict[str, str] | None = None) -> bool:
        return False


class FakeNotifier:
    """Test double: records every attempt; tokens can be told to fail, raise or be unregistered."""

    def __init__(self) -> None:
        self.attempts: list[dict] = []
        self.sent: list[dict] = []
        self.fail_tokens: set[str] = set()
        self.raise_tokens: set[str] = set()
        self.invalid_tokens: set[str] = set()

    def send(self, token: str, title: str, body: str, data: dict[str, str] | None = None) -> bool:
        rec = {"token": token, "title": title, "body": body, "data": dict(data or {})}
        self.attempts.append(rec)
        if token in self.raise_tokens:
            raise RuntimeError("fake notifier failure")
        if token in self.fail_tokens or token in self.invalid_tokens:
            return False
        self.sent.append(rec)
        return True

    def token_invalid(self, token: str) -> bool:
        return token in self.invalid_tokens


_FCM_APP = "sentinel-push"
_MAX_REMEMBERED_INVALID = 1000


class FCMNotifier:
    """Firebase Cloud Messaging via firebase-admin (imported lazily). Errors never propagate: they
    return False, and an unregistered / foreign-project token is remembered so the caller can ask
    `token_invalid(token)` once. Tokens are never logged."""

    def __init__(self, credentials_file: str):
        import firebase_admin
        from firebase_admin import credentials

        try:
            self._app = firebase_admin.get_app(_FCM_APP)
        except ValueError:
            self._app = firebase_admin.initialize_app(credentials.Certificate(credentials_file), name=_FCM_APP)
        self._invalid: dict[str, bool] = {}

    def send(self, token: str, title: str, body: str, data: dict[str, str] | None = None) -> bool:
        try:
            from firebase_admin import messaging

            with warnings.catch_warnings():  # firebase-admin 7.7 prefers `fid`; Android hands us a
                warnings.simplefilter("ignore", DeprecationWarning)  # registration token, still valid
                msg = messaging.Message(
                    token=token,
                    notification=messaging.Notification(title=title, body=body),
                    data={str(k): str(v) for k, v in (data or {}).items()},
                    android=messaging.AndroidConfig(priority="high"),
                )
            messaging.send(msg, app=self._app)
            return True
        except Exception as exc:  # never raise into the pipeline
            if _unregistered(exc):
                if len(self._invalid) >= _MAX_REMEMBERED_INVALID:
                    self._invalid.clear()
                self._invalid[token] = True
            log.warning("push send failed (%s)", type(exc).__name__)
            return False

    def token_invalid(self, token: str) -> bool:
        return self._invalid.pop(token, False)


def _unregistered(exc: Exception) -> bool:
    try:
        from firebase_admin import messaging
    except Exception:
        return False
    return isinstance(exc, (messaging.UnregisteredError, messaging.SenderIdMismatchError))


@lru_cache(maxsize=1)
def get_notifier() -> Notifier:
    """Process-wide notifier: FCM when FIREBASE_CREDENTIALS_FILE points at a readable file, else a
    no-op (logged once). Tests pass their own notifier / factory instead."""
    path = settings.firebase_credentials_file
    if not path or not os.path.isfile(path):
        log.warning("FIREBASE_CREDENTIALS_FILE unset or missing: push notifications are disabled")
        return NoopNotifier()
    try:
        return FCMNotifier(path)
    except Exception as exc:
        log.error("firebase init failed (%s): push notifications are disabled", type(exc).__name__)
        return NoopNotifier()


def _token_invalid(notifier: Notifier, token: str) -> bool:
    fn = getattr(notifier, "token_invalid", None)
    try:
        return bool(fn(token)) if callable(fn) else False
    except Exception:
        return False


# ---------------------------------------------------------------- pairing codes

CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I/L
CODE_LEN = 6
INVITE_TTL = timedelta(minutes=10)
MAX_INVITES_PER_HOUR = 5
MAX_ACCEPT_FAILURES = 10  # per guardian user per rolling hour, then 429
RATE_WINDOW = timedelta(hours=1)
_DEV_PEPPER = "sentinel-dev-family-pepper"  # used only when FAMILY_CODE_PEPPER is unset
_pepper_warned = False


def new_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))


def normalize_code(raw: str) -> str | None:
    """Case-insensitive, spaces / dashes ignored. None if it cannot be a valid code."""
    c = re.sub(r"[\s-]", "", raw or "").upper()
    return c if len(c) == CODE_LEN and all(ch in CODE_ALPHABET for ch in c) else None


def code_hash(code: str) -> str:
    """HMAC-SHA256 of the code under a server-side pepper: a leaked table does not reveal live codes
    without the pepper."""
    global _pepper_warned
    pepper = settings.family_code_pepper
    if not pepper:
        if not _pepper_warned:
            log.warning("FAMILY_CODE_PEPPER unset: using the development pepper")
            _pepper_warned = True
        pepper = _DEV_PEPPER
    return hmac.new(pepper.encode(), code.encode(), hashlib.sha256).hexdigest()


def lock(db: Session, key: str) -> None:
    """Transaction-scoped advisory lock (released on commit / rollback)."""
    db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:k))"), {"k": key})


# ---------------------------------------------------------------- notifications

TITLE = "Sentinel alert"
BODY_MAX = 140
FAMILY_PINGS_PER_HOUR = 10
FALLBACK_BODY = "Possible scam detected. Check in with them."
DATA = {"kind": "family_critical", "alert_tier": "critical"}

_CTRL = re.compile(r"[\x00-\x1f\x7f-\x9f​-‏ -‮⁠-⁤﻿]")
_URL = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
_HANDLE = re.compile(r"[\w.+-]+@[\w.-]+", re.IGNORECASE)  # email / UPI id
_DOMAIN = re.compile(r"\b(?:[a-z0-9-]+\.)+[a-z]{2,24}\b(?:/\S*)?", re.IGNORECASE)
_DIGITS = re.compile(r"\+?\d(?:[\d\s,.\-()/]*\d)?")
_WS = re.compile(r"\s+")


def family_body(headline: str | None) -> str:
    """The alert headline as shown to a guardian: redacted again, with links, handles and any digit
    run masked (phones, amounts, OTPs), control characters removed, capped to BODY_MAX chars."""
    s = _CTRL.sub(" ", redact(headline or ""))
    s = _URL.sub("[link]", s)
    s = _HANDLE.sub("[id]", s)
    s = _DOMAIN.sub("[link]", s)
    s = _DIGITS.sub(lambda m: "[number]" if sum(ch.isdigit() for ch in m.group()) >= 3 else m.group(), s)
    s = _WS.sub(" ", s).strip()
    if not s.strip("[]: .,-"):
        return FALLBACK_BODY
    if len(s) > BODY_MAX:
        s = s[:BODY_MAX - 1].rstrip() + "…"
    return s


def _eligible(alert, decision) -> bool:
    return (getattr(alert, "tier", None) == "critical" and bool(getattr(decision, "show", False))
            and bool(getattr(decision, "interrupt", False)) and getattr(decision, "reason", "") != "user_check")


def _take_ping_slot(db: Session, guardian_id: str) -> bool:
    """Reserve one of the guardian's FAMILY_PINGS_PER_HOUR pushes (serialised per guardian). Commits."""
    now = utcnow()
    lock(db, f"family-ping:{guardian_id}")
    db.execute(delete(FamilyPing).where(FamilyPing.user_id == guardian_id, FamilyPing.created_at <= now - RATE_WINDOW))
    n = db.scalar(select(func.count()).select_from(FamilyPing).where(
        FamilyPing.user_id == guardian_id, FamilyPing.created_at > now - RATE_WINDOW)) or 0
    if n >= FAMILY_PINGS_PER_HOUR:
        db.commit()
        return False
    db.add(FamilyPing(user_id=guardian_id, created_at=now))
    db.commit()
    return True


def notify_family(db: Session, user, alert, *, decision, notifier: Notifier) -> int:
    """Push the protected `user`'s critical alert to each of their guardians. Returns pushes accepted."""
    if not _eligible(alert, decision):
        return 0
    links = db.execute(select(TrustedContact.id, TrustedContact.guardian_user_id, TrustedContact.fcm_token)
                       .where(TrustedContact.protected_user_id == user.id,
                              TrustedContact.fcm_invalid_at.is_(None))
                       .order_by(TrustedContact.id)).all()
    db.commit()  # end the read transaction before network calls
    body = family_body(getattr(alert, "headline", ""))
    sent = 0
    for link_id, guardian_id, token in links:
        try:
            if not token or not _take_ping_slot(db, guardian_id):
                continue
            if notifier.send(token, TITLE, body, dict(DATA)):
                sent += 1
            elif _token_invalid(notifier, token):
                db.execute(update(TrustedContact)
                           .where(TrustedContact.id == link_id, TrustedContact.fcm_token == token)
                           .values(fcm_invalid_at=utcnow()))
                db.commit()
        except Exception as exc:  # one guardian's failure never affects the others
            log.warning("family notification failed (%s)", type(exc).__name__)
            try:
                db.rollback()
            except Exception:
                pass
    return sent


def make_family_hook(notifier_factory: Callable[[], Notifier] | None = None):
    """An alert hook (see orchestrator.alert_hooks) that notifies guardians. The notifier is resolved
    lazily (only for eligible alerts) from `notifier_factory`, default get_notifier()."""

    def family_alert_hook(db: Session, user, alert, *, decision) -> None:
        if not _eligible(alert, decision):
            return
        notifier = (notifier_factory or get_notifier)()
        notify_family(db, user, alert, decision=decision, notifier=notifier)

    return family_alert_hook


# Registered by the app at startup (app/main.py lifespan), never at import time.
family_alert_hook = make_family_hook()
