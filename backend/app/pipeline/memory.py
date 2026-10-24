"""Temporal memory: idempotent event storage, entity graph linking, history and vector recall."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
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
                entities: list[Entity], *, sender_key: str | None = None) -> StoredEvent:
    """Idempotently store an event. duplicate=True means it was already stored: callers must
    skip alerting. A naive `ev.ts` is assumed to be UTC. `sender_key` is an opaque (hashed)
    sender identity used for "seen this sender before" lookups."""
    ts = ev.ts if ev.ts.tzinfo else ev.ts.replace(tzinfo=timezone.utc)
    digest = hashlib.sha256(redacted.encode()).hexdigest()
    stmt = (pg_insert(Event)
            .values(user_id=user.id, device_event_id=ev.device_event_id, source=ev.source,
                    ts=ts, redacted_text=redacted, hash=digest, sender_key=sender_key)
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
            exclude_event_id: str | None = None, limit: int | None = None) -> list[HistoryItem]:
    """Events containing `entity` with ts <= now, oldest first. Includes the current event when
    now == its ts; pass `exclude_event_id` to leave it out. With `limit`, only the newest
    `limit` events are returned (still oldest first)."""
    q = (select(Event.id, Event.ts, Event.source, Event.redacted_text)
        .join(EventEntity, EventEntity.event_id == Event.id)
        .join(EntityRow, EntityRow.id == EventEntity.entity_id)
        .where(Event.user_id == user.id, EntityRow.user_id == user.id,
               EntityRow.type == entity.type, EntityRow.canonical == entity.canonical,
               Event.ts <= now,
               Event.id != exclude_event_id if exclude_event_id else true()))
    if limit is None:
        rows = db.execute(q.order_by(Event.ts, Event.id)).all()
    else:
        rows = db.execute(q.order_by(Event.ts.desc(), Event.id.desc()).limit(limit)).all()[::-1]
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


def store_embedding(db: Session, user: _HasId, event_id: str, vector: list[float], *,
                    model: str = "") -> None:
    """One vector per event: storing again replaces it (upsert on event_id)."""
    ins = pg_insert(Embedding).values(user_id=user.id, event_id=event_id, vector=vector, model=model)
    db.execute(ins.on_conflict_do_update(constraint="uq_embeddings_event",
                                         set_={"vector": ins.excluded.vector, "model": ins.excluded.model}))
    db.flush()


def recall(db: Session, user: _HasId, text: str, k: int = 5, *, llm,
           vector: list[float] | None = None, model: str | None = None) -> list[HistoryItem]:
    """Nearest events by cosine distance (k clamped to 1..50), one row per event. With `model`, only
    vectors produced by that provider are compared."""
    k = max(1, min(k, 50))
    vec = vector if vector is not None else llm.embed([text])[0]
    dist = func.min(Embedding.vector.cosine_distance(vec))
    q = (select(Event.id, Event.ts, Event.source, Event.redacted_text)
         .join(Embedding, Embedding.event_id == Event.id)
         .where(Embedding.user_id == user.id, Event.user_id == user.id))
    if model is not None:
        q = q.where(Embedding.model == model)
    rows = db.execute(q.group_by(Event.id).order_by(dist, Event.ts).limit(k)).all()
    return [HistoryItem(*r) for r in rows]


# ---------------------------------------------------------------- semantic links between events
# Two events with similar (redacted) text but no shared entity are linked by an edge `similar` between
# per-event graph nodes: an entity row of type "event" whose canonical is the event id. The node has no
# edges to phone/domain/org/claim entities, so entity traversal (related_entities) never crosses it.
EVENT_NODE = "event"
SIMILAR = "similar"


@dataclass(frozen=True)
class Neighbour:
    item: HistoryItem
    similarity: float


def _shares_entity_with(user_id: str, event_id: str):
    """Event ids sharing a non-amount, non-node entity with `event_id` (already linked by entities)."""
    mine = (select(EventEntity.entity_id).join(EntityRow, EntityRow.id == EventEntity.entity_id)
            .where(EventEntity.user_id == user_id, EventEntity.event_id == event_id,
                   EntityRow.user_id == user_id, EntityRow.type.not_in(("amount", EVENT_NODE))))
    return select(EventEntity.event_id).where(EventEntity.user_id == user_id, EventEntity.entity_id.in_(mine))


def semantic_neighbours(db: Session, user: _HasId, event_id: str, vector: list[float], *, model: str,
                        now: datetime, window: timedelta, k: int, threshold: float) -> list[Neighbour]:
    """Up to k (<= 5) PRIOR events of the same user within `window` before `now` whose stored vector
    (same model) has cosine similarity >= threshold. Excludes the event itself, events already linked
    by a shared entity and exact text duplicates. Most similar first."""
    k = max(1, min(int(k), 5))
    now = now if now.tzinfo else now.replace(tzinfo=timezone.utc)
    own_hash = select(Event.hash).where(Event.id == event_id, Event.user_id == user.id).scalar_subquery()
    dist = Embedding.vector.cosine_distance(vector)
    rows = db.execute(
        select(Event.id, Event.ts, Event.source, Event.redacted_text, dist.label("d"))
        .join(Embedding, Embedding.event_id == Event.id)
        .where(Embedding.user_id == user.id, Event.user_id == user.id, Embedding.model == model,
               Event.id != event_id, Event.ts <= now, Event.ts >= now - window,
               Event.hash != own_hash,
               Event.id.not_in(_shares_entity_with(user.id, event_id)),
               dist <= 1.0 - threshold)
        .order_by(dist, Event.ts.desc(), Event.id).limit(k)).all()
    return [Neighbour(HistoryItem(r[0], r[1], r[2], r[3]), round(1.0 - float(r[4]), 4)) for r in rows]


def _node_ids(db: Session, user: _HasId, event_ids: list[str]) -> dict[str, str]:
    return {e.canonical: i for e, i in _entity_ids(
        db, user, [Entity(EVENT_NODE, eid) for eid in event_ids]).items()}  # type: ignore[arg-type]


def link_similar(db: Session, user: _HasId, event_id: str, neighbour_ids: list[str]) -> int:
    """Persist `similar` edges between this event and each neighbour (evidence = both event ids).
    Idempotent. Returns the number of edges written."""
    ids = [n for n in dict.fromkeys(neighbour_ids) if n != event_id]
    if not ids:
        return 0
    ts = dict(db.execute(select(Event.id, Event.ts).where(
        Event.user_id == user.id, Event.id.in_([event_id, *ids]))).all())
    if event_id not in ts:
        return 0
    ids = [n for n in ids if n in ts]
    nodes = _node_ids(db, user, [event_id, *ids])
    pending = []
    for n in ids:
        a, b = sorted((nodes[event_id], nodes[n]))
        pending.append((a, b, min(ts[event_id], ts[n]), n))
    for a, b, valid_from, n in sorted(pending):  # canonical lock order
        ins = pg_insert(Edge).values(user_id=user.id, a=a, b=b, relation=SIMILAR, valid_from=valid_from,
                                     evidence_event_ids=sorted([event_id, n]))
        db.execute(ins.on_conflict_do_nothing(constraint="uq_edges_user_a_b_relation"))
    return len(pending)


def similar_events(db: Session, user: _HasId, event_id: str, *, now: datetime,
                   window: timedelta) -> list[HistoryItem]:
    """Events linked to `event_id` by a `similar` edge, with ts in [now - window, now], oldest first."""
    node = db.scalar(select(EntityRow.id).where(EntityRow.user_id == user.id, EntityRow.type == EVENT_NODE,
                                                EntityRow.canonical == event_id))
    if node is None:
        return []
    pairs = db.execute(select(Edge.a, Edge.b).where(
        Edge.user_id == user.id, Edge.relation == SIMILAR, (Edge.a == node) | (Edge.b == node))).all()
    others = {b if a == node else a for a, b in pairs}
    if not others:
        return []
    other_events = select(EntityRow.canonical).where(EntityRow.user_id == user.id, EntityRow.type == EVENT_NODE,
                                                     EntityRow.id.in_(others))
    now = now if now.tzinfo else now.replace(tzinfo=timezone.utc)
    rows = db.execute(select(Event.id, Event.ts, Event.source, Event.redacted_text).where(
        Event.user_id == user.id, Event.id.in_(other_events), Event.id != event_id,
        Event.ts <= now, Event.ts >= now - window).order_by(Event.ts, Event.id)).all()
    return [HistoryItem(*r) for r in rows]


@dataclass(frozen=True)
class EventMeta:
    sender_key: str | None
    signals: frozenset[str]
    stages: frozenset[str] | None  # None when no assessment stored stages (older rows)


def event_meta(db: Session, user: _HasId, event_ids: list[str]) -> dict[str, EventMeta]:
    """Hashed sender, stored signal names and story stages (union over the event's assessments)."""
    from app.models import Assessment  # local: keeps the import graph of this module small
    ids = list(dict.fromkeys(event_ids))
    if not ids:
        return {}
    senders = dict(db.execute(select(Event.id, Event.sender_key).where(
        Event.user_id == user.id, Event.id.in_(ids))).all())
    sig: dict[str, set[str]] = {}
    stg: dict[str, set[str] | None] = {}
    for eid, names, payload in db.execute(select(Assessment.event_id, Assessment.signals, Assessment.payload).where(
            Assessment.user_id == user.id, Assessment.event_id.in_(ids))).all():
        sig.setdefault(eid, set()).update(names or [])
        st = payload.get("stages") if isinstance(payload, dict) else None
        if isinstance(st, list):
            cur = stg.get(eid) or set()
            stg[eid] = cur | {x for x in st if isinstance(x, str)}
        else:
            stg.setdefault(eid, None)
    return {eid: EventMeta(senders.get(eid), frozenset(sig.get(eid, ())),
                           frozenset(stg[eid]) if stg.get(eid) is not None else None)
            for eid in ids if eid in senders}
