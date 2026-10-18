"""event_entities association and edge uniqueness

Revision ID: 0002
Revises: 0001
"""
import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "event_entities",
        sa.Column("user_id", sa.String, sa.ForeignKey("users.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("event_id", sa.String, sa.ForeignKey("events.id", ondelete="CASCADE"),
                  primary_key=True),
        sa.Column("entity_id", sa.String, sa.ForeignKey("entities.id", ondelete="CASCADE"),
                  primary_key=True, index=True))
    op.create_unique_constraint("uq_edges_user_a_b_relation", "edges",
                                ["user_id", "a", "b", "relation"])


def downgrade() -> None:
    op.drop_constraint("uq_edges_user_a_b_relation", "edges", type_="unique")
    op.drop_table("event_entities")
