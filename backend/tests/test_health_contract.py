
PATHS = [
    "/v1/devices/register",
    "/v1/events",
    "/v1/check",
    "/v1/alerts",
    "/v1/alerts/{id}/feedback",
    "/v1/family/contacts",
    "/v1/me",
    "/health",
]

EVENT = {
    "device_event_id": "dev-1",
    "source": "sms",
    "ts": "2026-10-17T10:00:00Z",
    "sender": "+911234567890",
    "text": "Your account is blocked, click http://x.co",
    "call": None,
    "triage_score": 0.8,
    "lang": "en",
}


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_events_requires_auth(client):
    assert client.post("/v1/events", json=EVENT).status_code == 401


def test_events_with_token_returns_result(client, auth_headers):
    r = client.post("/v1/events", json=EVENT, headers=auth_headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"event_id", "duplicate", "alert"} and body["duplicate"] is False
    assert body["alert"] is None or body["alert"]["tier"] in ("info", "caution", "high", "critical")
    # /v1/check always returns an alert
    r = client.post("/v1/check", json={**EVENT, "device_event_id": "dev-2"}, headers=auth_headers)
    assert r.status_code == 200
    assert r.json()["alert"]["tier"] in ("info", "caution", "high", "critical")


def test_register_unauthenticated(client):
    r = client.post("/v1/devices/register")
    assert r.status_code == 200
    assert set(r.json()) == {"device_id", "token"}


def test_openapi_has_all_paths(client):
    paths = client.get("/openapi.json").json()["paths"]
    for p in PATHS:
        assert p in paths, p


def test_oversized_text_rejected(client, auth_headers):
    r = client.post("/v1/events", json={**EVENT, "text": "x" * 10001}, headers=auth_headers)
    assert r.status_code == 422
    r = client.post("/v1/events", json={**EVENT, "sender": "x" * 201}, headers=auth_headers)
    assert r.status_code == 422
