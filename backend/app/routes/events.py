import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from python_multipart.multipart import MultipartParser, parse_options_header
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_session
from app.llm import get_llm
from app.models import User
from app.pipeline import ocr
from app.pipeline.orchestrator import PipelineDeps, process_event
from app.schemas import EventIn, EventResult

router = APIRouter()


def get_pipeline_deps() -> PipelineDeps:
    """Side-effecting pipeline dependencies (RDAP, HTTP clients, alert hooks); overridden in tests."""
    return PipelineDeps()


@router.post("/v1/events", response_model=EventResult)
def post_event(event: EventIn, user: User = Depends(current_user), db: Session = Depends(get_session),
               llm=Depends(get_llm), deps: PipelineDeps = Depends(get_pipeline_deps)):
    """Ingest a device event. `alert` is set only when it should be shown (caution and up, after
    dedupe / daily cap); `duplicate: true` (alert null) when the device_event_id was already seen."""
    return process_event(db, user, event, force_agent=False, llm=llm, deps=deps)


MAX_IMAGE_BYTES = 5 * 1024 * 1024
_MULTIPART_OVERHEAD = 64 * 1024  # boundaries, headers and the small form fields
MAX_REQUEST_BYTES = MAX_IMAGE_BYTES + _MULTIPART_OVERHEAD
MAX_FIELD_BYTES = 4096
log = logging.getLogger(__name__)

_OCR_FAILED = "could not read the image, please try again or paste the text"

_CHECK_BODIES = {"requestBody": {"required": True, "content": {
    "application/json": {"schema": {"$ref": "#/components/schemas/EventIn"}},
    "multipart/form-data": {"schema": {
        "type": "object", "required": ["image"],
        "properties": {
            "image": {"type": "string", "format": "binary",
                      "description": "PNG, JPEG, WEBP or GIF screenshot, at most 5 MB"},
            "device_event_id": {"type": "string"}, "ts": {"type": "string", "format": "date-time"},
            "lang": {"type": "string"}, "triage_score": {"type": "number"}}}}}}}


class _TooLarge(Exception):
    pass


def _bad(msg: str, loc: str = "body") -> RequestValidationError:
    return RequestValidationError([{"type": "value_error", "loc": ("body", loc), "msg": msg, "input": None}])


async def _read_multipart(request: Request) -> tuple[bytes | None, dict[str, str]]:
    """Parse a multipart body fully in memory (no temp files), aborting as soon as more than
    MAX_REQUEST_BYTES have arrived. Returns (image bytes or None, small text fields)."""
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > MAX_REQUEST_BYTES:
        raise _TooLarge
    _, params = parse_options_header(request.headers.get("content-type", ""))
    boundary = params.get(b"boundary")
    if not boundary:
        raise _bad("multipart boundary missing")

    parts: list[dict] = []
    cur: dict = {}

    def on_part_begin():
        cur.clear()
        cur.update(headers={}, name=b"", value=b"", data=bytearray())

    def on_header_field(data, start, end):
        cur["name"] += data[start:end]

    def on_header_value(data, start, end):
        cur["value"] += data[start:end]

    def on_header_end():
        cur["headers"][cur["name"].lower()] = cur["value"]
        cur["name"], cur["value"] = b"", b""

    def on_part_data(data, start, end):
        cur["data"] += data[start:end]
        if "image" not in _field_name(cur) and len(cur["data"]) > MAX_FIELD_BYTES:
            raise _bad("form field too large")
        if len(cur["data"]) > MAX_IMAGE_BYTES:
            raise _TooLarge

    def on_part_end():
        parts.append({"headers": dict(cur["headers"]), "data": bytes(cur["data"])})
        cur.clear()

    parser = MultipartParser(boundary, {"on_part_begin": on_part_begin, "on_header_field": on_header_field,
                                        "on_header_value": on_header_value, "on_header_end": on_header_end,
                                        "on_part_data": on_part_data, "on_part_end": on_part_end})
    total = 0
    try:
        async for chunk in request.stream():
            total += len(chunk)
            if total > MAX_REQUEST_BYTES:
                raise _TooLarge
            parser.write(chunk)
        parser.finalize()
    except (_TooLarge, RequestValidationError):
        raise
    except Exception:
        raise _bad("malformed multipart body")

    image: bytes | None = None
    fields: dict[str, str] = {}
    for p in parts:
        disp = p["headers"].get(b"content-disposition", b"")
        _, dparams = parse_options_header(disp)
        name = dparams.get(b"name", b"").decode("utf-8", "replace")
        if name == "image" and image is None:
            image = p["data"]
        elif name and name != "image":
            fields[name] = p["data"].decode("utf-8", "replace")
    return image, fields


def _field_name(cur: dict) -> str:
    _, dparams = parse_options_header(cur.get("headers", {}).get(b"content-disposition", b""))
    return dparams.get(b"name", b"").decode("utf-8", "replace") or ""


async def _check_image(request: Request, user: User, db: Session, llm, deps: PipelineDeps):
    try:
        image, fields = await _read_multipart(request)
    except _TooLarge:
        raise HTTPException(status_code=413, detail="image too large (max 5 MB)")
    if not image:
        raise _bad("an image file part named 'image' is required", "image")
    if ocr.sniff_image_type(image) is None:
        raise HTTPException(status_code=415, detail="unsupported image type (use PNG, JPEG, WEBP or GIF)")
    try:
        ts = datetime.fromisoformat(fields["ts"].replace("Z", "+00:00")) if fields.get("ts") else \
            datetime.now(timezone.utc)
        score = float(fields["triage_score"]) if fields.get("triage_score") else 0.5
    except ValueError:
        raise _bad("invalid ts or triage_score")
    try:
        text = await run_in_threadpool(ocr.extract_text_from_image, llm, image)
    except Exception as exc:  # LLMUnavailable or anything else: never a crash or a stack trace
        log.warning("image OCR failed (%s)", type(exc).__name__)
        raise HTTPException(status_code=502, detail=_OCR_FAILED)
    del image
    if not text:
        raise HTTPException(status_code=422, detail="no text could be found in the image")
    try:
        event = EventIn(device_event_id=(fields.get("device_event_id") or f"img-{uuid.uuid4().hex}")[:200],
                        source="share", ts=ts, text=text, triage_score=score, lang=fields.get("lang") or None)
    except ValidationError as exc:
        raise RequestValidationError([{**e, "loc": ("body", *e["loc"])} for e in exc.errors()])
    return await run_in_threadpool(process_event, db, user, event, force_agent=True, llm=llm, deps=deps)


@router.post("/v1/check", response_model=EventResult, openapi_extra=_CHECK_BODIES)
async def check(request: Request, user: User = Depends(current_user), db: Session = Depends(get_session),
                llm=Depends(get_llm), deps: PipelineDeps = Depends(get_pipeline_deps)):
    """User-initiated "is this a scam?": always runs the agent and always returns an alert. Body is
    JSON (EventIn) or multipart/form-data with an `image` screenshot (read by the vision model, then
    processed as a `share` event; the image is never stored)."""
    if request.headers.get("content-type", "").lower().startswith("multipart/form-data"):
        return await _check_image(request, user, db, llm, deps)
    try:
        event = EventIn.model_validate_json(await request.body())
    except ValidationError as exc:
        raise RequestValidationError([{**e, "loc": ("body", *e["loc"])} for e in exc.errors()])
    return await run_in_threadpool(process_event, db, user, event, force_agent=True, llm=llm, deps=deps)
