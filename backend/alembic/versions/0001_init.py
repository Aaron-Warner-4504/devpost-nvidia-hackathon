"""init schema

Revision ID: 0001
Revises:
"""
import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects.postgresql import ARRAY

from app.config import settings

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

TS = sa.DateTime(timezone=True)


def _uid():
    return sa.Column("user_id", sa.String, sa.ForeignKey("users.id", ondelete="CASCADE"),
                     nullable=False, index=True)


def _now():
    return sa.Column("created_at", TS, server_default=sa.func.now(), nullable=False)


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.create_table("users", sa.Column("id", sa.String, primary_key=True), _now())
    op.create_table(
        "devices", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("token_hash", sa.String, nullable=False, unique=True), _now())
    op.create_table(
        "consents", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("source", sa.String, nullable=False),
        sa.Column("granted_at", TS, nullable=False),
        sa.Column("revoked_at", TS),
        sa.UniqueConstraint("user_id", "source"))
    op.create_table(
        "events", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("device_event_id", sa.String, nullable=False),
        sa.Column("source", sa.String, nullable=False),
        sa.Column("ts", TS, nullable=False),
        sa.Column("redacted_text", sa.Text),
        sa.Column("hash", sa.String, nullable=False), _now(),
        sa.UniqueConstraint("user_id", "device_event_id"))
    op.create_table(
        "entities", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("type", sa.String, nullable=False),
        sa.Column("canonical", sa.String, nullable=False),
        sa.UniqueConstraint("user_id", "type", "canonical"))
    op.create_table(
        "edges", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("a", sa.String, sa.ForeignKey("entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("b", sa.String, sa.ForeignKey("entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("relation", sa.String, nullable=False),
        sa.Column("valid_from", TS, nullable=False),
        sa.Column("valid_to", TS),
        sa.Column("evidence_event_ids", ARRAY(sa.String), nullable=False))
    op.create_table(
        "assessments", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("event_id", sa.String, sa.ForeignKey("events.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("tier", sa.String, nullable=False),
        sa.Column("signals", ARRAY(sa.String), nullable=False),
        sa.Column("explanation", sa.Text, nullable=False),
        sa.Column("model", sa.String, nullable=False),
        sa.Column("latency", sa.Float, nullable=False),
        sa.Column("degraded", sa.Boolean, nullable=False), _now())
    op.create_table(
        "alerts", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("assessment_id", sa.String, sa.ForeignKey("assessments.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("shown_at", TS),
        sa.Column("action", sa.String),
        sa.Column("user_label", sa.String), _now())
    op.create_table(
        "trusted_contacts", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("name", sa.String, nullable=False),
        sa.Column("phone", sa.String, nullable=False))
    op.create_table(
        "embeddings", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("event_id", sa.String, sa.ForeignKey("events.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("vector", Vector(settings.embed_dim), nullable=False))
    op.create_table(
        "overrides", sa.Column("id", sa.String, primary_key=True), _uid(),
        sa.Column("kind", sa.String, nullable=False),
        sa.Column("type", sa.String, nullable=False),
        sa.Column("canonical", sa.String, nullable=False),
        sa.UniqueConstraint("user_id", "kind", "type", "canonical"),
        sa.CheckConstraint("kind in ('safe','known_bad')", name="ck_overrides_kind"))


def downgrade() -> None:
    for t in ["overrides", "embeddings", "trusted_contacts", "alerts", "assessments", "edges",
              "entities", "events", "consents", "devices", "users"]:
        op.drop_table(t)
