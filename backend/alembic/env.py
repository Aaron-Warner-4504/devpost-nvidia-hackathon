from alembic import context
from sqlalchemy import create_engine, pool

from app import models  # noqa: F401  (register tables)
from app.config import settings
from app.db import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    return config.attributes.get("url") or settings.database_url


def run_migrations_online() -> None:
    engine = create_engine(_url(), poolclass=pool.NullPool)
    with engine.connect() as conn:
        context.configure(connection=conn, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


run_migrations_online()
