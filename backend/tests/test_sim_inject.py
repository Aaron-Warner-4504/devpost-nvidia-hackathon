"""Task 13 step 1: the demo simulator against the TestClient-backed app."""
import pytest
from sqlalchemy import func, select

from app.config import settings
from app.models import Alert, Event, User
from sim import inject


@pytest.fixture(autouse=True)
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")


def test_timeline_idempotent(client, db, tmp_path):
    tf = tmp_path / ".demo_token"
    out = []
    first = inject.run("demo", "http://testserver", client=client, token_file=tf, out=out.append)
    assert [r["duplicate"] for r in first] == [False] * 3
    tiers = [r["alert"]["tier"] for r in first if r["alert"]]
    assert tiers and tiers[-1] in ("high", "critical")
    assert any("CRITICAL" in l or "HIGH" in l for l in out)
    n_alerts = db.scalar(select(func.count()).select_from(Alert))
    n_events = db.scalar(select(func.count()).select_from(Event))

    out2 = []
    second = inject.run("demo", "http://testserver", client=client, token_file=tf, out=out2.append)
    assert [r["event_id"] for r in second] == [r["event_id"] for r in first]
    assert all(r["duplicate"] and r["alert"] is None for r in second)
    assert all("duplicate" in l for l in out2)
    db.expire_all()
    assert db.scalar(select(func.count()).select_from(Alert)) == n_alerts
    assert db.scalar(select(func.count()).select_from(Event)) == n_events
    assert db.scalar(select(func.count()).select_from(User)) == 1


def test_reset_recreates_account(client, db, tmp_path):
    tf = tmp_path / ".demo_token"
    inject.run("demo", "http://testserver", client=client, token_file=tf, out=lambda s: None)
    old = db.scalar(select(User.id))
    again = inject.run("demo", "http://testserver", client=client, token_file=tf, reset=True,
                       out=lambda s: None)
    assert all(not r["duplicate"] for r in again)
    db.expire_all()
    ids = db.scalars(select(User.id)).all()
    assert len(ids) == 1 and ids[0] != old


def test_stale_token_registers_again(client, db, tmp_path):
    tf = tmp_path / ".demo_token"
    tf.write_text('{"http://testserver|demo": "not-a-real-token"}')
    res = inject.run("demo", "http://testserver", client=client, token_file=tf, out=lambda s: None)
    assert len(res) == 3 and "not-a-real-token" not in tf.read_text()
