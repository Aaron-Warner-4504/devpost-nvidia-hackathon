"""pre-auth rate-limit rows; index for the global failed-accept count

Revision ID: 0005
Revises: 0004
"""
import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "rate_limit_events",
        sa.Column("id", sa.String, primary_key=True),
        sa.Column("kind", sa.String, nullable=False),
        sa.Column("key", sa.String, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_rate_limit_events_kind_key_created", "rate_limit_events", ["kind", "key", "created_at"])
    op.create_index("ix_rate_limit_events_created_at", "rate_limit_events", ["created_at"])
    op.create_index("ix_family_accept_failures_created_at", "family_accept_failures", ["created_at"])


def downgrade() -> None:
    op.drop_index("ix_family_accept_failures_created_at", "family_accept_failures")
    op.drop_table("rate_limit_events")
