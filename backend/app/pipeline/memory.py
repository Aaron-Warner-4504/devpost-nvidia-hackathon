"""Temporal memory: idempotent event storage, entity graph linking, history and vector recall."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime
from itertools import combinations
from typing import Protocol

from sqlalchemy import func, literal_column, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session

from app.models import Edge, Embedding, Event, EventEntity
from app.models import Entity as EntityRow
from app.pipeline.extract import Entity
from app.schemas import EventIn

CLAIM_SOURCE_TYPES = {"org", "phone", "domain"}


class _HasId(Protocol):
    id: str


@dataclass(frozen=True)
class StoredEvent:
    id: str
    duplicate: bool


@dataclass(frozen=True)
class HistoryItem:
    event_id: str
    ts: datetime
    source: str
    redacted_text: str | None


def _entity_ids(db: Session, user: _HasId, entities: list[Entity]) -> dict[Entity, str]:
    """Upsert entity rows for this user and return their ids."""
    out: dict[Entity, str] = {}
    for e in dict.fromkeys(entities):
        stmt = (pg_insert(EntityRow)
                .values(user_id=user.id, type=e.type, canonical=e.canonical)
                .on_conflict_do_update(index_elements=["user_id", "type", "canonical"],
                                       set_={"canonical": e.canonical})
                .returning(EntityRow.id))
        out[e] = db.execute(stmt).scalar_one()
    return out


def store_event(db: Session, user: _HasId, ev: EventIn, redacted: str,
                entities: list[Entity]) -> StoredEvent:
    digest = hashlib.sha256(redacted.encode()).hexdigest()
    stmt = (pg_insert(Event)
            .values(user_id=user.id, device_event_id=ev.device_event_id, source=ev.source,
                    ts=ev.ts, redacted_text=redacted, hash=digest)
            .on_conflict_do_nothing(index_elements=["user_id", "device_event_id"])
            .returning(Event.id))
    new_id = db.execute(stmt).scalar_one_or_none()
    duplicate = new_id is None
    event_id = new_id or db.scalar(select(Event.id).where(
        Event.user_id == user.id, Event.device_event_id == ev.device_event_id))
    ids = _entity_ids(db, user, entities)
    if ids:
        db.execute(pg_insert(EventEntity)
                   .values([{"user_id": user.id, "event_id": event_id, "entity_id": i}
                            for i in ids.values()])
                   .on_conflict_do_nothing())
    return StoredEvent(id=event_id, duplicate=duplicate)


def _upsert_edge(db: Session, user_id: str, a: str, b: str, relation: str,
                 ts: datetime, event_id: str) -> None:
    ins = pg_insert(Edge).values(user_id=user_id, a=a, b=b, relation=relation,
                                 valid_from=ts, evidence_event_ids=[event_id])
    merged = literal_column(
        "ARRAY(SELECT DISTINCT unnest(edges.evidence_event_ids || excluded.evidence_event_ids))")
    db.execute(ins.on_conflict_do_update(
        constraint="uq_edges_user_a_b_relation",
        set_={"valid_from": func.least(Edge.valid_from, ins.excluded.valid_from),
              "evidence_event_ids": merged}))


def link(db: Session, user: _HasId, stored: StoredEvent, entities: list[Entity]) -> None:
    ts = db.scalar(select(Event.ts).where(Event.id == stored.id, Event.user_id == user.id))
    if ts is None:
        return
    ids = _entity_ids(db, user, entities)
    for x, y in combinations(ids.values(), 2):
        a, b = sorted((x, y))
        _upsert_edge(db, user.id, a, b, "co_occurs", ts, stored.id)
    claims = [i for e, i in ids.items() if e.type == "claim"]
    sources = [i for e, i in ids.items() if e.type in CLAIM_SOURCE_TYPES]
    for s in sources:
        for c in claims:
            _upsert_edge(db, user.id, s, c, "claims", ts, stored.id)


def _entity_id(db: Session, user: _HasId, entity: Entity) -> str | None:
    return db.scalar(select(EntityRow.id).where(
        EntityRow.user_id == user.id, EntityRow.type == entity.type,
        EntityRow.canonical == entity.canonical))


def history(db: Session, user: _HasId, entity: Entity, now: datetime) -> list[HistoryItem]:
    rows = db.execute(
        select(Event.id, Event.ts, Event.source, Event.redacted_text)
        .join(EventEntity, EventEntity.event_id == Event.id)
        .join(EntityRow, EntityRow.id == EventEntity.entity_id)
        .where(Event.user_id == user.id, EntityRow.user_id == user.id,
               EntityRow.type == entity.type, EntityRow.canonical == entity.canonical,
               Event.ts <= now)
        .order_by(Event.ts, Event.id)).all()
    return [HistoryItem(*r) for r in rows]


def related_entities(db: Session, user: _HasId, entity: Entity, depth: int = 2) -> list[Entity]:
    """Entities reachable over edges (either direction) within `depth` hops, excluding itself."""
    eid = _entity_id(db, user, entity)
    if eid is None:
        return []
    seen, frontier = {eid}, {eid}
    for _ in range(depth):
        edges = db.execute(select(Edge.a, Edge.b).where(
            Edge.user_id == user.id, Edge.a.in_(frontier) | Edge.b.in_(frontier))).all()
        frontier = {x for a, b in edges for x in (a, b)} - seen
        seen |= frontier
        if not frontier:
            break
    seen.discard(eid)
    if not seen:
        return []
    rows = db.execute(select(EntityRow.type, EntityRow.canonical).where(
        EntityRow.user_id == user.id, EntityRow.id.in_(seen))
        .order_by(EntityRow.type, EntityRow.canonical)).all()
    return [Entity(t, c) for t, c in rows]


def store_embedding(db: Session, user: _HasId, event_id: str, vector: list[float]) -> None:
    db.add(Embedding(user_id=user.id, event_id=event_id, vector=vector))
    db.flush()


def recall(db: Session, user: _HasId, text: str, k: int = 5, *, llm,
           vector: list[float] | None = None) -> list[HistoryItem]:
    vec = vector if vector is not None else llm.embed([text])[0]
    rows = db.execute(
        select(Event.id, Event.ts, Event.source, Event.redacted_text)
        .join(Embedding, Embedding.event_id == Event.id)
        .where(Embedding.user_id == user.id, Event.user_id == user.id)
        .order_by(Embedding.vector.cosine_distance(vec), Event.ts)
        .limit(k)).all()
    return [HistoryItem(*r) for r in rows]
