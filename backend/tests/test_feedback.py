"""Task 11: POST /v1/alerts/{id}/feedback -> alerts.user_label + per-user safe / known_bad overrides."""
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select

from app import feedback
from app.config import settings
from app.models import Alert, Assessment, Device, Override

D1 = datetime(2026, 3, 1, 9, 0, tzinfo=timezone.utc)
COURIER_SENDER = "+917700112233"
# authority_impersonation (2) + unknown_sender (1) = caution; repeated by a now-known sender: still caution (2);
# with the sender marked safe: 2 - 2 = info
COURIER_TEXT = "Customs department: your parcel is held. Please confirm your delivery address."


def sms(i, text=COURIER_TEXT, sender=COURIER_SENDER, ts=D1, source="sms"):
    return {"device_event_id": f"fb-{i}", "source": source, "ts": ts.isoformat(), "sender": sender,
            "text": text, "triage_score": 0.3, "lang": "en"}


@pytest.fixture(autouse=True)
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")


@pytest.fixture
def users(register, db):
    def _u():
        d, h = register()
        return db.get(Device, d).user_id, h
    return _u


def post(client, h, body, path="/v1/events"):
    r = client.post(path, json=body, headers=h)
    assert r.status_code == 200, r.text
    return r.json()


def tier_of(db, event_id):
    db.expire_all()
    return db.scalar(select(Assessment.tier).where(Assessment.event_id == event_id))


def fb(client, h, alert_id, label):
    return client.post(f"/v1/alerts/{alert_id}/feedback", json={"label": label}, headers=h)


def overrides(db, uid):
    db.expire_all()
    return {(o.kind, o.type, o.canonical) for o in db.scalars(select(Override).where(Override.user_id == uid))}


def test_safe_feedback_flips_next_identical_event_from_caution_to_info(client, users, db):
    # control user: without feedback the repeated message stays caution
    _, c = users()
    first = post(client, c, sms(1))
    assert first["alert"]["tier"] == "caution"
    again = post(client, c, sms(2, ts=D1 + timedelta(hours=7)))
    assert tier_of(db, again["event_id"]) == "caution"

    uid, h = users()
    first = post(client, h, sms(1))
    assert first["alert"]["tier"] == "caution"
    r = fb(client, h, first["alert"]["id"], "safe")
    assert r.status_code == 200 and r.json() == {"ok": True}
    nxt = post(client, h, sms(2, ts=D1 + timedelta(hours=7)))
    assert nxt["alert"] is None and tier_of(db, nxt["event_id"]) == "info"
    assert overrides(db, uid) == {("safe", "phone", COURIER_SENDER)}
    assert db.get(Alert, first["alert"]["id"]).user_label == "safe"


def test_safe_feedback_is_originator_only(client, users, db):
    uid, h = users()
    body_phone = "+919123456780"
    a = post(client, h, sms(1, text="Your parcel delivery is pending. Call 9123456780 urgently or visit "
                                    "http://parcel-help.in"))["alert"]
    assert fb(client, h, a["id"], "safe").status_code == 200
    ovr = overrides(db, uid)
    assert ovr == {("safe", "phone", COURIER_SENDER)}
    assert ("safe", "phone", body_phone) not in ovr and not any(t == "domain" for _, t, _ in ovr)


def test_safe_feedback_on_unauthenticated_email_pins_nothing(client, users, db):
    uid, h = users()
    a = post(client, h, sms(1, sender="Support <help@parcel-help.in>", source="email",
                            text="Your parcel delivery is pending. Please confirm your address urgently "
                                 "at http://parcel-help.in"))["alert"]
    assert a is not None
    assert fb(client, h, a["id"], "safe").status_code == 200
    assert overrides(db, uid) == set()  # From: is spoofable; no authenticated sender domain yet
    assert db.get(Alert, a["id"]).user_label == "safe"


def test_safe_feedback_on_call_pins_the_caller(client, users, db):
    uid, h = users()
    call = {"device_event_id": "call-1", "source": "call", "ts": D1.isoformat(), "sender": None, "text": None,
            "triage_score": 0.9, "call": {"number": "+91 98765 43210", "duration_s": 240, "is_contact": False,
                                          "direction": "in"}}
    r = post(client, h, call, path="/v1/check")
    assert fb(client, h, r["alert"]["id"], "safe").status_code == 200
    assert overrides(db, uid) == {("safe", "phone", "+919876543210")}


def test_scam_feedback_pins_known_bad_and_escalates_next_event(client, users, db):
    uid, h = users()
    a = post(client, h, sms(1, text="Your parcel delivery is pending. Call 9123456780 urgently or pay to "
                                    "parcel.fee@ybl via http://parcel-help.in"))["alert"]
    assert fb(client, h, a["id"], "scam").json() == {"ok": True}
    assert overrides(db, uid) == {("known_bad", "phone", COURIER_SENDER), ("known_bad", "phone", "+919123456780"),
                                  ("known_bad", "upi_id", "parcel.fee@ybl"),
                                  ("known_bad", "domain", "parcel-help.in")}
    # a different sender reusing the pinned UPI id: known_bad_entity (weight 7) -> critical
    r = post(client, h, sms(2, sender="+919000011111", text="Hello, kindly send it to parcel.fee@ybl",
                            ts=D1 + timedelta(hours=1)))
    assert r["alert"]["tier"] == "critical" and "known_bad_entity" in r["alert"]["signals"]
    assert db.get(Alert, a["id"]).user_label == "scam"


def test_scam_feedback_never_pins_brand_or_infra_domains(client, users, db):
    uid, h = users()
    text = ("Your HDFC account is blocked. Login at http://netbanking.hdfcbank.com or http://google.com or "
            "http://bit.ly/x or http://web.app or the form at http://hdfc-kyc-update.in urgently")
    a = post(client, h, sms(1, text=text))["alert"]
    assert fb(client, h, a["id"], "scam").status_code == 200
    doms = {c for k, t, c in overrides(db, uid) if t == "domain"}
    assert doms == {"hdfc-kyc-update.in"}
    assert not any(t == "org" for _, t, _ in overrides(db, uid))


@pytest.mark.parametrize("d,protected", [("hdfcbank.com", True), ("netbanking.hdfcbank.com", True),
                                         ("google.co.in", True), ("bit.ly", True), ("web.app", True),
                                         ("evil.web.app", False), ("hdfcbank-kyc.in", False)])
def test_brand_guard(d, protected):
    assert feedback.never_known_bad("domain", d) is protected


def test_feedback_is_idempotent_and_flips(client, users, db):
    uid, h = users()
    a = post(client, h, sms(1))["alert"]
    for _ in range(2):
        assert fb(client, h, a["id"], "safe").status_code == 200
    assert overrides(db, uid) == {("safe", "phone", COURIER_SENDER)}
    assert fb(client, h, a["id"], "scam").status_code == 200
    assert overrides(db, uid) == {("known_bad", "phone", COURIER_SENDER)}
    assert fb(client, h, a["id"], "scam").status_code == 200
    assert db.scalar(select(func.count()).select_from(Override).where(Override.user_id == uid)) == 1
    assert fb(client, h, a["id"], "safe").status_code == 200
    assert overrides(db, uid) == {("safe", "phone", COURIER_SENDER)}
    assert db.get(Alert, a["id"]).user_label == "safe"


def test_feedback_is_per_user(client, users, db):
    u1, h1 = users()
    u2, h2 = users()
    a1 = post(client, h1, sms(1))["alert"]
    post(client, h2, sms(1))
    assert fb(client, h1, a1["id"], "safe").status_code == 200
    assert overrides(db, u2) == set()
    r = post(client, h2, sms(2, ts=D1 + timedelta(hours=7)))
    assert tier_of(db, r["event_id"]) == "caution"  # user 2's identical repeat is unaffected


def test_foreign_or_unknown_alert_is_404(client, users, db):
    u1, h1 = users()
    u2, h2 = users()
    a1 = post(client, h1, sms(1))["alert"]
    r1 = fb(client, h2, a1["id"], "scam")
    r2 = fb(client, h2, "alr_doesnotexist", "scam")
    assert r1.status_code == r2.status_code == 404 and r1.json() == r2.json()
    assert overrides(db, u1) == overrides(db, u2) == set()
    assert db.get(Alert, a1["id"]).user_label is None


def test_feedback_validation_and_auth(client, users):
    _, h = users()
    a = post(client, h, sms(1))["alert"]
    assert fb(client, h, a["id"], "maybe").status_code == 422
    assert client.post(f"/v1/alerts/{a['id']}/feedback", json={"label": "safe"}).status_code == 401


def test_override_cap(client, users, db, monkeypatch):
    uid, h = users()
    monkeypatch.setattr(feedback, "MAX_OVERRIDES", 3)
    for i in range(3):
        db.add(Override(user_id=uid, kind="known_bad", type="phone", canonical=f"+91900000000{i}",
                        created_at=D1 + timedelta(minutes=i)))
    db.commit()
    a = post(client, h, sms(1))["alert"]
    assert fb(client, h, a["id"], "safe").status_code == 200
    ovr = overrides(db, uid)
    assert len(ovr) == 3 and ("safe", "phone", COURIER_SENDER) in ovr
    assert ("known_bad", "phone", "+919000000000") not in ovr  # the oldest was evicted
