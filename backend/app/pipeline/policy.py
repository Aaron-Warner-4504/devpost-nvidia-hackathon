"""Alert policy: decides whether an assessed event is shown to the user and whether it interrupts.

Rules (the clock is the event's own timestamp, so replays and tests are deterministic; it is clamped to
at most server-now + 1 day, and prior events' timestamps are clamped the same way, so a device clock set
in the future cannot sidestep dedupe or the caps):
- `info` is never shown, except for a user-initiated check (`always=True`).
- `caution` is shown (feed only) and never interrupts; `high` / `critical` interrupt.
- Dedupe (caution/high): suppressed (reason `duplicate`) when a PRIOR shown alert of this user with the
  same entity-set key and an equal or higher tier has `ts - 6h < prior_ts <= ts`. A tier increase passes.
  Exactly 6 h earlier no longer counts.
- Daily cap: at most 5 interrupts with `ts - 24h < prior_ts <= ts`; the cap only suppresses `high`
  (reason `daily_cap`).
- Critical flood guard: a `critical` with the same key as a shown critical with
  `ts - 10min < prior_ts <= ts` is suppressed (reason `critical_flood`); exactly 10 min later passes.
  Ceiling: after 20 shown criticals with `ts - 24h < prior_ts <= ts`, further criticals are still SHOWN
  (returned, listed) but non-interrupting (reason `critical_ceiling`): a flood never hides a new scam.
- `always=True` (/v1/check) is never suppressed, and its alerts (reason `user_check`) are ignored by
  dedupe, the cap and the critical guards: a user's own check never hides a later organic alert.
Suppressed alerts are still persisted by the caller (audit) with `shown_at` null. Alert hooks (push,
family) only run for shown alerts and receive the Decision; notifiers must act only on
`decision.interrupt`.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, or_, select, text
from sqlalchemy.orm import Session

from app.models import Alert, Assessment, Event
from app.pipeline.extract import Entity
from app.pipeline.signals import TIER_ORDER
from app.schemas import Tier

DEDUPE_WINDOW = timedelta(hours=6)
CAP_WINDOW = timedelta(hours=24)
DAILY_INTERRUPT_CAP = 5
CRITICAL_FLOOD_WINDOW = timedelta(minutes=10)
CRITICAL_DAILY_CEILING = 20
CLOCK_SKEW_MAX = timedelta(days=1)
USER_CHECK = "user_check"
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
    reason: str  # shown | user_check | info | duplicate | daily_cap | critical_flood | critical_ceiling


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
    """ORGANIC shown alerts of this user (user_check alerts excluded) joined to assessment and event."""
    return (select(*cols).select_from(Alert)
            .join(Assessment, Assessment.id == Alert.assessment_id)
            .join(Event, Event.id == Assessment.event_id)
            .where(Alert.user_id == user_id, Assessment.user_id == user_id, Event.user_id == user_id,
                   Alert.shown_at.is_not(None),
                   or_(Alert.reason.is_(None), Alert.reason != USER_CHECK)))


def decide(db: Session, user, cand: AlertCandidate, *, always: bool = False,
           now: datetime | None = None) -> Decision:
    """`now` (server time, default wall clock) only bounds the policy clock: ts <= now + 1 day."""
    if always:
        return Decision(True, False, USER_CHECK)
    if cand.tier not in TIER_ORDER or cand.tier == "info":
        return Decision(False, False, "info")
    upper = (now or datetime.now(timezone.utc)) + CLOCK_SKEW_MAX
    ts = min(cand.ts, upper)
    prior_ts = func.least(Event.ts, upper)

    def window(width: timedelta):  # one-sided: ts - width < prior_ts <= ts
        return (prior_ts > ts - width, prior_ts <= ts, Event.id != cand.event_id)

    _lock(db, user.id)
    if cand.tier == "critical":
        flood = db.scalar(_shown(user.id, func.count(Alert.id)).where(
            Alert.dedupe_key == cand.dedupe_key, Assessment.tier == "critical",
            *window(CRITICAL_FLOOD_WINDOW))) or 0
        if flood:
            return Decision(False, False, "critical_flood")
        n = db.scalar(_shown(user.id, func.count(Alert.id)).where(
            Assessment.tier == "critical", *window(CAP_WINDOW))) or 0
        if n >= CRITICAL_DAILY_CEILING:  # fail safe: still shown in the feed, just no more interrupts
            return Decision(True, False, "critical_ceiling")
        return Decision(True, True, "shown")
    prev = db.execute(_shown(user.id, Assessment.tier).where(
        Alert.dedupe_key == cand.dedupe_key, *window(DEDUPE_WINDOW))).scalars().all()
    if prev and _rank(cand.tier) <= max(_rank(t) for t in prev):
        return Decision(False, False, "duplicate")
    if cand.tier == "high":
        n = db.scalar(_shown(user.id, func.count(Alert.id)).where(
            Alert.interrupt.is_(True), *window(CAP_WINDOW))) or 0
        if n >= DAILY_INTERRUPT_CAP:
            return Decision(False, False, "daily_cap")
    return Decision(True, cand.tier in INTERRUPT_TIERS, "shown")
