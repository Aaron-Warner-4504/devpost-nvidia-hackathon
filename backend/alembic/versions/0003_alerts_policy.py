"""alert policy columns, assessment payload, sender key

Revision ID: 0003
Revises: 0002
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # sha256 of the normalised sender (phone or SMS header); raw sender strings are not stored
    op.add_column("events", sa.Column("sender_key", sa.String))
    op.create_index("ix_events_user_sender_key", "events", ["user_id", "sender_key"])
    # user-facing verdict: headline, next_step, why[], used_tools, raised_by
    op.add_column("assessments", sa.Column("payload", JSONB))
    op.add_column("alerts", sa.Column("dedupe_key", sa.String))
    op.add_column("alerts", sa.Column("interrupt", sa.Boolean, nullable=False, server_default=sa.false()))
    op.add_column("alerts", sa.Column("reason", sa.String))
    op.create_index("ix_alerts_user_dedupe_key", "alerts", ["user_id", "dedupe_key"])
    op.create_index("ix_alerts_user_shown_at", "alerts", ["user_id", "shown_at"])


def downgrade() -> None:
    op.drop_index("ix_alerts_user_shown_at", "alerts")
    op.drop_index("ix_alerts_user_dedupe_key", "alerts")
    op.drop_column("alerts", "reason")
    op.drop_column("alerts", "interrupt")
    op.drop_column("alerts", "dedupe_key")
    op.drop_column("assessments", "payload")
    op.drop_index("ix_events_user_sender_key", "events")
    op.drop_column("events", "sender_key")
