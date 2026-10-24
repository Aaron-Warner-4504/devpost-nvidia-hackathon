"""scripts/latency_check.py: statistics and event shapes (no server needed)."""
import importlib.util
from datetime import datetime, timezone
from pathlib import Path

from app.schemas import EventIn

spec = importlib.util.spec_from_file_location(
    "latency_check", Path(__file__).resolve().parent.parent / "scripts" / "latency_check.py")
lc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lc)


def test_percentile_nearest_rank():
    v = list(range(1, 101))
    assert lc.percentile(v, 50) == 50 and lc.percentile(v, 95) == 95 and lc.percentile([7], 95) == 7


def test_events_valid_and_unique():
    base = datetime(2026, 3, 1, tzinfo=timezone.utc)
    evs = [lc.make_event(m, i, base) for m in ("rules", "agent") for i in range(5)]
    for e in evs:
        EventIn.model_validate(e)
    assert len({e["device_event_id"] for e in evs}) == len(evs)
    assert all(e["triage_score"] < 0.2 for e in evs if e["device_event_id"].startswith("lat-rules"))


def test_runs_against_app(client, db, monkeypatch):
    """End to end with the TestClient transport: rules mode meets its threshold and exits 0."""
    from app.config import settings
    monkeypatch.setattr(settings, "agent_mode", "rules")
    h = {"Authorization": "Bearer " + client.post("/v1/devices/register").json()["token"]}
    s = lc.measure(client, h, "rules", 5, 1)
    assert len(s) == 5 and lc.report("rules", s)
