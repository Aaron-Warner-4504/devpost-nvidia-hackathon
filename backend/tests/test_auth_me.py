import hashlib

from sqlalchemy import func, select, text

from app.db import Base
from app.models import Consent, Device, Event, User


def user_columns():
    """Every (table, column) that references users.id (user_id, protected_user_id, guardian_user_id...)."""
    cols = []
    for t in Base.metadata.sorted_tables:
        for c in t.c:
            if any(fk.column.table.name == "users" for fk in c.foreign_keys):
                cols.append((t, c))
    return cols


def counts(db, user_id):
    """Rows per table that belong to this user through ANY users.id foreign key. Keyed by table name
    for plain `user_id` columns (as before) and `table.column` for other user references."""
    out = {}
    for t, c in user_columns():
        key = t.name if c.name == "user_id" else f"{t.name}.{c.name}"
        out[key] = db.execute(select(func.count()).select_from(t).where(c == user_id)).scalar_one()
    return out


# Tables that hold no user data and need no cascade. Each entry must justify itself.
NOT_USER_OWNED = {
    "rate_limit_events",  # pre-auth counters keyed by a sha256 of the client IP; purged after 24 h
}


def test_every_table_is_owned_by_a_user():
    """A new table must reference users (so DELETE /v1/me cascades to it) or be listed above."""
    owned = {t.name for t, _ in user_columns()}
    for t in Base.metadata.sorted_tables:
        assert t.name == "users" or t.name in owned or t.name in NOT_USER_OWNED, t.name
    for name in NOT_USER_OWNED:  # exempt tables must really have no user-identifying columns
        cols = {c.name for c in Base.metadata.tables[name].c}
        assert not cols & {"user_id", "device_id", "phone", "ip", "token"}, (name, cols)
    for t, c in user_columns():
        assert all(fk.ondelete == "CASCADE" for fk in c.foreign_keys), (t.name, c.name)


def seed(db, user_id):
    """Insert one row into every user-owned table."""
    from app.models import (Alert, Assessment, Edge, Embedding, Entity, EventEntity, EventJob, FamilyAcceptFailure,
                            FamilyInvite, FamilyPing, Override, TrustedContact, User)
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
        FamilyInvite(user_id=user_id, code_hash=f"h-{user_id}",
                     expires_at=datetime.now(timezone.utc)),
        FamilyAcceptFailure(user_id=user_id),
        FamilyPing(user_id=user_id),
        Override(user_id=user_id, kind="safe", type="domain", canonical="ok.com"),
        Consent(user_id=user_id, source="sms", granted_at=datetime.now(timezone.utc)),
        EventJob(user_id=user_id, event_id=ev.id, state="queued", payload={"event": {}}),
        a,
    ])
    db.flush()
    db.add(Alert(user_id=user_id, assessment_id=a.id))
    # this user in BOTH family roles: protecting someone and protected by someone
    other_a, other_b = User(), User()
    db.add_all([other_a, other_b])
    db.flush()
    db.add_all([
        TrustedContact(protected_user_id=user_id, guardian_user_id=other_a.id, name="Mom", fcm_token="t1",
                       consent_at=datetime.now(timezone.utc)),
        TrustedContact(protected_user_id=other_b.id, guardian_user_id=user_id, name="Son", fcm_token="t2",
                       consent_at=datetime.now(timezone.utc)),
    ])
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
                       "alerts", "embeddings", "overrides", "event_entities", "family_invites",
                       "family_accept_failures", "family_pings", "trusted_contacts.protected_user_id",
                       "trusted_contacts.guardian_user_id"}
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


# ---------------------------------------------------------------- registration rate limit (per client IP)
def _client_from(ip):
    from fastapi.testclient import TestClient
    from app.main import app
    return TestClient(app, client=(ip, 5000))


def test_register_rate_limited_per_ip(client, monkeypatch, db):
    from app.config import settings
    from app.models import RateLimitEvent
    monkeypatch.setattr(settings, "register_per_ip_per_hour", 20)
    a = _client_from("203.0.113.7")
    for _ in range(20):
        assert a.post("/v1/devices/register").status_code == 200
    r = a.post("/v1/devices/register")
    assert r.status_code == 429 and r.json() == {"detail": "too many requests, try again later"}
    assert _client_from("203.0.113.8").post("/v1/devices/register").status_code == 200  # other IP fine
    # the raw IP is never stored
    keys = db.scalars(select(RateLimitEvent.key)).all()
    assert keys and not any("203.0.113" in k for k in keys)


def test_register_ignores_forwarded_for_unless_trusted(client, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "register_per_ip_per_hour", 2)
    a = _client_from("10.0.0.1")
    for i in range(2):
        assert a.post("/v1/devices/register", headers={"X-Forwarded-For": f"198.51.100.{i}"}).status_code == 200
    # spoofed XFF does not help when proxy headers are not trusted
    assert a.post("/v1/devices/register", headers={"X-Forwarded-For": "198.51.100.99"}).status_code == 429
    monkeypatch.setattr(settings, "trust_proxy_headers", True)
    # behind our proxy the RIGHTMOST entry (appended by the proxy) is the client; a forged left part is ignored
    assert a.post("/v1/devices/register", headers={"X-Forwarded-For": "10.0.0.1, 198.51.100.99"}).status_code == 200
    assert a.post("/v1/devices/register", headers={"X-Forwarded-For": "198.51.100.5, 10.0.0.1"}).status_code == 429


def test_register_window_and_cleanup(client, monkeypatch, db):
    from datetime import datetime, timedelta, timezone
    from app import ratelimit
    from app.config import settings
    from app.models import RateLimitEvent
    monkeypatch.setattr(settings, "register_per_ip_per_hour", 1)
    a = _client_from("203.0.113.9")
    t0 = datetime.now(timezone.utc)
    monkeypatch.setattr(ratelimit, "utcnow", lambda: t0)
    assert a.post("/v1/devices/register").status_code == 200
    assert a.post("/v1/devices/register").status_code == 429
    monkeypatch.setattr(ratelimit, "utcnow", lambda: t0 + timedelta(hours=1, seconds=1))
    assert a.post("/v1/devices/register").status_code == 200
    monkeypatch.setattr(ratelimit, "utcnow", lambda: t0 + timedelta(hours=25))
    assert a.post("/v1/devices/register").status_code == 200
    db.expire_all()
    ts = db.scalars(select(RateLimitEvent.created_at)).all()
    assert all(t > t0 + timedelta(hours=1) for t in ts), ts  # rows older than 24h were purged
