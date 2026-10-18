"""Task 11: family pairing (invites / accept / unlink) and critical-alert notifications to guardians."""
import threading
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, update

from app import family
from app.config import settings
from app.main import app
from app.models import Device, FamilyAcceptFailure, FamilyInvite, FamilyPing, TrustedContact
from app.pipeline import orchestrator
from app.pipeline.policy import Decision
from app.routes.events import get_pipeline_deps
from app.schemas import AlertOut

from tests.conftest import offline_deps

D1 = datetime(2026, 3, 1, 9, 0, tzinfo=timezone.utc)
SCAM_SENDER = "+919811100099"
SCAM_TEXT = ("CBI officer here. Share the OTP immediately. Do not tell anyone. "
             "Pay Rs 25,000 to customs.clear@ybl or call 9123456780.")
INVALID = {"detail": "invalid or expired code"}


def sms(i, text=SCAM_TEXT, sender=SCAM_SENDER, ts=D1):
    return {"device_event_id": f"fam-{i}", "source": "sms", "ts": ts.isoformat(), "sender": sender,
            "text": text, "triage_score": 0.9, "lang": "en"}


@pytest.fixture
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")


@pytest.fixture
def fake(client):
    """A FakeNotifier wired into the pipeline through an injected family hook."""
    n = family.FakeNotifier()
    deps = offline_deps()
    deps.hooks = [family.make_family_hook(lambda: n)]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    return n


@pytest.fixture
def users(register, db):
    """Callable: users() -> (user_id, headers)."""
    def _u():
        d, h = register()
        return db.get(Device, d).user_id, h
    return _u


def invite(client, h):
    r = client.post("/v1/family/invites", json={"consent": True}, headers=h)
    assert r.status_code == 200, r.text
    return r.json()


def accept(client, h, code, name="Ravi", token="tok-guardian", path="/v1/family/accept"):
    return client.post(path, json={"code": code, "name": name, "fcm_token": token}, headers=h)


def pair(client, protected_h, guardian_h, name="Ravi", token="tok-guardian"):
    r = accept(client, guardian_h, invite(client, protected_h)["code"], name=name, token=token)
    assert r.status_code == 200, r.text
    return r.json()["id"]


# ---------------------------------------------------------------- invites
def test_invite_requires_explicit_consent(client, users):
    _, h = users()
    for body in ({}, {"consent": False}, {"consent": "yes"}, {"consent": None}):
        assert client.post("/v1/family/invites", json=body, headers=h).status_code == 422, body
    assert client.post("/v1/family/invites", json={"consent": True}).status_code == 401


def test_invite_code_format_and_storage(client, users, db):
    uid, h = users()
    body = invite(client, h)
    assert set(body) == {"code", "expires_at"}
    code = body["code"]
    assert len(code) == 6 and all(c in family.CODE_ALPHABET for c in code)
    assert not set("0O1IL") & set(family.CODE_ALPHABET)
    exp = datetime.fromisoformat(body["expires_at"].replace("Z", "+00:00"))
    now = datetime.now(timezone.utc)
    assert timedelta(minutes=9) < exp - now <= timedelta(minutes=10, seconds=5)
    row = db.scalar(select(FamilyInvite).where(FamilyInvite.user_id == uid))
    assert row.code_hash != code and code not in row.code_hash and len(row.code_hash) == 64
    assert row.used_at is None


def test_invite_codes_are_random(client, users):
    codes = set()
    for _ in range(4):
        _, h = users()
        codes.add(invite(client, h)["code"])
    assert len(codes) == 4


def test_new_invite_invalidates_previous_unused(client, users):
    _, p = users()
    _, g = users()
    old = invite(client, p)["code"]
    new = invite(client, p)["code"]
    assert accept(client, g, old).json() == INVALID
    assert accept(client, g, new).status_code == 200


def test_invite_rate_limit_five_per_hour(client, users, monkeypatch):
    _, h = users()
    for _ in range(5):
        invite(client, h)
    r = client.post("/v1/family/invites", json={"consent": True}, headers=h)
    assert r.status_code == 429
    later = datetime.now(timezone.utc) + timedelta(hours=1, minutes=1)
    monkeypatch.setattr(family, "utcnow", lambda: later)
    assert client.post("/v1/family/invites", json={"consent": True}, headers=h).status_code == 200


# ---------------------------------------------------------------- accept
def test_accept_creates_link_without_leaking_protected_user(client, users, db):
    pid, p = users()
    gid, g = users()
    code = invite(client, p)["code"]
    r = accept(client, g, code.lower())  # codes are case-insensitive
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"id", "protected_name"} and body["protected_name"] is None
    assert body["id"].startswith("fam_") and pid not in r.text
    link = db.get(TrustedContact, body["id"])
    assert (link.protected_user_id, link.guardian_user_id, link.name, link.fcm_token) == (pid, gid, "Ravi",
                                                                                          "tok-guardian")
    assert link.consent_at is not None and link.fcm_invalid_at is None


def test_contacts_post_is_the_same_operation_as_accept(client, users, db):
    _, p = users()
    _, g = users()
    r = accept(client, g, invite(client, p)["code"], path="/v1/family/contacts")
    assert r.status_code == 200 and set(r.json()) == {"id", "protected_name"}
    assert db.scalar(select(func.count()).select_from(TrustedContact)) == 1


def test_code_is_single_use(client, users):
    _, p = users()
    _, g1 = users()
    _, g2 = users()
    code = invite(client, p)["code"]
    assert accept(client, g1, code).status_code == 200
    r = accept(client, g2, code)
    assert r.status_code == 404 and r.json() == INVALID


def test_code_expires_after_ten_minutes(client, users, monkeypatch):
    _, p = users()
    _, g = users()
    t0 = datetime.now(timezone.utc)
    monkeypatch.setattr(family, "utcnow", lambda: t0)
    code = invite(client, p)["code"]
    monkeypatch.setattr(family, "utcnow", lambda: t0 + timedelta(minutes=10))
    r = accept(client, g, code)
    assert r.status_code == 404 and r.json() == INVALID
    monkeypatch.setattr(family, "utcnow", lambda: t0 + timedelta(minutes=9, seconds=59))
    assert accept(client, g, code).status_code == 200


def test_wrong_expired_used_and_self_accept_are_identical_404(client, users, monkeypatch):
    _, p = users()
    _, g = users()
    wrong = accept(client, g, "ABCDEF")
    garbage = accept(client, g, "!!")
    own = invite(client, p)["code"]
    self_accept = accept(client, p, own)
    assert self_accept.status_code == 404
    assert accept(client, g, own).status_code == 200  # self-accept did not burn the code
    used = accept(client, g, own)
    for r in (wrong, garbage, self_accept, used):
        assert r.status_code == 404 and r.json() == INVALID


def test_failed_accept_rate_limit(client, users, db, monkeypatch):
    _, p = users()
    gid, g = users()
    for _ in range(family.MAX_ACCEPT_FAILURES):
        assert accept(client, g, "ABCDEF").status_code == 404
    code = invite(client, p)["code"]
    r = accept(client, g, code)  # even a correct code is refused while locked out
    assert r.status_code == 429
    assert db.scalar(select(FamilyInvite.used_at)) is None  # and the code was not consumed
    _, other = users()
    assert accept(client, other, code).status_code == 200  # per-guardian, not global
    later = datetime.now(timezone.utc) + timedelta(hours=1, minutes=1)
    monkeypatch.setattr(family, "utcnow", lambda: later)
    assert accept(client, g, "ABCDEF").status_code == 404  # window rolled over


def test_accept_validation(client, users):
    _, g = users()
    for body in ({"code": "ABCDEF", "name": "", "fcm_token": "t"},
                 {"code": "ABCDEF", "name": "x" * 81, "fcm_token": "t"},
                 {"code": "ABCDEF", "name": "R", "fcm_token": ""},
                 {"code": "ABCDEF", "name": "R"}):
        assert client.post("/v1/family/accept", json=body, headers=g).status_code == 422, body
    assert client.post("/v1/family/accept", json={"code": "A", "name": "R", "fcm_token": "t"}).status_code == 401


def test_concurrent_accept_exactly_one_winner(client, users, db):
    _, p = users()
    guardians = [users()[1] for _ in range(4)]
    code = invite(client, p)["code"]
    results, barrier = [], threading.Barrier(len(guardians))

    def go(h):
        c = TestClient(app)
        barrier.wait()
        results.append(accept(c, h, code))

    ts = [threading.Thread(target=go, args=(h,)) for h in guardians]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert sorted(r.status_code for r in results) == [200, 404, 404, 404]
    assert db.scalar(select(func.count()).select_from(TrustedContact)) == 1


def test_re_accept_by_same_guardian_updates_the_link(client, users, db):
    _, p = users()
    _, g = users()
    first = pair(client, p, g, token="old")
    db.execute(update(TrustedContact).values(fcm_invalid_at=datetime.now(timezone.utc)))
    db.commit()
    second = pair(client, p, g, name="Ravi K", token="new")
    assert first == second
    db.expire_all()
    link = db.get(TrustedContact, first)
    assert (link.name, link.fcm_token, link.fcm_invalid_at) == ("Ravi K", "new", None)


# ---------------------------------------------------------------- list / unlink
def test_list_contacts_both_roles(client, users):
    _, p = users()
    _, g1 = users()
    _, g2 = users()
    l1 = pair(client, p, g1, name="Ravi", token="secret-token-1")
    l2 = pair(client, p, g2, name="Meera", token="secret-token-2")
    r = client.get("/v1/family/contacts", headers=p)
    assert r.status_code == 200
    body = r.json()
    assert "secret-token" not in r.text
    assert {(x["id"], x["name"]) for x in body["guardians"]} == {(l1, "Ravi"), (l2, "Meera")}
    assert all(set(x) == {"id", "name", "linked_at"} for x in body["guardians"])
    assert body["protecting"] == []
    mine = client.get("/v1/family/contacts", headers=g1).json()
    assert mine["guardians"] == []
    assert [x["id"] for x in mine["protecting"]] == [l1]
    assert set(mine["protecting"][0]) == {"id", "linked_at"}


@pytest.mark.parametrize("side", ["protected", "guardian"])
def test_either_side_can_unlink(client, users, db, side):
    _, p = users()
    _, g = users()
    link = pair(client, p, g)
    r = client.delete(f"/v1/family/contacts/{link}", headers=p if side == "protected" else g)
    assert r.status_code == 204
    assert db.scalar(select(func.count()).select_from(TrustedContact)) == 0
    assert client.get("/v1/family/contacts", headers=p).json() == {"guardians": [], "protecting": []}


def test_unlink_by_non_participant_is_404(client, users, db):
    _, p = users()
    _, g = users()
    _, stranger = users()
    link = pair(client, p, g)
    r1 = client.delete(f"/v1/family/contacts/{link}", headers=stranger)
    r2 = client.delete("/v1/family/contacts/fam_doesnotexist", headers=stranger)
    assert r1.status_code == r2.status_code == 404 and r1.json() == r2.json()
    assert db.scalar(select(func.count()).select_from(TrustedContact)) == 1


# ---------------------------------------------------------------- notifications
def test_critical_alert_notifies_each_guardian_once_with_headline_only(client, users, fake, rules_mode):
    _, p = users()
    _, g1 = users()
    _, g2 = users()
    pair(client, p, g1, token="tok-1")
    pair(client, p, g2, token="tok-2")
    r = client.post("/v1/events", json=sms(1), headers=p)
    alert = r.json()["alert"]
    assert alert["tier"] == "critical"
    assert sorted(s["token"] for s in fake.sent) == ["tok-1", "tok-2"]
    for s in fake.sent:
        assert s["title"] == "Sentinel alert"
        assert s["data"] == {"kind": "family_critical", "alert_tier": "critical"}
        assert 0 < len(s["body"]) <= 140
        payload = s["title"] + s["body"] + repr(s["data"])
        for secret in ("CBI officer", "Share the OTP", SCAM_SENDER, "9811100099", "9123456780",
                       "customs.clear@ybl", "25,000", alert["id"], alert["event_id"]):
            assert secret not in payload, secret
        for sig in alert["signals"]:
            assert sig not in payload
    # the guardian's body is the (cleaned) headline, nothing else
    assert fake.sent[0]["body"] == family.family_body(alert["headline"])


def test_family_body_is_redacted_and_capped():
    b = family.family_body("Scam: call +91 98111 00099 or pay to customs.clear@ybl, OTP 482913 at "
                           "http://evil.example/x " + "very " * 60)
    assert len(b) <= 140
    for s in ("98111", "customs.clear@ybl", "482913", "evil.example"):
        assert s not in b
    assert family.family_body("") and family.family_body("   \n\t")  # never empty


def test_non_critical_and_non_interrupting_alerts_never_notify(client, users, db, rules_mode):
    pid, p = users()
    _, g = users()
    pair(client, p, g)
    n = family.FakeNotifier()
    user = db.get(family.User, pid)
    now = datetime.now(timezone.utc)
    for tier in ("info", "caution", "high"):
        a = AlertOut(id="alr_x", event_id="evt_x", tier=tier, headline="h", why=[], next_step="n", signals=[],
                     created_at=now)
        assert family.notify_family(db, user, a, decision=Decision(True, True, "shown"), notifier=n) == 0
    crit = AlertOut(id="alr_y", event_id="evt_y", tier="critical", headline="h", why=[], next_step="n",
                    signals=[], created_at=now)
    for d in (Decision(True, False, "user_check"), Decision(True, False, "critical_ceiling"),
              Decision(False, False, "critical_flood")):
        assert family.notify_family(db, user, crit, decision=d, notifier=n) == 0
    assert n.sent == []
    assert family.notify_family(db, user, crit, decision=Decision(True, True, "shown"), notifier=n) == 1


def test_check_and_lower_tiers_over_http_never_notify(client, users, fake, rules_mode):
    _, p = users()
    _, g = users()
    pair(client, p, g)
    r = client.post("/v1/check", json=sms(1), headers=p)
    assert r.json()["alert"]["tier"] == "critical"
    r = client.post("/v1/events", json=sms(2, "Pay Rs 500 fine immediately to avoid penalty.",
                                           "+919811100010"), headers=p)
    assert r.json()["alert"]["tier"] == "high"
    r = client.post("/v1/events", json=sms(3, "Your parcel delivery is pending. Please confirm your "
                                              "address urgently.", "+917700112233"), headers=p)
    assert r.json()["alert"]["tier"] == "caution"
    assert fake.sent == []


def test_suppressed_critical_duplicate_does_not_notify_again(client, users, fake, rules_mode):
    _, p = users()
    _, g = users()
    pair(client, p, g)
    assert client.post("/v1/events", json=sms(1), headers=p).json()["alert"]["tier"] == "critical"
    r = client.post("/v1/events", json=sms(2, ts=D1 + timedelta(minutes=2)), headers=p)
    assert r.json()["alert"] is None
    assert len(fake.sent) == 1


def test_only_the_alerted_users_guardians_are_notified(client, users, fake, rules_mode):
    _, p1 = users()
    _, p2 = users()
    _, g1 = users()
    _, g2 = users()
    pair(client, p1, g1, token="tok-1")
    pair(client, p2, g2, token="tok-2")
    client.post("/v1/events", json=sms(1), headers=p1)
    assert [s["token"] for s in fake.sent] == ["tok-1"]
    # a guardian's own alerts never go to the person they protect
    client.post("/v1/events", json=sms(2), headers=g1)
    assert [s["token"] for s in fake.sent] == ["tok-1"]


def test_after_unlink_no_notification(client, users, fake, rules_mode):
    _, p = users()
    _, g = users()
    link = pair(client, p, g)
    assert client.delete(f"/v1/family/contacts/{link}", headers=g).status_code == 204
    r = client.post("/v1/events", json=sms(1), headers=p)
    assert r.json()["alert"]["tier"] == "critical"
    assert fake.sent == []


def test_failing_notifier_isolated_per_guardian(client, users, fake, rules_mode, caplog):
    _, p = users()
    _, g1 = users()
    _, g2 = users()
    _, g3 = users()
    pair(client, p, g1, token="tok-raise")
    pair(client, p, g2, token="tok-fail")
    pair(client, p, g3, token="tok-ok")
    fake.raise_tokens.add("tok-raise")
    fake.fail_tokens.add("tok-fail")
    r = client.post("/v1/events", json=sms(1), headers=p)
    assert r.status_code == 200 and r.json()["alert"]["tier"] == "critical"
    assert {s["token"] for s in fake.sent} == {"tok-ok"}
    assert {a["token"] for a in fake.attempts} == {"tok-raise", "tok-fail", "tok-ok"}
    assert "tok-raise" not in caplog.text and "tok-fail" not in caplog.text


def test_unregistered_token_marks_contact_and_stops_sending(client, users, db, fake, rules_mode):
    _, p = users()
    _, g = users()
    link = pair(client, p, g, token="tok-dead")
    fake.invalid_tokens.add("tok-dead")
    client.post("/v1/events", json=sms(1), headers=p)
    db.expire_all()
    assert db.get(TrustedContact, link).fcm_invalid_at is not None
    client.post("/v1/events", json=sms(2, sender="+919811100055", ts=D1 + timedelta(minutes=1)), headers=p)
    assert [a["token"] for a in fake.attempts] == ["tok-dead"]


def test_per_guardian_throttle(client, users, db, monkeypatch):
    pid, p = users()
    pid2, p2 = users()
    gid, g = users()
    pair(client, p, g, token="tok-g")
    pair(client, p2, g, token="tok-g2")
    n = family.FakeNotifier()
    user = db.get(family.User, pid)
    user2 = db.get(family.User, pid2)
    t0 = datetime.now(timezone.utc)
    monkeypatch.setattr(family, "utcnow", lambda: t0)
    ok = Decision(True, True, "shown")

    def crit(i):
        return AlertOut(id=f"alr_{i}", event_id=f"evt_{i}", tier="critical", headline="Scam call", why=[],
                        next_step="n", signals=[], created_at=t0)

    for i in range(family.FAMILY_PINGS_PER_HOUR - 1):
        family.notify_family(db, user, crit(i), decision=ok, notifier=n)
    family.notify_family(db, user2, crit(99), decision=ok, notifier=n)  # counts for the same guardian
    family.notify_family(db, user, crit(100), decision=ok, notifier=n)
    assert len(n.sent) == family.FAMILY_PINGS_PER_HOUR
    monkeypatch.setattr(family, "utcnow", lambda: t0 + timedelta(hours=1, seconds=1))
    family.notify_family(db, user, crit(101), decision=ok, notifier=n)
    assert len(n.sent) == family.FAMILY_PINGS_PER_HOUR + 1
    assert db.scalar(select(func.count()).select_from(FamilyPing).where(FamilyPing.user_id == gid)) <= \
        family.FAMILY_PINGS_PER_HOUR


def test_family_hook_registered_on_startup(monkeypatch):
    assert family.family_alert_hook not in orchestrator.alert_hooks  # not at import time
    with TestClient(app):
        assert family.family_alert_hook in orchestrator.alert_hooks
    assert family.family_alert_hook not in orchestrator.alert_hooks


def test_get_notifier_without_credentials_is_noop(monkeypatch, tmp_path):
    family.get_notifier.cache_clear()
    monkeypatch.setattr(settings, "firebase_credentials_file", "")
    assert isinstance(family.get_notifier(), family.NoopNotifier)
    family.get_notifier.cache_clear()
    monkeypatch.setattr(settings, "firebase_credentials_file", str(tmp_path / "missing.json"))
    assert isinstance(family.get_notifier(), family.NoopNotifier)
    family.get_notifier.cache_clear()
    assert family.NoopNotifier().send("t", "x", "y", {}) is False


def test_fcm_notifier_maps_errors_to_false(monkeypatch):
    """FCMNotifier never raises: unregistered tokens are reported via token_invalid()."""
    from firebase_admin import exceptions as fx, messaging

    n = family.FCMNotifier.__new__(family.FCMNotifier)
    n._app = object()
    n._invalid = {}

    def boom(exc):
        def send(msg, app=None):
            raise exc
        return send

    monkeypatch.setattr(messaging, "send", boom(messaging.UnregisteredError("gone")))
    assert n.send("tok", "t", "b", {"k": "v"}) is False and n.token_invalid("tok") is True
    monkeypatch.setattr(messaging, "send", boom(fx.UnavailableError("down")))
    assert n.send("tok2", "t", "b") is False and n.token_invalid("tok2") is False
    monkeypatch.setattr(messaging, "send", boom(RuntimeError("weird")))
    assert n.send("tok3", "t", "b") is False
    monkeypatch.setattr(messaging, "send", lambda msg, app=None: "projects/x/messages/1")
    assert n.send("tok4", "t", "b", {"kind": "family_critical"}) is True


# ---------------------------------------------------------------- deletion
def _rows_for(db, uid):
    from tests.test_auth_me import counts
    return counts(db, uid)


@pytest.mark.parametrize("who", ["protected", "guardian"])
def test_delete_me_removes_links_for_either_role(client, users, db, who):
    pid, p = users()
    gid, g = users()
    oid, o = users()
    _, og = users()
    pair(client, p, g)
    invite(client, p)  # an outstanding invite
    accept(client, g, "ABCDEF")  # a failure row for the guardian
    pair(client, o, og)  # unrelated pair
    db.add(FamilyPing(user_id=gid))
    db.commit()
    before_other = _rows_for(db, oid)
    gone, gone_h = (pid, p) if who == "protected" else (gid, g)
    assert client.delete("/v1/me", headers=gone_h).status_code == 204
    db.expire_all()
    rows = _rows_for(db, gone)
    assert all(v == 0 for v in rows.values()), rows
    assert db.scalar(select(func.count()).select_from(TrustedContact)) == 1  # only the unrelated pair
    assert _rows_for(db, oid) == before_other
    survivor = g if who == "protected" else p
    assert client.get("/v1/family/contacts", headers=survivor).json() == {"guardians": [], "protecting": []}
    assert db.scalar(select(func.count()).select_from(FamilyAcceptFailure)) == (1 if who == "protected" else 0)
