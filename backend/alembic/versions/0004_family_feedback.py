"""family pairing (links, invites, rate-limit rows) and override timestamps

Revision ID: 0004
Revises: 0003
"""
import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def _user_fk(name: str, **kw):
    return sa.Column(name, sa.String, sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False, **kw)


def upgrade() -> None:
    # The pre-Task-11 trusted_contacts (user_id, name, phone) was only ever written by tests (the endpoint
    # was a stub), and a phone number is not a consented link: it is replaced, not migrated.
    op.drop_table("trusted_contacts")
    op.create_table(
        "trusted_contacts",
        sa.Column("id", sa.String, primary_key=True),
        _user_fk("protected_user_id", index=True),
        _user_fk("guardian_user_id", index=True),
        sa.Column("name", sa.String, nullable=False),
        sa.Column("fcm_token", sa.String, nullable=False),
        sa.Column("consent_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("fcm_invalid_at", sa.DateTime(timezone=True)),
        sa.UniqueConstraint("protected_user_id", "guardian_user_id", name="uq_trusted_contacts_pair"),
        sa.CheckConstraint("protected_user_id <> guardian_user_id", name="ck_trusted_contacts_not_self"),
    )
    op.create_table(
        "family_invites",
        sa.Column("id", sa.String, primary_key=True),
        _user_fk("user_id", index=True),
        sa.Column("code_hash", sa.String, nullable=False, unique=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "family_accept_failures",
        sa.Column("id", sa.String, primary_key=True),
        _user_fk("user_id", index=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "family_pings",
        sa.Column("id", sa.String, primary_key=True),
        _user_fk("user_id", index=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.add_column("overrides", sa.Column("created_at", sa.DateTime(timezone=True),
                                         server_default=sa.func.now(), nullable=False))


def downgrade() -> None:
    op.drop_column("overrides", "created_at")
    op.drop_table("family_pings")
    op.drop_table("family_accept_failures")
    op.drop_table("family_invites")
    op.drop_table("trusted_contacts")
    op.create_table(
        "trusted_contacts",
        sa.Column("id", sa.String, primary_key=True),
        _user_fk("user_id", index=True),
        sa.Column("name", sa.String, nullable=False),
        sa.Column("phone", sa.String, nullable=False),
    )
