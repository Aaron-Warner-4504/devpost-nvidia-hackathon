"""a push token belongs to one device: unique partial index

Revision ID: 0007
Revises: 0006
"""
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # dedupe: the most recently created device keeps a shared token, the others lose it
    op.execute("""
        UPDATE devices SET fcm_token = NULL, fcm_invalid_at = NULL
        WHERE fcm_token IS NOT NULL AND id IN (
            SELECT id FROM (
                SELECT id, row_number() OVER (PARTITION BY fcm_token ORDER BY created_at DESC, id DESC) AS rn
                FROM devices WHERE fcm_token IS NOT NULL
            ) t WHERE rn > 1
        )
    """)
    op.create_index("uq_devices_fcm_token", "devices", ["fcm_token"], unique=True,
                    postgresql_where="fcm_token IS NOT NULL")


def downgrade() -> None:
    op.drop_index("uq_devices_fcm_token", table_name="devices")
