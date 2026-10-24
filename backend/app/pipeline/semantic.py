"""Semantic linking of one stored event (runs in the stage-2 background review, never on the request path).

    stored redacted text -> embed (provider, timeout) -> upsert vector -> nearest PRIOR events of the same
    user (<= 14 days, k <= 5, similarity >= SEMANTIC_THRESHOLD, not already entity-linked, not an exact
    duplicate) -> `similar` edges (evidence = both event ids)

Never raises for provider failures (they mean "no semantic links"); database errors propagate to the
caller, which owns the transaction. Logs carry ids and counts only, never text.
"""

from __future__ import annotations

import logging
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Event
from app.pipeline import embeddings, memory

log = logging.getLogger(__name__)


def link_event(db: Session, user, event_id: str, *, settings, provider=None) -> list[memory.Neighbour]:
    if not embeddings.semantic_enabled(settings) and provider is None:
        return []
    provider = provider or embeddings.get_provider(settings)
    if provider is None:
        return []
    row = db.execute(select(Event.ts, Event.redacted_text).where(
        Event.id == event_id, Event.user_id == user.id)).first()
    if row is None or not (row.redacted_text or "").strip():
        return []
    vecs = embeddings.embed_safe(provider, [row.redacted_text], dim=settings.embed_dim,
                                 timeout=settings.embed_timeout_s)
    if not vecs:
        return []
    memory.store_embedding(db, user, event_id, vecs[0], model=provider.name)
    found = memory.semantic_neighbours(
        db, user, event_id, vecs[0], model=provider.name, now=row.ts,
        window=timedelta(days=settings.semantic_window_days), k=settings.semantic_k,
        threshold=settings.semantic_threshold)
    memory.link_similar(db, user, event_id, [n.item.event_id for n in found])
    log.info("semantic link: event %s -> %d neighbours", event_id, len(found))
    return found
