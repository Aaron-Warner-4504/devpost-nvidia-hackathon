"""Alert feedback -> alerts.user_label + per-user entity overrides read by the rules (signals.py).

- `safe`: the event's ORIGINATOR entities become `safe` — exactly the ones signals.py may discount: the
  sender's / caller's phone (identified by the event's sender_key). Entities merely named in the body are
  never made safe (a scammer can quote a number the user trusts). Email sender domains would qualify only
  when the sender is authenticated, which the pipeline does not verify yet, so email feedback pins nothing.
- `scam`: the event's phone / domain / UPI entities become `known_bad` (weight 7 -> critical on the next
  event involving them), except legitimate brand / shared-infrastructure / shortener domains, which must
  never be pinned (that would flag every genuine bank message).
Each kind removes the opposite override for the same entities, so feedback is idempotent and can flip.
Overrides are per user; at most MAX_OVERRIDES are kept per user (oldest evicted).
"""

from __future__ import annotations

from sqlalchemy import delete, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.family import lock
from app.models import Alert, Assessment, Entity as EntityRow, Event, EventEntity, Override, _id
from app.pipeline import tools
from app.pipeline.extract import Entity
from app.pipeline.orchestrator import sender_key_for

MAX_OVERRIDES = 500
_new_id = _id("ovr")
KNOWN_BAD_TYPES = ("phone", "domain", "upi_id")


def never_known_bad(type_: str, canonical: str) -> bool:
    """Entities that must never be pinned known_bad: orgs and legitimate brand / infra domains."""
    if type_ == "org":
        return True
    if type_ != "domain":
        return False
    d = canonical.lower().rstrip(".")
    if d in tools.SHORTENERS or d in tools._SAFE_INFRA or d in tools._BRAND_DOMAINS:
        return True
    return tools._registrable(d) in tools._BRAND_DOMAINS


def originator_entities(entities: list[Entity], sender_key: str | None) -> list[Entity]:
    if not sender_key:
        return []
    return [e for e in entities if e.type == "phone" and sender_key_for(e.canonical) == sender_key]


def apply_feedback(db: Session, user, alert_id: str, label: str) -> bool:
    """False if the alert does not exist or is not the caller's."""
    row = db.execute(
        select(Alert, Event)
        .join(Assessment, Assessment.id == Alert.assessment_id)
        .join(Event, Event.id == Assessment.event_id)
        .where(Alert.id == alert_id, Alert.user_id == user.id, Assessment.user_id == user.id,
               Event.user_id == user.id)).first()
    if row is None:
        return False
    alert, event = row
    lock(db, f"overrides:{user.id}")  # serialise upserts + cap eviction per user
    ents = [Entity(t, c) for t, c in db.execute(
        select(EntityRow.type, EntityRow.canonical)
        .join(EventEntity, EventEntity.entity_id == EntityRow.id)
        .where(EventEntity.event_id == event.id, EventEntity.user_id == user.id, EntityRow.user_id == user.id)
        .order_by(EntityRow.type, EntityRow.canonical)).all()]
    if label == "safe":
        kind, opposite = "safe", "known_bad"
        targets = originator_entities(ents, event.sender_key)
    else:
        kind, opposite = "known_bad", "safe"
        targets = [e for e in ents if e.type in KNOWN_BAD_TYPES and not never_known_bad(e.type, e.canonical)]
    alert.user_label = label
    for e in dict.fromkeys(targets):
        db.execute(delete(Override).where(Override.user_id == user.id, Override.kind == opposite,
                                          Override.type == e.type, Override.canonical == e.canonical))
        db.execute(insert(Override).values(id=_new_id(), user_id=user.id, kind=kind, type=e.type,
                                           canonical=e.canonical)
                   .on_conflict_do_nothing(index_elements=["user_id", "kind", "type", "canonical"]))
    keep = select(Override.id).where(Override.user_id == user.id).order_by(
        Override.created_at.desc(), Override.id.desc()).limit(MAX_OVERRIDES)
    db.execute(delete(Override).where(Override.user_id == user.id, Override.id.not_in(keep)))
    db.commit()
    return True
