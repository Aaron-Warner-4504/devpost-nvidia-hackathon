"""Temporal memory: idempotent event storage, entity graph linking, history and vector recall."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timezone
from itertools import combinations
from typing import Protocol

from sqlalchemy import func, literal_column, select, true
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
    # canonical lock order (type, canonical) so concurrent transactions cannot deadlock
    for e in sorted(set(entities), key=lambda x: (x.type, x.canonical)):
        stmt = (pg_insert(EntityRow)
                .values(user_id=user.id, type=e.type, canonical=e.canonical)
                .on_conflict_do_update(index_elements=["user_id", "type", "canonical"],
                                       set_={"canonical": e.canonical})
                .returning(EntityRow.id))
        out[e] = db.execute(stmt).scalar_one()
    return out


def store_event(db: Session, user: _HasId, ev: EventIn, redacted: str,
                entities: list[Entity]) -> StoredEvent:
    """Idempotently store an event. duplicate=True means it was already stored: callers must
    skip alerting. A naive `ev.ts` is assumed to be UTC."""
    ts = ev.ts if ev.ts.tzinfo else ev.ts.replace(tzinfo=timezone.utc)
    digest = hashlib.sha256(redacted.encode()).hexdigest()
    stmt = (pg_insert(Event)
            .values(user_id=user.id, device_event_id=ev.device_event_id, source=ev.source,
                    ts=ts, redacted_text=redacted, hash=digest)
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
    pending: set[tuple[str, str, str]] = set()
    for x, y in combinations(ids.values(), 2):
        a, b = sorted((x, y))
        pending.add((a, b, "co_occurs"))
    claims = [i for e, i in ids.items() if e.type == "claim"]
    sources = [i for e, i in ids.items() if e.type in CLAIM_SOURCE_TYPES]
    pending.update((src, c, "claims") for src in sources for c in claims)
    for a, b, relation in sorted(pending):  # canonical lock order
        _upsert_edge(db, user.id, a, b, relation, ts, stored.id)


def _entity_id(db: Session, user: _HasId, entity: Entity) -> str | None:
    return db.scalar(select(EntityRow.id).where(
        EntityRow.user_id == user.id, EntityRow.type == entity.type,
        EntityRow.canonical == entity.canonical))


def history(db: Session, user: _HasId, entity: Entity, now: datetime,
            exclude_event_id: str | None = None) -> list[HistoryItem]:
    """Events containing `entity` with ts <= now, oldest first. Includes the current event when
    now == its ts; pass `exclude_event_id` to leave it out."""
    rows = db.execute(
        select(Event.id, Event.ts, Event.source, Event.redacted_text)
        .join(EventEntity, EventEntity.event_id == Event.id)
        .join(EntityRow, EntityRow.id == EventEntity.entity_id)
        .where(Event.user_id == user.id, EntityRow.user_id == user.id,
               EntityRow.type == entity.type, EntityRow.canonical == entity.canonical,
               Event.ts <= now,
               Event.id != exclude_event_id if exclude_event_id else true())
        .order_by(Event.ts, Event.id)).all()
    return [HistoryItem(*r) for r in rows]


def _edge_rows(db: Session, user_id: str, nodes: set[str], now: datetime | None):
    q = select(Edge.a, Edge.b).where(Edge.user_id == user_id, Edge.a.in_(nodes) | Edge.b.in_(nodes))
    if now is not None:
        q = q.where(Edge.valid_from <= now, (Edge.valid_to.is_(None)) | (Edge.valid_to > now))
    return db.execute(q).all()


def neighbors_by_depth(db: Session, user: _HasId, entity: Entity, *, depth: int = 2,
                       now: datetime | None = None, types: set[str] | None = None,
                       hub_degree: int = 25) -> dict[int, list[Entity]]:
    """Entities by hop distance ({1: direct, 2: indirect}). Edges are time-filtered by `now`.
    Entities with more than `hub_degree` edges are returned but not expanded through at
    depth >= 2. Each level is ordered by (type, canonical)."""
    eid = _entity_id(db, user, entity)
    levels: dict[int, set[str]] = {d: set() for d in range(1, depth + 1)}
    if eid is not None:
        seen, frontier = {eid}, {eid}
        for d in range(1, depth + 1):
            edges = _edge_rows(db, user.id, frontier, now)
            if d >= 2:
                degree: dict[str, int] = {}
                for a, b in edges:
                    for n in (a, b):
                        if n in frontier:
                            degree[n] = degree.get(n, 0) + 1
                hubs = {n for n, c in degree.items() if c > hub_degree}
                edges = [(a, b) for a, b in edges if not ({a, b} & frontier) <= hubs]
            frontier = {x for a, b in edges for x in (a, b)} - seen
            levels[d] = frontier
            seen |= frontier
            if not frontier:
                break
    out: dict[int, list[Entity]] = {}
    for d, ids in levels.items():
        out[d] = []
        if ids:
            q = select(EntityRow.type, EntityRow.canonical).where(
                EntityRow.user_id == user.id, EntityRow.id.in_(ids))
            if types is not None:
                q = q.where(EntityRow.type.in_(types))
            out[d] = [Entity(t, c) for t, c in db.execute(q.order_by(EntityRow.type, EntityRow.canonical))]
    return out


def related_entities(db: Session, user: _HasId, entity: Entity, *, depth: int = 2,
                     now: datetime | None = None, types: set[str] | None = None,
                     limit: int = 50, hub_degree: int = 25) -> list[Entity]:
    """Flat list: depth-1 neighbours first, then depth-2, capped at `limit`."""
    by = neighbors_by_depth(db, user, entity, depth=depth, now=now, types=types,
                            hub_degree=hub_degree)
    return [e for d in sorted(by) for e in by[d]][:limit]


def store_embedding(db: Session, user: _HasId, event_id: str, vector: list[float]) -> None:
    db.add(Embedding(user_id=user.id, event_id=event_id, vector=vector))
    db.flush()


def recall(db: Session, user: _HasId, text: str, k: int = 5, *, llm,
           vector: list[float] | None = None) -> list[HistoryItem]:
    """Nearest events by cosine distance (k clamped to 1..50), one row per event."""
    k = max(1, min(k, 50))
    vec = vector if vector is not None else llm.embed([text])[0]
    dist = func.min(Embedding.vector.cosine_distance(vec))
    rows = db.execute(
        select(Event.id, Event.ts, Event.source, Event.redacted_text)
        .join(Embedding, Embedding.event_id == Event.id)
        .where(Embedding.user_id == user.id, Event.user_id == user.id)
        .group_by(Event.id)
        .order_by(dist, Event.ts)
        .limit(k)).all()
    return [HistoryItem(*r) for r in rows]
