import hashlib

from sqlalchemy import func, select, text

from app.db import Base
from app.models import Consent, Device, Event, User


def counts(db, user_id):
    out = {}
    for t in Base.metadata.sorted_tables:
        if "user_id" in t.c:
            out[t.name] = db.execute(
                select(func.count()).select_from(t).where(t.c.user_id == user_id)
            ).scalar_one()
    return out


def seed(db, user_id):
    """Insert one row into every user-owned table."""
    from app.models import (Alert, Assessment, Edge, Embedding, Entity, EventEntity, Override,
                            TrustedContact)
    from datetime import datetime, timezone
    from app.config import settings

    ev = Event(user_id=user_id, device_event_id="d1", source="sms",
               ts=datetime.now(timezone.utc), redacted_text="hi [PHONE]", hash="h")
    e1 = Entity(user_id=user_id, type="phone", canonical="+1")
    e2 = Entity(user_id=user_id, type="domain", canonical="x.co")
    db.add_all([ev, e1, e2])
    db.flush()
    a = Assessment(user_id=user_id, event_id=ev.id, tier="high", signals=["urgency"],
                   explanation="x", model="m", latency=0.1, degraded=False)
    db.add_all([
        Edge(user_id=user_id, a=e1.id, b=e2.id, relation="linked",
             valid_from=datetime.now(timezone.utc), evidence_event_ids=[ev.id]),
        Embedding(user_id=user_id, event_id=ev.id, vector=[0.1] * settings.embed_dim),
        EventEntity(user_id=user_id, event_id=ev.id, entity_id=e1.id),
        TrustedContact(user_id=user_id, name="Mom", phone="+2"),
        Override(user_id=user_id, kind="safe", type="domain", canonical="ok.com"),
        Consent(user_id=user_id, source="sms", granted_at=datetime.now(timezone.utc)),
        a,
    ])
    db.flush()
    db.add(Alert(user_id=user_id, assessment_id=a.id))
    db.commit()


def test_register_returns_token_and_stores_only_hash(client, db):
    r = client.post("/v1/devices/register")
    assert r.status_code == 200
    body = r.json()
    assert body["token"] and body["device_id"]
    dev = db.get(Device, body["device_id"])
    assert dev.token_hash == hashlib.sha256(body["token"].encode()).hexdigest()
    assert dev.token_hash != body["token"]
    assert not any(body["token"] in str(v) for v in db.execute(text("select * from devices")).all()[0])


def test_token_authenticates_and_bad_token_rejected(client, register):
    _, h = register()
    assert client.get("/v1/me", headers=h).status_code == 200
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Basic abc"}).status_code == 401


def test_me_returns_user_and_consents(client, register):
    _, h = register()
    assert client.post("/v1/me/consents", json={"source": "sms", "granted": True}, headers=h).status_code == 200
    me = client.get("/v1/me", headers=h).json()
    assert me["id"].startswith("usr_")
    assert me["consents"] == {"sms": True}
    client.post("/v1/me/consents", json={"source": "sms", "granted": False}, headers=h)
    assert client.get("/v1/me", headers=h).json()["consents"] == {"sms": False}


def test_delete_me_cascades_and_isolates_other_user(client, register, db):
    d1, h1 = register()
    d2, h2 = register()
    u1 = db.get(Device, d1).user_id
    u2 = db.get(Device, d2).user_id
    seed(db, u1)
    seed(db, u2)
    before2 = counts(db, u2)
    assert all(v >= 1 for k, v in counts(db, u1).items() if k != "users"), counts(db, u1)

    assert client.delete("/v1/me", headers=h1).status_code == 204
    db.expire_all()
    c1 = counts(db, u1)
    assert set(c1) >= {"devices", "consents", "events", "entities", "edges", "assessments",
                       "alerts", "trusted_contacts", "embeddings", "overrides", "event_entities"}
    assert all(v == 0 for v in c1.values()), c1
    assert db.get(User, u1) is None
    assert counts(db, u2) == before2
    assert db.get(User, u2) is not None
    assert client.get("/v1/me", headers=h1).status_code == 401
    assert client.get("/v1/me", headers=h2).status_code == 200


def test_events_unique_per_user(db, register, client):
    import pytest
    from datetime import datetime, timezone
    from sqlalchemy.exc import IntegrityError
    d, _ = register()
    uid = db.get(Device, d).user_id
    for _i in range(2):
        db.add(Event(user_id=uid, device_event_id="same", source="sms",
                     ts=datetime.now(timezone.utc), redacted_text="x", hash="h"))
    with pytest.raises(IntegrityError):
        db.commit()
