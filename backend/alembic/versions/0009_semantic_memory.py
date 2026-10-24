"""semantic memory: one embedding per event, provider name per vector, column width = EMBED_DIM

- embeddings.model: the provider/model that produced the vector (vectors of different models are never
  compared).
- unique (event_id): one vector per event (re-embedding is an upsert).
- embeddings.vector is altered to vector(EMBED_DIM) when the configured width differs from the column.
  GUARD: only while the table is empty (vectors cannot be converted between models); with rows present
  the migration refuses with a clear message instead of destroying data.

Revision ID: 0009
Revises: 0008
"""
import re

import sqlalchemy as sa
from alembic import op

from app.config import settings

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def _current_dim(conn) -> int | None:
    t = conn.execute(sa.text(
        "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
        "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
        "AND c.relname = 'embeddings' AND a.attname = 'vector' AND NOT a.attisdropped")).scalar()
    m = re.search(r"\((\d+)\)", t or "")
    return int(m.group(1)) if m else None


def resize_vector(conn, dim: int) -> None:
    cur = _current_dim(conn)
    if cur == dim:
        return
    n = conn.execute(sa.text("SELECT count(*) FROM embeddings")).scalar()
    if n:
        raise RuntimeError(
            f"embeddings.vector is vector({cur}) but EMBED_DIM={dim}, and the table has {n} rows. Vectors of "
            f"different models cannot be converted. Set EMBED_DIM={cur}, or delete the embeddings rows "
            f"(they are rebuilt only for new events) and run the migration again.")
    conn.execute(sa.text(f"ALTER TABLE embeddings ALTER COLUMN vector TYPE vector({int(dim)})"))


def upgrade() -> None:
    conn = op.get_bind()
    resize_vector(conn, int(settings.embed_dim))
    op.add_column("embeddings", sa.Column("model", sa.String, nullable=False, server_default=""))
    op.add_column("embeddings", sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()))
    op.execute("DELETE FROM embeddings a USING embeddings b WHERE a.event_id = b.event_id AND a.id > b.id")
    op.create_unique_constraint("uq_embeddings_event", "embeddings", ["event_id"])
    op.create_index("ix_edges_user_relation", "edges", ["user_id", "relation"])


def downgrade() -> None:
    op.drop_index("ix_edges_user_relation", "edges")
    op.drop_constraint("uq_embeddings_event", "embeddings", type_="unique")
    op.drop_column("embeddings", "created_at")
    op.drop_column("embeddings", "model")
