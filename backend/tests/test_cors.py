import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.cors import apply_cors, parse_cors_origins

ORIGIN = "http://localhost:5173"
PREFLIGHT = {
    "Origin": ORIGIN,
    "Access-Control-Request-Method": "POST",
    "Access-Control-Request-Headers": "authorization,content-type",
}


def make_client(raw, env="dev"):
    app = FastAPI()

    @app.get("/health")
    def health():
        return {"status": "ok"}

    apply_cors(app, raw, env)
    return TestClient(app)


def test_default_has_no_cors_headers(client):
    r = client.get("/health", headers={"Origin": ORIGIN})
    assert r.status_code == 200
    assert not any(k.lower().startswith("access-control-") for k in r.headers)
    r = client.options("/health", headers=PREFLIGHT)
    assert "access-control-allow-origin" not in r.headers


def test_allowed_origin_preflight():
    r = make_client(ORIGIN).options("/health", headers=PREFLIGHT)
    assert r.status_code == 200
    assert r.headers["access-control-allow-origin"] == ORIGIN
    assert set(r.headers["access-control-allow-methods"].split(", ")) == {"GET", "POST", "DELETE", "OPTIONS"}
    allowed = set(r.headers["access-control-allow-headers"].lower().split(", "))
    assert {"authorization", "content-type"} <= allowed and "*" not in allowed
    # a header outside the list is refused
    bad = make_client(ORIGIN).options("/health", headers={**PREFLIGHT, "Access-Control-Request-Headers": "x-evil"})
    assert bad.status_code == 400
    assert r.headers["access-control-max-age"] == "600"
    assert "access-control-allow-credentials" not in r.headers


def test_simple_request_from_allowed_origin_has_no_exposed_headers():
    r = make_client(ORIGIN).get("/health", headers={"Origin": ORIGIN})
    assert r.headers["access-control-allow-origin"] == ORIGIN
    assert "access-control-expose-headers" not in r.headers


def test_disallowed_origin_gets_nothing():
    c = make_client(ORIGIN)
    r = c.options("/health", headers={**PREFLIGHT, "Origin": "http://evil.example"})
    assert "access-control-allow-origin" not in r.headers
    r = c.get("/health", headers={"Origin": "http://evil.example"})
    assert "access-control-allow-origin" not in r.headers


def test_wildcard_rejected_in_prod_accepted_in_dev():
    with pytest.raises(ValueError, match=r"\*"):
        parse_cors_origins("*", "prod")
    assert parse_cors_origins("*", "dev") == ["*"]
    assert parse_cors_origins("*", "test") == ["*"]


def test_prod_requires_https():
    with pytest.raises(ValueError, match="https"):
        parse_cors_origins("https://a.example,http://b.example", "prod")
    assert parse_cors_origins("https://a.example, https://b.example/", "prod") == [
        "https://a.example", "https://b.example"]


def test_malformed_origin_rejected():
    with pytest.raises(ValueError):
        parse_cors_origins("localhost:5173", "dev")
    assert parse_cors_origins("", "prod") == []
