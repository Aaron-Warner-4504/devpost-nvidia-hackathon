from datetime import datetime
from typing import Literal

from pydantic import BaseModel

Source = Literal["sms", "whatsapp", "email", "call", "share", "notification"]
Tier = Literal["info", "caution", "high", "critical"]


class CallMeta(BaseModel):
    number: str
    duration_s: int
    is_contact: bool
    direction: Literal["in", "out", "missed"]


class EventIn(BaseModel):
    device_event_id: str
    source: Source
    ts: datetime
    sender: str | None = None
    text: str | None = None
    call: CallMeta | None = None
    triage_score: float
    lang: str | None = None


class WhyItem(BaseModel):
    event_id: str
    ts: datetime
    text: str


class AlertOut(BaseModel):
    id: str
    event_id: str
    tier: Tier
    headline: str
    why: list[WhyItem]
    next_step: str
    signals: list[str]
    created_at: datetime


class EventResult(BaseModel):
    event_id: str
    duplicate: bool
    alert: AlertOut | None = None


class Feedback(BaseModel):
    label: Literal["scam", "safe"]


class DeviceRegistered(BaseModel):
    device_id: str
    token: str


class FamilyContact(BaseModel):
    name: str
    phone: str
