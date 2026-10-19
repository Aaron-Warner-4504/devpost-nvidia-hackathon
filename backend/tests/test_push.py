"""Task 17: push of high / critical alerts to the user's own devices."""
import pytest
from sqlalchemy import select

from app import family
from app.config import settings
from app.models import Device
from app.main import app
from app.routes.events import get_pipeline_deps

from tests.conftest import offline_deps
from tests.test_pipeline_e2e import D1, SCAMMER, WHATSAPP, COURIER_SMS, BANK_OTP, at

from app import push


@pytest.fixture(autouse=True)
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")


@pytest.fixture
def fake(client):
    n = family.FakeNotifier()
    deps = offline_deps()
    deps.hooks = [push.make_push_hook(lambda: n)]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    return n


def set_token(client, h, token="tok-own-1"):
    r = client.post("/v1/devices/fcm", json={"token": token}, headers=h)
    assert r.status_code == 200, r.text
    return r


def ev(client, h, body, path="/v1/events"):
    r = client.post(path, json=body, headers=h)
    assert r.status_code == 200, r.text
    return r.json()


CRIT = at(WHATSAPP, device_event_id="c1", ts=D1)
# known-bad number -> critical needs history; simplest critical: customs scam with UPI + threat
HIGH = {"device_event_id": "h1", "source": "sms", "ts": D1.isoformat(), "sender": "+917700112233",
        "text": "HDFC Bank: your KYC is expired, account will be blocked. Share the OTP now to verify.",
        "triage_score": 0.9}


def test_requires_auth_and_validates_token(client, auth_headers):
    assert client.post("/v1/devices/fcm", json={"token": "x"}).status_code == 401
    assert client.post("/v1/devices/fcm", json={"token": ""}, headers=auth_headers).status_code == 422
    assert client.post("/v1/devices/fcm", json={"token": "x" * 5000}, headers=auth_headers).status_code == 422


def test_token_stored_and_updated_on_that_device(client, register, db):
    d1, h1 = register()
    d2, h2 = register()
    set_token(client, h1, "t1")
    set_token(client, h1, "t2")
    db.expire_all()
    assert db.get(Device, d1).fcm_token == "t2"
    assert db.get(Device, d2).fcm_token is None
    assert "t2" not in client.get("/v1/me", headers=h1).text


def test_high_pushes_once_headline_only(client, auth_headers, fake):
    set_token(client, auth_headers)
    r = ev(client, auth_headers, HIGH)
    assert r["alert"]["tier"] in ("high", "critical")
    assert len(fake.sent) == 1
    m = fake.sent[0]
    assert m["token"] == "tok-own-1"
    assert m["data"] == {"kind": "own_alert", "alert_tier": r["alert"]["tier"]}
    assert len(m["body"]) <= 140
    assert "OTP now" not in m["body"] and HIGH["text"] not in m["body"]
    assert "9876" not in m["body"]


def test_duplicate_delivery_no_second_push(client, auth_headers, fake):
    set_token(client, auth_headers)
    ev(client, auth_headers, HIGH)
    r = ev(client, auth_headers, HIGH)
    assert r["duplicate"] is True
    assert len(fake.sent) == 1


def test_suppressed_duplicate_alert_no_push(client, auth_headers, fake):
    set_token(client, auth_headers)
    ev(client, auth_headers, HIGH)
    r = ev(client, auth_headers, {**HIGH, "device_event_id": "h2"})
    assert r["alert"] is None
    assert len(fake.sent) == 1


def test_critical_one_push(client, auth_headers, fake, monkeypatch):
    set_token(client, auth_headers)
    ev(client, auth_headers, COURIER_SMS)
    r = ev(client, auth_headers, CRIT)
    assert r["alert"]["tier"] == "critical"
    crit = [m for m in fake.sent if m["data"]["alert_tier"] == "critical"]
    assert len(crit) == 1


def test_caution_and_info_no_push(client, auth_headers, fake):
    set_token(client, auth_headers)
    r = ev(client, auth_headers, at(COURIER_SMS, device_event_id="cs"))
    assert r["alert"] is None or r["alert"]["tier"] == "caution"
    r = ev(client, auth_headers, {**COURIER_SMS, "device_event_id": "o", "text": BANK_OTP})
    assert fake.attempts == []


def test_check_never_pushes(client, auth_headers, fake):
    set_token(client, auth_headers)
    r = ev(client, auth_headers, HIGH, path="/v1/check")
    assert r["alert"]["tier"] in ("high", "critical")
    assert fake.attempts == []


def test_non_interrupting_ceiling_no_push(client, auth_headers, fake):
    set_token(client, auth_headers)
    import types
    from app.schemas import AlertOut
    from datetime import datetime, timezone
    alert = types.SimpleNamespace(tier="critical", headline="Scam")
    hook = push.make_push_hook(lambda: fake)
    user = types.SimpleNamespace(id="x")
    from app.pipeline.policy import Decision
    hook(None, user, alert, decision=Decision(show=True, interrupt=False, reason="critical_ceiling"))
    hook(None, user, alert, decision=Decision(show=True, interrupt=True, reason="user_check"))
    assert fake.attempts == []


def test_no_token_no_push(client, auth_headers, fake):
    ev(client, auth_headers, HIGH)
    assert fake.attempts == []


def test_notifier_raising_does_not_break(client, auth_headers, fake):
    set_token(client, auth_headers, "boom")
    fake.raise_tokens.add("boom")
    r = ev(client, auth_headers, HIGH)
    assert r["alert"] is not None
    assert len(fake.attempts) == 1


def test_user_isolation_and_all_own_devices(client, register, db, fake):
    d1, h1 = register()
    d1b = db.get(Device, d1).user_id
    _, h2 = register()
    set_token(client, h2, "other-user")
    set_token(client, h1, "mine")
    # a second device for user 1
    from app.auth import hash_token
    db.add(Device(user_id=d1b, token_hash=hash_token("second"), fcm_token="mine-2"))
    db.commit()
    ev(client, h1, HIGH)
    assert sorted(m["token"] for m in fake.sent) == ["mine", "mine-2"]


def test_unregistered_token_marks_invalid_and_stops(client, register, db, fake):
    d, h = register()
    set_token(client, h, "dead")
    fake.invalid_tokens.add("dead")
    ev(client, h, HIGH)
    db.expire_all()
    assert db.get(Device, d).fcm_invalid_at is not None
    ev(client, h, {**HIGH, "device_event_id": "h9", "text": HIGH["text"] + " Pay at once or lose it.",
                   "ts": "2026-03-03T09:00:00+00:00"})
    assert len(fake.attempts) == 1
    set_token(client, h, "fresh")  # re-registering clears the invalid mark
    db.expire_all()
    assert db.get(Device, d).fcm_invalid_at is None
