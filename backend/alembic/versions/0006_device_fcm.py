"""own-device push token on devices

Revision ID: 0006
Revises: 0005
"""
import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("fcm_token", sa.String))
    op.add_column("devices", sa.Column("fcm_invalid_at", sa.DateTime(timezone=True)))


def downgrade() -> None:
    op.drop_column("devices", "fcm_invalid_at")
    op.drop_column("devices", "fcm_token")
