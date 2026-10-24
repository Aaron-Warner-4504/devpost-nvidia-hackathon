"""Semantic memory: vectors, nearest PRIOR neighbours, `similar` edges, scoping and the dim migration guard."""
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select, text

from app.config import settings
from app.models import Device, Edge, Embedding
from app.models import Entity as EntityRow
from app.pipeline import memory, semantic
from app.pipeline.embeddings import FakeEmbeddings
from app.pipeline.extract import Entity
from app.schemas import EventIn

T0 = datetime(2026, 3, 1, 10, 0, tzinfo=timezone.utc)
S = settings.model_copy(update={"embedding_provider": "fake", "semantic_memory": True, "semantic_threshold": 0.5,
                                "semantic_k": 5, "semantic_window_days": 14})


class U:
    def __init__(self, id):
        self.id = id


def user(register, db):
    d, _ = register()
    return U(db.get(Device, d).user_id)


def put(db, u, did, txt, hours=0.0, ents=()):
    e = EventIn(device_event_id=did, source="whatsapp", ts=T0 + timedelta(hours=hours), text=txt, triage_score=0.1)
    s = memory.store_event(db, u, e, txt, list(ents))
    memory.link(db, u, s, list(ents))
    db.flush()
    return s.id


def link(db, u, eid, **kw):
    out = semantic.link_event(db, u, eid, settings=S.model_copy(update=kw) if kw else S,
                              provider=FakeEmbeddings(384))
    db.commit()
    return out


STORY = ["Welcome to the Alpha traders stock tips group, members booked big profit this week",
         "Alpha traders stock tips group: members booked profit again, VIP slots open this week"]


def test_links_similar_prior_event_and_writes_edge(db, register):
    u = user(register, db)
    a = put(db, u, "a", STORY[0])
    link(db, u, a)
    b = put(db, u, "b", STORY[1], hours=30)
    found = link(db, u, b)
    assert [n.item.event_id for n in found] == [a] and found[0].similarity >= 0.5
    edges = db.scalars(select(Edge).where(Edge.user_id == u.id, Edge.relation == "similar")).all()
    assert len(edges) == 1 and sorted(edges[0].evidence_event_ids) == sorted([a, b])
    nodes = {r.canonical for r in db.scalars(select(EntityRow).where(EntityRow.type == "event"))}
    assert nodes == {a, b}
    assert [h.event_id for h in memory.similar_events(db, u, b, now=T0 + timedelta(days=2),
                                                      window=timedelta(days=7))] == [a]
    # idempotent: re-linking writes no second edge or vector
    link(db, u, b)
    assert db.query(Edge).filter(Edge.relation == "similar").count() == 1
    assert db.query(Embedding).filter(Embedding.event_id == b).count() == 1


def test_no_link_below_threshold_or_outside_window_or_future(db, register):
    u = user(register, db)
    old = put(db, u, "old", STORY[0], hours=-24 * 15)
    link(db, u, old)
    other = put(db, u, "other", "Did you reach office? call me when free")
    link(db, u, other)
    cur = put(db, u, "cur", STORY[1], hours=1)
    assert link(db, u, cur) == []  # 15-day-old story event is outside the 14-day window; chat is dissimilar
    later = put(db, u, "later", STORY[0], hours=5)
    link(db, u, later)
    # a LATER event never becomes a neighbour of an earlier one
    assert later not in [n.item.event_id for n in link(db, u, cur)]


def test_excludes_entity_linked_and_exact_duplicates(db, register):
    u = user(register, db)
    p = Entity("phone", "+919812345678")
    a = put(db, u, "a", STORY[0] + " call 9812345678", ents=[p])
    link(db, u, a)
    d = put(db, u, "d", STORY[1])
    link(db, u, d)
    dup = put(db, u, "dup", STORY[1], hours=2)
    b = put(db, u, "b", STORY[1] + " ping 9812345678", hours=3, ents=[p])
    got = {n.item.event_id for n in link(db, u, b)}
    assert a not in got  # already linked through the shared phone
    assert d in got
    assert d not in {n.item.event_id for n in link(db, u, dup)}  # exact duplicate text


def test_k_capped_at_five(db, register):
    u = user(register, db)
    for i in range(8):
        link(db, u, put(db, u, f"e{i}", STORY[0] + f" day {i}", hours=i))
    cur = put(db, u, "cur", STORY[0] + " today", hours=20)
    assert len(link(db, u, cur, semantic_k=50)) == 5


def test_two_users_near_identical_messages_never_link(db, register):
    u1, u2 = user(register, db), user(register, db)
    a = put(db, u1, "x", STORY[0])
    link(db, u1, a)
    b = put(db, u2, "x", STORY[0] + " now")
    assert link(db, u2, b) == []
    assert memory.similar_events(db, u2, b, now=T0 + timedelta(days=1), window=timedelta(days=14)) == []
    assert db.query(Edge).filter(Edge.relation == "similar").count() == 0


def test_provider_failure_means_no_links(db, register):
    class Down:
        name = "down"

        def embed(self, texts):
            raise RuntimeError("down")

    u = user(register, db)
    a = put(db, u, "a", STORY[0])
    assert semantic.link_event(db, u, a, settings=S, provider=Down()) == []
    assert db.query(Embedding).count() == 0


def test_disabled_does_nothing(db, register):
    u = user(register, db)
    a = put(db, u, "a", STORY[0])
    off = settings.model_copy(update={"embedding_provider": "", "token_factory_api_key": "", "semantic_memory": None})
    assert semantic.link_event(db, u, a, settings=off) == []
    assert db.query(Embedding).count() == 0


def test_dim_migration_guard(test_engine):
    import importlib.util
    import os
    path = os.path.join(os.path.dirname(__file__), "..", "alembic", "versions", "0009_semantic_memory.py")
    spec = importlib.util.spec_from_file_location("mig0009", path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    with test_engine.connect() as c:
        tx = c.begin()
        m.resize_vector(c, 384)  # empty table: allowed
        assert "vector(384)" in c.execute(text(
            "SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'embeddings'::regclass "
            "AND attname = 'vector'")).scalar()
        tx.rollback()
    with test_engine.connect() as c:
        tx = c.begin()
        c.execute(text("INSERT INTO users (id) VALUES ('usr_g')"))
        c.execute(text("INSERT INTO events (id, user_id, device_event_id, source, ts, hash) "
                       "VALUES ('evt_g', 'usr_g', 'g', 'sms', now(), 'h')"))
        c.execute(text("INSERT INTO embeddings (id, user_id, event_id, vector, model) VALUES "
                       "('emb_g', 'usr_g', 'evt_g', :v, 'm')"), {"v": str([0.0] * settings.embed_dim)})
        with pytest.raises(RuntimeError, match="cannot be converted"):
            m.resize_vector(c, 384)
        m.resize_vector(c, settings.embed_dim)  # same width: no-op even with rows
        tx.rollback()
