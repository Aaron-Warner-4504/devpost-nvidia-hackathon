from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException

from app.schemas import (
    AlertOut,
    DeviceRegistered,
    EventIn,
    EventResult,
    FamilyContact,
    Feedback,
    WhyItem,
)

app = FastAPI(title="Sentinel API", version="0.1.0")


def require_token(authorization: str | None = Header(default=None)) -> str:
    # Stub: any non-empty bearer token is accepted. Real auth lands in Task 3.
    if authorization:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() == "bearer" and token.strip():
            return token.strip()
    raise HTTPException(status_code=401, detail="missing or invalid bearer token")


_TS = datetime(2026, 10, 17, 10, 0, tzinfo=timezone.utc)


def example_alert() -> AlertOut:
    return AlertOut(
        id="alr_123",
        event_id="evt_123",
        tier="high",
        headline="Likely bank-impersonation scam",
        why=[
            WhyItem(event_id="evt_120", ts=_TS, text="Same sender asked for an OTP yesterday"),
            WhyItem(event_id="evt_123", ts=_TS, text="Urgent link to a look-alike bank domain"),
        ],
        next_step="Do not click the link or share any OTP. Call your bank on the number printed on your card.",
        signals=["urgency", "lookalike_domain", "otp_request"],
        created_at=_TS,
    )


def example_result() -> EventResult:
    return EventResult(event_id="evt_123", duplicate=False, alert=example_alert())


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/devices/register", response_model=DeviceRegistered)
def register_device():
    return DeviceRegistered(device_id="dev_123", token="example-token")


@app.post("/v1/events", response_model=EventResult)
def post_event(event: EventIn, _: str = Depends(require_token)):
    return example_result()


@app.post("/v1/check", response_model=EventResult)
def check(event: EventIn, _: str = Depends(require_token)):
    return example_result()


@app.get("/v1/alerts", response_model=list[AlertOut])
def list_alerts(since: datetime | None = None, _: str = Depends(require_token)):
    return [example_alert()]


@app.post("/v1/alerts/{id}/feedback")
def alert_feedback(id: str, body: Feedback, _: str = Depends(require_token)):
    return {"ok": True}


@app.post("/v1/family/contacts")
def add_family_contact(body: FamilyContact, _: str = Depends(require_token)):
    return {"ok": True, "id": "fam_123"}


@app.delete("/v1/me", status_code=204)
def delete_me(_: str = Depends(require_token)):
    return None
