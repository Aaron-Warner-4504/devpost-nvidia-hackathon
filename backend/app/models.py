import uuid
from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (Boolean, CheckConstraint, DateTime, Float, ForeignKey, Index,
                        String, Text, UniqueConstraint, func, text)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.config import settings
from app.db import Base


def _id(prefix: str):
    return lambda: f"{prefix}_{uuid.uuid4().hex[:20]}"


def _pk(prefix: str):
    return mapped_column(String, primary_key=True, default=_id(prefix))


def _uid():
    return mapped_column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)


def _now():
    return mapped_column(DateTime(timezone=True), server_default=func.now())


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = _pk("usr")
    created_at: Mapped[datetime] = _now()


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (Index("uq_devices_fcm_token", "fcm_token", unique=True,
                            postgresql_where=text("fcm_token IS NOT NULL")),)
    id: Mapped[str] = _pk("dev")
    user_id: Mapped[str] = _uid()
    token_hash: Mapped[str] = mapped_column(String, unique=True)  # sha256 hex; raw token never stored
    created_at: Mapped[datetime] = _now()
    fcm_token: Mapped[str | None] = mapped_column(String, nullable=True)  # this device's own push token; never returned
    fcm_invalid_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Consent(Base):
    __tablename__ = "consents"
    __table_args__ = (UniqueConstraint("user_id", "source"),)
    id: Mapped[str] = _pk("cns")
    user_id: Mapped[str] = _uid()
    source: Mapped[str] = mapped_column(String)
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Event(Base):
    __tablename__ = "events"
    __table_args__ = (UniqueConstraint("user_id", "device_event_id"),)
    id: Mapped[str] = _pk("evt")
    user_id: Mapped[str] = _uid()
    device_event_id: Mapped[str] = mapped_column(String)
    source: Mapped[str] = mapped_column(String)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    redacted_text: Mapped[str | None] = mapped_column(Text, nullable=True)  # only redacted form is stored
    hash: Mapped[str] = mapped_column(String)
    sender_key: Mapped[str | None] = mapped_column(String, nullable=True)  # sha256 of normalised sender
    created_at: Mapped[datetime] = _now()


class Entity(Base):
    __tablename__ = "entities"
    __table_args__ = (UniqueConstraint("user_id", "type", "canonical"),)
    id: Mapped[str] = _pk("ent")
    user_id: Mapped[str] = _uid()
    type: Mapped[str] = mapped_column(String)
    canonical: Mapped[str] = mapped_column(String)


class Edge(Base):
    __tablename__ = "edges"
    __table_args__ = (UniqueConstraint("user_id", "a", "b", "relation", name="uq_edges_user_a_b_relation"),)
    id: Mapped[str] = _pk("edg")
    user_id: Mapped[str] = _uid()
    a: Mapped[str] = mapped_column(String, ForeignKey("entities.id", ondelete="CASCADE"))
    b: Mapped[str] = mapped_column(String, ForeignKey("entities.id", ondelete="CASCADE"))
    relation: Mapped[str] = mapped_column(String)
    valid_from: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    valid_to: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    evidence_event_ids: Mapped[list[str]] = mapped_column(ARRAY(String), default=list)


class Assessment(Base):
    __tablename__ = "assessments"
    id: Mapped[str] = _pk("asm")
    user_id: Mapped[str] = _uid()
    event_id: Mapped[str] = mapped_column(String, ForeignKey("events.id", ondelete="CASCADE"), index=True)
    tier: Mapped[str] = mapped_column(String)
    signals: Mapped[list[str]] = mapped_column(ARRAY(String), default=list)
    explanation: Mapped[str] = mapped_column(Text, default="")
    model: Mapped[str] = mapped_column(String, default="")
    latency: Mapped[float] = mapped_column(Float, default=0.0)
    degraded: Mapped[bool] = mapped_column(Boolean, default=False)
    payload: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # headline, next_step, why, ...
    created_at: Mapped[datetime] = _now()


class Alert(Base):
    __tablename__ = "alerts"
    id: Mapped[str] = _pk("alr")
    user_id: Mapped[str] = _uid()
    assessment_id: Mapped[str] = mapped_column(
        String, ForeignKey("assessments.id", ondelete="CASCADE"), index=True)
    shown_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    action: Mapped[str | None] = mapped_column(String, nullable=True)
    user_label: Mapped[str | None] = mapped_column(String, nullable=True)
    dedupe_key: Mapped[str | None] = mapped_column(String, nullable=True)
    interrupt: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    reason: Mapped[str | None] = mapped_column(String, nullable=True)  # policy decision reason
    created_at: Mapped[datetime] = _now()


def _user_fk():
    return mapped_column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)


class TrustedContact(Base):
    """Family link: a guardian (their own app install / user) watching a protected user. Exists only after
    the protected user created an invite with explicit consent; unlinking (either side) deletes the row."""
    __tablename__ = "trusted_contacts"
    __table_args__ = (
        UniqueConstraint("protected_user_id", "guardian_user_id", name="uq_trusted_contacts_pair"),
        CheckConstraint("protected_user_id <> guardian_user_id", name="ck_trusted_contacts_not_self"),
    )
    id: Mapped[str] = _pk("fam")
    protected_user_id: Mapped[str] = _user_fk()
    guardian_user_id: Mapped[str] = _user_fk()
    name: Mapped[str] = mapped_column(String)  # guardian's display name, shown to the protected user
    fcm_token: Mapped[str] = mapped_column(String)  # the GUARDIAN's push token; never returned by the API
    consent_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))  # when the invite was consented
    created_at: Mapped[datetime] = _now()
    fcm_invalid_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class FamilyInvite(Base):
    """Pairing code created by the protected user. Only a peppered hash of the code is stored."""
    __tablename__ = "family_invites"
    id: Mapped[str] = _pk("inv")
    user_id: Mapped[str] = _uid()  # the protected user
    code_hash: Mapped[str] = mapped_column(String, unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = _now()


class FamilyAcceptFailure(Base):
    """One row per failed accept attempt by a (guardian) user: brute-force rate limit."""
    __tablename__ = "family_accept_failures"
    id: Mapped[str] = _pk("faf")
    user_id: Mapped[str] = _uid()
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), index=True)


class FamilyPing(Base):
    """One row per family notification sent to a guardian (user_id = guardian): per-guardian throttle."""
    __tablename__ = "family_pings"
    id: Mapped[str] = _pk("fpg")
    user_id: Mapped[str] = _uid()
    created_at: Mapped[datetime] = _now()


class Embedding(Base):
    """One vector per event (redacted text only). `model` names the provider that produced it."""
    __tablename__ = "embeddings"
    __table_args__ = (UniqueConstraint("event_id", name="uq_embeddings_event"),)
    id: Mapped[str] = _pk("emb")
    user_id: Mapped[str] = _uid()
    event_id: Mapped[str] = mapped_column(String, ForeignKey("events.id", ondelete="CASCADE"), index=True)
    vector = mapped_column(Vector(settings.embed_dim), nullable=False)
    model: Mapped[str] = mapped_column(String, default="", server_default="")
    created_at: Mapped[datetime] = _now()


class Override(Base):
    """Per-user safe / known-bad entity overrides, written by alert feedback (Task 11)."""
    __tablename__ = "overrides"
    __table_args__ = (
        UniqueConstraint("user_id", "kind", "type", "canonical"),
        CheckConstraint("kind in ('safe','known_bad')", name="ck_overrides_kind"),
    )
    id: Mapped[str] = _pk("ovr")
    user_id: Mapped[str] = _uid()
    kind: Mapped[str] = mapped_column(String)
    type: Mapped[str] = mapped_column(String)
    canonical: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = _now()


class EventEntity(Base):
    """Event <-> entity membership."""
    __tablename__ = "event_entities"
    user_id: Mapped[str] = _uid()
    event_id: Mapped[str] = mapped_column(
        String, ForeignKey("events.id", ondelete="CASCADE"), primary_key=True)
    entity_id: Mapped[str] = mapped_column(
        String, ForeignKey("entities.id", ondelete="CASCADE"), primary_key=True, index=True)


class RateLimitEvent(Base):
    """Pre-auth rate-limit counter rows (e.g. device registration per client IP). Holds no user data:
    `key` is a hash of the client IP; rows older than 24 h are purged on the write path."""
    __tablename__ = "rate_limit_events"
    __table_args__ = (Index("ix_rate_limit_events_kind_key_created", "kind", "key", "created_at"),)
    id: Mapped[str] = _pk("rle")
    kind: Mapped[str] = mapped_column(String)
    key: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
