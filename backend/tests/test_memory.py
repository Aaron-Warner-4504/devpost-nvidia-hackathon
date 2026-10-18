from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select

from app.config import settings
from app.llm import FakeLLM
from app.models import Device, Edge, Event
from app.models import Entity as EntityRow
from app.pipeline.extract import Entity
from app.pipeline.memory import (history, link, neighbors_by_depth, recall, related_entities,
                                 store_embedding,
                                 store_event)
from app.schemas import CallMeta, EventIn

T0 = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
P = Entity("phone", "+919876543210")
DOM = Entity("domain", "x.in")
ORG = Entity("org", "customs")
CLAIM = Entity("claim", "parcel_held")


def uid(register, db):
    d, _ = register()
    return db.get(Device, d).user_id


class U:  # lightweight user stand-in (only .id is used)
    def __init__(self, id):
        self.id = id


def ev(i, source="sms", minutes=0, text="t"):
    return EventIn(device_event_id=i, source=source, ts=T0 + timedelta(minutes=minutes),
                   text=text, triage_score=0.5)


def ingest(db, u, e, redacted, ents):
    s = store_event(db, u, e, redacted, ents)
    link(db, u, s, ents)
    db.commit()
    return s


def test_duplicate_event_idempotent(db, register):
    u = U(uid(register, db))
    a = store_event(db, u, ev("d1"), "hello", [P])
    b = store_event(db, u, ev("d1"), "hello", [P])
    db.commit()
    assert not a.duplicate and b.duplicate and a.id == b.id
    assert db.scalar(select(func.count()).select_from(Event)) == 1


def test_stores_redacted_text_and_hash_only(db, register):
    import hashlib
    u = U(uid(register, db))
    s = store_event(db, u, ev("d1", text="RAW 9876543210"), "RAW [PHONE]", [])
    db.commit()
    row = db.get(Event, s.id)
    assert row.redacted_text == "RAW [PHONE]"
    assert row.hash == hashlib.sha256(b"RAW [PHONE]").hexdigest()


def test_related_and_history_scenario(db, register):
    u = U(uid(register, db))
    other = U(uid(register, db))
    ingest(db, u, ev("1", "sms", 0), "customs x.in", [ORG, DOM])
    ingest(db, u, ev("2", "call", 10), "call", [P])
    ingest(db, u, ev("3", "whatsapp", 20), "visit x.in", [P, DOM])
    ingest(db, other, ev("9", "sms", 5), "other", [P, DOM])
    rel = related_entities(db, u, P)
    assert DOM in rel and ORG in rel and P not in rel
    assert related_entities(db, u, DOM).count(P) == 1
    h = history(db, u, P, T0 + timedelta(days=1))
    assert [i.source for i in h] == ["call", "whatsapp"]
    assert all(i.redacted_text != "other" for i in h)
    assert [i.source for i in history(db, u, P, T0 + timedelta(minutes=15))] == ["call"]
    assert related_entities(db, U("nobody"), P) == []


def test_edges_not_duplicated_and_evidence_accumulates(db, register):
    u = U(uid(register, db))
    s1 = ingest(db, u, ev("1", minutes=10), "a", [P, DOM])
    s2 = ingest(db, u, ev("2", minutes=0), "b", [DOM, P])
    edges = db.scalars(select(Edge).where(Edge.user_id == u.id)).all()
    assert len(edges) == 1
    assert sorted(edges[0].evidence_event_ids) == sorted([s1.id, s2.id])
    assert edges[0].valid_from == T0
    ingest(db, u, ev("2", minutes=0), "b", [DOM, P])  # duplicate delivery
    db.refresh(edges[0])
    assert len(edges[0].evidence_event_ids) == 2


def test_claims_edges(db, register):
    u = U(uid(register, db))
    ingest(db, u, ev("1"), "x", [ORG, DOM, P, CLAIM, Entity("amount", "INR:5")])
    rows = db.execute(select(Edge.relation, Edge.a, Edge.b).where(Edge.user_id == u.id)).all()
    ids = {(e.type, e.canonical): e.id for e in db.scalars(select(EntityRow))}
    claims = {(a, b) for r, a, b in rows if r == "claims"}
    assert claims == {(ids[("org", "customs")], ids[("claim", "parcel_held")]),
                      (ids[("domain", "x.in")], ids[("claim", "parcel_held")]),
                      (ids[("phone", P.canonical)], ids[("claim", "parcel_held")])}
    assert sum(1 for r, *_ in rows if r == "co_occurs") == 10  # C(5,2)


def test_recall_scoped_by_user(db, register):
    u = U(uid(register, db))
    other = U(uid(register, db))
    llm = FakeLLM()
    for who, i, text in [(u, "1", "parcel held"), (u, "2", "bank kyc"), (other, "3", "parcel held")]:
        s = store_event(db, who, ev(i, text=text), text, [])
        store_embedding(db, who, s.id, llm.embed([text])[0])
    db.commit()
    got = recall(db, u, "parcel held", k=5, llm=llm)
    assert [g.redacted_text for g in got] == ["parcel held", "bank kyc"]
    assert len(recall(db, u, "parcel held", k=1, llm=llm)) == 1
    assert recall(db, U("nobody"), "parcel held", llm=llm) == []


def test_concurrent_opposite_order_no_deadlock(db, register):
    import threading
    from sqlalchemy.orm import sessionmaker
    uids = uid(register, db)
    Session = sessionmaker(bind=db.get_bind())
    ents = [Entity("phone", f"+9198765432{i:02d}") for i in range(12)]
    errors = []

    def work(name, order):
        try:
            with Session() as s:
                u = U(uids)
                for r in range(5):
                    barrier.wait(timeout=10)
                    st = store_event(s, u, ev(f"{name}{r}"), "x", order)
                    link(s, u, st, order)
                    s.commit()
        except Exception as e:  # noqa: BLE001
            errors.append(e)

    barrier = threading.Barrier(2)
    ts = [threading.Thread(target=work, args=("a", ents)),
          threading.Thread(target=work, args=("b", ents[::-1]))]
    [t.start() for t in ts]
    [t.join(30) for t in ts]
    assert not errors, errors
    assert db.scalar(select(func.count()).select_from(Edge).where(Edge.user_id == uids)) == 66


def test_related_time_types_limit_and_depth_order(db, register):
    u = U(uid(register, db))
    ingest(db, u, ev("1", minutes=0), "a", [P, DOM])
    ingest(db, u, ev("2", minutes=30), "b", [DOM, ORG])
    now = T0 + timedelta(minutes=10)
    assert related_entities(db, u, P, now=now) == [DOM]
    by = neighbors_by_depth(db, u, P)
    assert by[1] == [DOM] and by[2] == [ORG]
    assert related_entities(db, u, P) == [DOM, ORG]
    assert related_entities(db, u, P, types={"org"}) == [ORG]
    assert related_entities(db, u, P, limit=1) == [DOM]
    assert related_entities(db, u, P, depth=1) == [DOM]


def test_hub_damping(db, register):
    u = U(uid(register, db))
    ingest(db, u, ev("1"), "a", [P, DOM])
    for i in range(3):
        ingest(db, u, ev(f"h{i}"), "h", [DOM, Entity("org", f"o{i}")])
    by = neighbors_by_depth(db, u, P, hub_degree=2)
    assert by[1] == [DOM] and by[2] == []  # DOM has 4 edges > 2: returned but not expanded
    assert len(neighbors_by_depth(db, u, P)[2]) == 3


def test_recall_clamps_k_and_dedups(db, register):
    u = U(uid(register, db))
    llm = FakeLLM()
    s = store_event(db, u, ev("1", text="a"), "a", [])
    store_embedding(db, u, s.id, llm.embed(["a"])[0])
    store_embedding(db, u, s.id, llm.embed(["b"])[0])
    db.commit()
    assert len(recall(db, u, "a", k=0, llm=llm)) == 1
    assert len(recall(db, u, "a", k=999, llm=llm)) == 1


def test_history_exclude_and_naive_ts(db, register):
    u = U(uid(register, db))
    naive = EventIn(device_event_id="n", source="sms", ts=datetime(2026, 1, 1, 12, 0),
                    text="t", triage_score=0.1)
    s1 = ingest(db, u, naive, "n", [P])
    assert db.get(Event, s1.id).ts == T0
    s2 = ingest(db, u, ev("2", minutes=5), "m", [P])
    h = history(db, u, P, T0 + timedelta(minutes=5), exclude_event_id=s2.id)
    assert [i.event_id for i in h] == [s1.id]
