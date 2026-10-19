"""Task 14: screenshot / image check through POST /v1/check (multipart)."""
import json

import pytest
from sqlalchemy import select, text as sql

from app.config import settings
from app.llm import ChatResult, FakeLLM, LLMUnavailable, get_llm
from app.main import app
from app.models import Event
from app.pipeline import ocr

from tests.test_pipeline_e2e import COURIER_SMS, final

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
SCAM = ("Dear customer your HDFC account will be blocked today. Share the OTP 482913 with the "
        "officer on 9876501234 now.")


@pytest.fixture(autouse=True)
def rules_mode(monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")
    monkeypatch.setattr(settings, "vision_model", "vision-x")


@pytest.fixture
def llm(client):
    fake = FakeLLM()
    app.dependency_overrides[get_llm] = lambda: fake
    return fake


def up(client, headers, data=b"", name="shot.png", ctype="image/png", fields=None, files=True):
    files_arg = {"image": (name, data, ctype)} if files else None
    return client.post("/v1/check", files=files_arg, data=fields or {}, headers=headers)


def test_image_text_processed_as_share(client, auth_headers, llm, db):
    llm.script = [ChatResult(content=SCAM), final("high", "Bank OTP scam")]
    r = up(client, auth_headers, PNG, fields={"device_event_id": "img-1", "lang": "en", "triage_score": "0.7",
                                              "ts": "2026-03-01T09:00:00+00:00"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["alert"] is not None and body["duplicate"] is False
    ev = db.scalar(select(Event).where(Event.id == body["event_id"]))
    assert ev.source == "share" and ev.device_event_id == "img-1"
    assert "482913" not in (ev.redacted_text or "")
    assert "482913" not in json.dumps(r.json())


def test_defaults_when_optional_fields_omitted(client, auth_headers, llm):
    llm.script = [ChatResult(content=SCAM), final("high")]
    r = up(client, auth_headers, PNG)
    assert r.status_code == 200, r.text


def test_llm_call_has_image_part_and_no_scam_text_in_system(client, auth_headers, llm):
    llm.script = [ChatResult(content=SCAM), final("high")]
    up(client, auth_headers, PNG)
    call = llm.calls[0]
    assert call["model"] == "vision-x" and not call["tools"]
    msgs = call["messages"]
    parts = [p for m in msgs if isinstance(m["content"], list) for p in m["content"]]
    img = [p for p in parts if p["type"] == "image_url"]
    assert len(img) == 1 and img[0]["image_url"]["url"].startswith("data:image/png;base64,")
    for m in msgs:
        if m["role"] == "system":
            assert isinstance(m["content"], str) and "482913" not in m["content"]
    assert "482913" not in json.dumps([m for m in msgs if m["role"] == "system"])


def test_extract_text_unit():
    f = FakeLLM([ChatResult(content="  hello  ")])
    assert ocr.extract_text_from_image(f, PNG) == "hello"
    f = FakeLLM([ChatResult(content="x" * 20000)])
    assert len(ocr.extract_text_from_image(f, PNG)) == 10000


def test_image_not_persisted_and_raw_text_not_stored(client, auth_headers, llm, db):
    llm.script = [ChatResult(content=SCAM), final("high")]
    r = up(client, auth_headers, PNG + b"UNIQUEBYTES")
    assert r.status_code == 200
    db.expire_all()
    rows = db.execute(sql("SELECT row_to_json(t)::text FROM (SELECT * FROM events) t")).scalars().all()
    dump = "".join(rows)
    for tbl in ("assessments", "alerts"):
        dump += "".join(db.execute(sql(f"SELECT row_to_json(t)::text FROM (SELECT * FROM {tbl}) t")).scalars())
    # the only text stored is the pipeline's own redacted_text (same as any share event); no image bytes
    assert "482913" not in dump and "UNIQUEBYTES" not in dump
    from app.pipeline.redact import redact
    ev = db.scalar(select(Event))
    assert ev.redacted_text == redact(SCAM)


def test_oversize_413(client, auth_headers, llm):
    big = PNG + b"\x00" * (5 * 1024 * 1024)
    r = up(client, auth_headers, big)
    assert r.status_code == 413
    assert llm.calls == []


def test_oversize_without_content_length_413(client, auth_headers, llm):
    import app.routes.events as ev
    boundary = "b0undary"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + PNG + b"\x00" * (6 * 1024 * 1024) + \
        f"\r\n--{boundary}--\r\n".encode()

    def gen():
        for i in range(0, len(body), 65536):
            yield body[i:i + 65536]

    r = client.post("/v1/check", content=gen(), headers={
        **auth_headers, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    assert r.status_code == 413
    assert llm.calls == []


def test_wrong_type_415(client, auth_headers, llm):
    r = up(client, auth_headers, b"%PDF-1.4 not an image", name="a.png", ctype="image/png")
    assert r.status_code == 415
    r = up(client, auth_headers, b"MZ\x90\x00", name="a.exe", ctype="application/octet-stream")
    assert r.status_code == 415
    assert llm.calls == []


@pytest.mark.parametrize("magic", [b"\xff\xd8\xff\xe0" + b"0" * 20, b"GIF89a" + b"0" * 20,
                                   b"RIFF\x10\x00\x00\x00WEBPVP8 " + b"0" * 20])
def test_other_allowed_formats(client, auth_headers, llm, magic):
    llm.script = [ChatResult(content=SCAM), final("high")]
    assert up(client, auth_headers, magic).status_code == 200


def test_missing_image_part_422(client, auth_headers, llm):
    r = up(client, auth_headers, files=False, fields={"lang": "en"})
    assert r.status_code in (415, 422)  # no multipart body at all falls to the JSON path -> 422
    r = client.post("/v1/check", files={"other": ("a.png", PNG, "image/png")}, headers=auth_headers)
    assert r.status_code == 422


@pytest.mark.parametrize("fail", [LLMUnavailable("down"), RuntimeError("secret internals")])
def test_vision_failure_502(client, auth_headers, llm, fail):
    llm.script = [fail]
    r = up(client, auth_headers, PNG)
    assert r.status_code == 502
    d = r.json()["detail"]
    assert "could not read the image" in d and "paste the text" in d
    assert "secret internals" not in r.text and "Traceback" not in r.text


def test_empty_ocr_422(client, auth_headers, llm):
    llm.script = [ChatResult(content="   \n ")]
    r = up(client, auth_headers, PNG)
    assert r.status_code == 422
    assert "no text" in r.json()["detail"].lower()


def test_bad_form_fields_422(client, auth_headers, llm):
    assert up(client, auth_headers, PNG, fields={"triage_score": "abc"}).status_code == 422
    assert up(client, auth_headers, PNG, fields={"ts": "notadate"}).status_code == 422


def test_auth_required(client, llm):
    assert up(client, {}, PNG).status_code == 401
    assert llm.calls == []


def test_json_check_unchanged(client, auth_headers, llm):
    llm.script = [final("high", "Scam")]
    r = client.post("/v1/check", json=COURIER_SMS, headers=auth_headers)
    assert r.status_code == 200 and r.json()["alert"] is not None
    assert client.post("/v1/check", json={"source": "sms"}, headers=auth_headers).status_code == 422
    assert client.post("/v1/check", content=b"{bad", headers={**auth_headers, "Content-Type": "application/json"}
                       ).status_code == 422


def test_openapi_documents_both_bodies(client):
    rb = client.get("/openapi.json").json()["paths"]["/v1/check"]["post"]["requestBody"]["content"]
    assert "application/json" in rb and "multipart/form-data" in rb
