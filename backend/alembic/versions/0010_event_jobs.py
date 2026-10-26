"""event_jobs: stage-2 background review queue (one row per event), row level security on

Revision ID: 0010
Revises: 0009
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "event_jobs",
        sa.Column("id", sa.String, primary_key=True),
        sa.Column("user_id", sa.String, sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True),
        sa.Column("event_id", sa.String, sa.ForeignKey("events.id", ondelete="CASCADE"), nullable=False,
                  unique=True),
        sa.Column("state", sa.String, nullable=False, server_default="queued"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("agent_ran", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("upgraded", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("error", sa.String, nullable=True),
        sa.Column("payload", JSONB, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint("state in ('queued','running','done','failed')", name="ck_event_jobs_state"),
    )
    op.create_index("ix_event_jobs_state_updated", "event_jobs", ["state", "updated_at"])
    op.execute("ALTER TABLE public.event_jobs ENABLE ROW LEVEL SECURITY")
    op.execute("""
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')
           AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            REVOKE ALL ON public.event_jobs FROM anon, authenticated;
        END IF;
    END $$;
    """)


def downgrade() -> None:
    op.drop_index("ix_event_jobs_state_updated", "event_jobs")
    op.drop_table("event_jobs")
