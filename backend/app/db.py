from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.config import settings

def connect_args() -> dict:
    args: dict = {}
    if settings.db_search_path:
        args["options"] = f"-csearch_path={settings.db_search_path}"
    if settings.db_disable_prepared_statements:
        args["prepare_threshold"] = None
    return args


def make_engine(url: str | None = None, **kw):
    return create_engine(url or settings.database_url, pool_pre_ping=True,
                         pool_size=settings.db_pool_size, max_overflow=settings.db_max_overflow,
                         pool_recycle=settings.db_pool_recycle, connect_args=connect_args(), **kw)


engine = make_engine()
SessionLocal = sessionmaker(bind=engine, autoflush=False)


class Base(DeclarativeBase):
    pass


def get_session():
    with SessionLocal() as session:
        yield session
