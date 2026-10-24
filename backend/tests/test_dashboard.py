"""Task 16: demo dashboard page and GET /v1/dashboard/timeline."""
import re
from pathlib import Path

import pytest

from app.config import settings
from sim import inject

STATIC = Path(__file__).resolve().parent.parent / "app" / "static"


@pytest.fixture(autouse=True)
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")


@pytest.fixture
def demo(monkeypatch):
    monkeypatch.setattr(settings, "demo_enabled", True)


def seed(client, tmp_path, label="demo"):
    inject.run(label, "http://testserver", client=client, token_file=tmp_path / ".t", out=lambda s: None)
    import json
    tok = list(json.loads((tmp_path / ".t").read_text()).values())[-1]
    return {"Authorization": f"Bearer {tok}"}


def test_timeline_has_events_entities_edges_alerts(client, demo, tmp_path):
    h = seed(client, tmp_path)
    r = client.get("/v1/dashboard/timeline", headers=h)
    assert r.status_code == 200, r.text
    d = r.json()
    assert [e["source"] for e in d["events"]] == ["sms", "call", "whatsapp"]
    assert all({"id", "ts", "source", "redacted_text", "entities"} <= set(e) for e in d["events"])
    types = {e["type"] for e in d["entities"]}
    assert {"phone", "domain", "upi_id"} <= types
    ids = {e["id"] for e in d["entities"]}
    assert all(i in ids for e in d["events"] for i in e["entities"])
    assert d["edges"] and all({"a", "b", "relation", "evidence_event_ids"} <= set(g) for g in d["edges"])
    ev_ids = {e["id"] for e in d["events"]}
    assert all(set(g["evidence_event_ids"]) <= ev_ids for g in d["edges"])
    assert d["alerts"] and {"tier", "headline", "why", "signals", "created_at"} <= set(d["alerts"][0])
    assert d["latest"]["tier"] == d["alerts"][0]["tier"] in ("high", "critical")
    assert d["latest"]["why"]


def test_only_callers_data(client, demo, tmp_path, register):
    h = seed(client, tmp_path)
    _, h2 = register()
    other = {"device_event_id": "x1", "source": "sms", "ts": "2026-03-01T09:00:00+00:00",
             "sender": "+917711223344", "text": "Lunch at noon? ping me at other-user-secret.org",
             "triage_score": 0.1}
    assert client.post("/v1/events", json=other, headers=h2).status_code == 200
    mine = client.get("/v1/dashboard/timeline", headers=h).text
    assert "other-user-secret" not in mine and "x1" not in mine
    theirs = client.get("/v1/dashboard/timeline", headers=h2).json()
    assert len(theirs["events"]) == 1 and "bluedart" not in str(theirs)
    # there is no `user` parameter to widen the view
    assert "other-user-secret" not in client.get("/v1/dashboard/timeline?user=anyone", headers=h).text


def test_empty_timeline(client, demo, auth_headers):
    d = client.get("/v1/dashboard/timeline", headers=auth_headers).json()
    assert d["events"] == [] and d["alerts"] == [] and d["latest"] == {"tier": "info", "why": []}


def test_404_when_demo_disabled(client, auth_headers, monkeypatch):
    monkeypatch.setattr(settings, "demo_enabled", False)
    for path in ("/v1/dashboard/timeline", "/dashboard", "/dashboard/dashboard.js", "/dashboard/dashboard.css"):
        assert client.get(path, headers=auth_headers).status_code == 404
        assert client.get(path).status_code == 404  # no 401 hint that the route exists


def test_401_without_token(client, demo):
    assert client.get("/v1/dashboard/timeline").status_code == 401
    assert client.get("/v1/dashboard/timeline", headers={"Authorization": "Bearer nope"}).status_code == 401


@pytest.mark.parametrize("path", ["/dashboard", "/dashboard/dashboard.js", "/dashboard/dashboard.css"])
def test_security_headers(client, demo, path):
    r = client.get(path)
    assert r.status_code == 200
    csp = r.headers["content-security-policy"]
    assert "default-src 'self'" in csp and "frame-ancestors 'none'" in csp
    assert "unsafe-inline" not in csp and "unsafe-eval" not in csp
    assert r.headers["x-content-type-options"] == "nosniff"


def test_html_has_no_inline_script_or_remote_resources(client, demo):
    html = client.get("/dashboard").text
    scripts = re.findall(r"<script\b([^>]*)>(.*?)</script>", html, re.S | re.I)
    assert scripts and all("src=" in a and not body.strip() for a, body in scripts)
    assert not re.search(r"\son\w+\s*=", html, re.I) and "javascript:" not in html.lower()
    assert "style=" not in html.lower() and "<style" not in html.lower()
    assert not re.search(r"(?:src|href)=[\"']?(?:https?:)?//", html, re.I)
    assert "prefers-color-scheme: dark" in client.get("/dashboard/dashboard.css").text


def test_js_never_uses_html_injection_sinks():
    js = (STATIC / "dashboard.js").read_text()
    for sink in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function"):
        assert sink not in js
    assert not re.search(r"https?://(?!www\.w3\.org/2000/svg)", js)  # no CDN / external calls
