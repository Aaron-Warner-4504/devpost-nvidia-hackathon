"""Push of the user's own high / critical alerts to the user's own devices (reuses family.Notifier).

What the device receives: title "Sentinel alert", the alert HEADLINE (redacted again, links / ids /
digit runs masked, max 140 chars) and data {"kind": "own_alert", "alert_tier": tier}. Never message text.

When: a shown alert of tier high / critical with decision.interrupt true and reason not user_check.
Never for caution / info, /v1/check results, suppressed (hooks not called), duplicate or non-interrupting
(e.g. critical_ceiling) alerts. A failing notifier never breaks the pipeline; an unregistered token marks
the device `fcm_invalid_at` (cleared when the device posts a new token). Tokens are never logged.
"""

from __future__ import annotations

import logging
from typing import Callable

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app import family
from app.family import Notifier
from app.models import Device

log = logging.getLogger(__name__)

TIERS = frozenset({"high", "critical"})


def _eligible(alert, decision) -> bool:
    return (getattr(alert, "tier", None) in TIERS and bool(getattr(decision, "show", False))
            and bool(getattr(decision, "interrupt", False)) and getattr(decision, "reason", "") != "user_check")


def notify_own(db: Session, user, alert, *, decision, notifier: Notifier) -> int:
    if not _eligible(alert, decision):
        return 0
    rows = db.execute(select(Device.id, Device.fcm_token).where(
        Device.user_id == user.id, Device.fcm_token.is_not(None), Device.fcm_invalid_at.is_(None))
        .order_by(Device.created_at, Device.id)).all()
    db.commit()  # end the read transaction before network calls
    body = family.family_body(getattr(alert, "headline", ""))
    data = {"kind": "own_alert", "alert_tier": str(alert.tier)}
    sent = 0
    for dev_id, token in rows:
        try:
            if notifier.send(token, family.TITLE, body, dict(data)):
                sent += 1
            elif family._token_invalid(notifier, token):
                db.execute(update(Device).where(Device.id == dev_id, Device.fcm_token == token)
                           .values(fcm_invalid_at=family.utcnow()))
                db.commit()
        except Exception as exc:  # one device's failure never affects the others
            log.warning("own-device push failed (%s)", type(exc).__name__)
            try:
                db.rollback()
            except Exception:
                pass
    return sent


def make_push_hook(notifier_factory: Callable[[], Notifier] | None = None):
    def own_push_hook(db: Session, user, alert, *, decision) -> None:
        if not _eligible(alert, decision):
            return
        notify_own(db, user, alert, decision=decision, notifier=(notifier_factory or family.get_notifier)())

    return own_push_hook


# Registered by the app at startup (app/main.py lifespan), never at import time.
push_alert_hook = make_push_hook()
