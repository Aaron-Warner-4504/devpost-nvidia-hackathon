from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, Field, StringConstraints

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
    sender: str | None = Field(default=None, max_length=200)
    text: str | None = Field(default=None, max_length=10000)
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


class FamilyInviteIn(BaseModel):
    # the protected user's explicit consent toggle; anything but `true` is a 422
    consent: Literal[True]


class FamilyInviteOut(BaseModel):
    code: str
    expires_at: datetime


class FamilyAccept(BaseModel):
    code: str = Field(max_length=32)
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    fcm_token: str = Field(min_length=1, max_length=4096)


class FamilyLinkOut(BaseModel):
    id: str
    protected_name: None = None  # nothing about the protected user is revealed to the guardian


class GuardianOut(BaseModel):
    id: str
    name: str
    linked_at: datetime


class ProtectingOut(BaseModel):
    id: str
    linked_at: datetime


class FamilyContactsOut(BaseModel):
    guardians: list[GuardianOut]  # people notified about MY critical alerts
    protecting: list[ProtectingOut]  # people whose critical alerts I receive
