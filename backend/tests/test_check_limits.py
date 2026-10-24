"""Per-user rate limit and body cap on POST /v1/check."""
import pytest

from app.config import settings
from app.llm import FakeLLM, get_llm
from app.main import app

from tests.test_health_contract import EVENT

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


@pytest.fixture(autouse=True)
def low_limits(client, monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")
    monkeypatch.setattr(settings, "vision_model", "vision-x")
    monkeypatch.setattr(settings, "check_rate_limit_per_hour", 30)
    monkeypatch.setattr(settings, "check_image_rate_limit_per_hour", 10)


@pytest.fixture
def llm(client):
    fake = FakeLLM()
    app.dependency_overrides[get_llm] = lambda: fake
    return fake


def jcheck(client, h, i):
    return client.post("/v1/check", json={**EVENT, "device_event_id": f"lim-{i}"}, headers=h)


def icheck(client, h):
    return client.post("/v1/check", files={"image": ("s.png", PNG, "image/png")}, headers=h)


def test_31st_json_check_is_429(client, auth_headers):
    for i in range(30):
        assert jcheck(client, auth_headers, i).status_code == 200
    r = jcheck(client, auth_headers, 30)
    assert r.status_code == 429 and r.json() == {"detail": "rate limit exceeded"}


def test_11th_image_check_429_before_vision_model(client, auth_headers, llm):
    for _ in range(10):
        assert icheck(client, auth_headers).status_code != 429
    n = len(llm.calls)
    r = icheck(client, auth_headers)
    assert r.status_code == 429 and r.json() == {"detail": "rate limit exceeded"}
    assert len(llm.calls) == n


def test_json_and_image_counters_are_separate(client, auth_headers, llm, monkeypatch):
    monkeypatch.setattr(settings, "check_image_rate_limit_per_hour", 1)
    icheck(client, auth_headers)
    assert icheck(client, auth_headers).status_code == 429
    assert jcheck(client, auth_headers, 1).status_code == 200


def test_per_user_isolation(client, register):
    _, a = register()
    _, b = register()
    for i in range(30):
        jcheck(client, a, i)
    assert jcheck(client, a, 99).status_code == 429
    assert jcheck(client, b, 1).status_code == 200


def test_oversized_json_is_413(client, auth_headers):
    r = client.post("/v1/check", content=b'{"text": "' + b"a" * 70_000 + b'"}',
                    headers={**auth_headers, "Content-Type": "application/json"})
    assert r.status_code == 413


def test_oversized_chunked_json_is_413(client, auth_headers):
    def gen():
        for _ in range(80):
            yield b" " * 1024
    r = client.post("/v1/check", content=gen(), headers={**auth_headers, "Content-Type": "application/json"})
    assert r.status_code == 413


def test_normal_check_unaffected(client, auth_headers):
    r = jcheck(client, auth_headers, 1)
    assert r.status_code == 200 and r.json()["alert"]
