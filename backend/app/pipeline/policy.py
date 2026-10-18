"""Alert policy: decides whether an assessed event is shown to the user and whether it interrupts.

Rules (the clock is the event's own timestamp, so replays and tests are deterministic):
- `info` is never shown, except for a user-initiated check (`always=True`).
- `caution` is shown (feed only) and never interrupts; `high` / `critical` interrupt.
- Dedupe: an alert is suppressed when a previously SHOWN alert of this user with the same entity-set
  key and an equal or higher tier lies within 6 h of it. A tier increase passes.
- Daily cap: at most 5 interrupts per rolling 24 h; the cap only suppresses `high`.
- `critical` is never suppressed by dedupe or the cap; `always=True` is never suppressed.
Suppressed alerts are still persisted by the caller (audit) with `shown_at` null.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.models import Alert, Assessment, Event
from app.pipeline.extract import Entity
from app.pipeline.signals import TIER_ORDER
from app.schemas import Tier

DEDUPE_WINDOW = timedelta(hours=6)
CAP_WINDOW = timedelta(hours=24)
DAILY_INTERRUPT_CAP = 5
INTERRUPT_TIERS = frozenset({"high", "critical"})


@dataclass(frozen=True)
class AlertCandidate:
    event_id: str
    tier: Tier
    dedupe_key: str
    ts: datetime  # the event's timestamp (aware UTC): the policy clock


@dataclass(frozen=True)
class Decision:
    show: bool
    interrupt: bool
    reason: str  # shown | user_check | info | duplicate | daily_cap


def dedupe_key(entities: list[Entity], text_hash: str) -> str:
    """Stable key for "the same thing again": the event's entity set without amounts (an amount
    change alone is the same scam); entity-less events fall back to the redacted-text hash."""
    parts = sorted({f"{e.type}:{e.canonical}" for e in entities if e.type != "amount"})
    raw = "\n".join(parts) if parts else f"text:{text_hash}"
    return hashlib.sha256(raw.encode()).hexdigest()


def _rank(t: str) -> int:
    return TIER_ORDER.index(t) if t in TIER_ORDER else 0


def _lock(db: Session, user_id: str) -> None:
    """Serialise policy decisions per user until commit, so two concurrent events cannot both
    pass dedupe / the cap."""
    db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:k))"), {"k": f"alert-policy:{user_id}"})


def _shown(user_id: str, *cols):
    """Shown alerts of this user joined to their assessment and event."""
    return (select(*cols).select_from(Alert)
            .join(Assessment, Assessment.id == Alert.assessment_id)
            .join(Event, Event.id == Assessment.event_id)
            .where(Alert.user_id == user_id, Assessment.user_id == user_id, Event.user_id == user_id,
                   Alert.shown_at.is_not(None)))


def decide(db: Session, user, cand: AlertCandidate, *, always: bool = False) -> Decision:
    if always:
        return Decision(True, False, "user_check")
    if cand.tier not in TIER_ORDER or cand.tier == "info":
        return Decision(False, False, "info")
    interrupt = cand.tier in INTERRUPT_TIERS
    if cand.tier == "critical":
        return Decision(True, True, "shown")
    _lock(db, user.id)
    prev = db.execute(
        _shown(user.id, Assessment.tier)
        .where(Alert.dedupe_key == cand.dedupe_key, Event.id != cand.event_id,
               Event.ts >= cand.ts - DEDUPE_WINDOW, Event.ts <= cand.ts + DEDUPE_WINDOW)).scalars().all()
    if prev and _rank(cand.tier) <= max(_rank(t) for t in prev):
        return Decision(False, False, "duplicate")
    if cand.tier == "high":
        n = db.scalar(_shown(user.id, func.count(Alert.id))
                      .where(Alert.interrupt.is_(True), Event.id != cand.event_id,
                             Event.ts > cand.ts - CAP_WINDOW, Event.ts <= cand.ts)) or 0
        if n >= DAILY_INTERRUPT_CAP:
            return Decision(False, False, "daily_cap")
    return Decision(True, interrupt, "shown")
