"""Shared fixtures. Tests run against the compose Postgres (`docker compose up -d db`).

DATABASE_URL is read from env/.env; the fallback below is the documented dev value
from .env.example and is used by tests only. Tests always use a separate
database named `<db>_test`, which is created and migrated here.
"""
import os

os.environ.setdefault(
    "DATABASE_URL", "postgresql+psycopg://sentinel:sentinel@127.0.0.1:5440/sentinel"
)

import pytest  # noqa: E402
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402
from sqlalchemy.engine import make_url  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

import httpx  # noqa: E402

from app.db import Base, get_session  # noqa: E402
from app.llm import LLMUnavailable, get_llm  # noqa: E402
from app.main import app  # noqa: E402
from app.pipeline.orchestrator import PipelineDeps  # noqa: E402
from app.pipeline.signals import DomainInfo  # noqa: E402
from app.pipeline.tools import SHORTENERS, lookalike_of  # noqa: E402
from app.routes.events import get_pipeline_deps  # noqa: E402


class DownLLM:
    """Token Factory unreachable: every call fails like a timeout / 5xx would."""

    def __init__(self):
        self.calls = []

    def chat(self, *a, **kw):
        self.calls.append((a, kw))
        raise LLMUnavailable("down")

    def embed(self, texts):
        raise LLMUnavailable("down")


def offline_deps() -> PipelineDeps:
    """Pipeline dependencies with no network: local-only domain info, RDAP/web clients that 404."""
    def domain_info(ctx, d):
        return DomainInfo(lookalike_of=lookalike_of(d), shortener=d in SHORTENERS)

    def http(timeout):
        return httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(404)))

    return PipelineDeps(domain_info=domain_info, http_client_factory=http)


def _test_url():
    url = make_url(os.environ["DATABASE_URL"])
    return url, url.set(database=f"{url.database}_test")


@pytest.fixture(scope="session")
def test_engine():
    admin_url, url = _test_url()
    admin = create_engine(admin_url, isolation_level="AUTOCOMMIT")
    with admin.connect() as c:
        c.execute(text(f'DROP DATABASE IF EXISTS "{url.database}" WITH (FORCE)'))
        c.execute(text(f'CREATE DATABASE "{url.database}"'))
    admin.dispose()
    cfg = Config(os.path.join(os.path.dirname(__file__), "..", "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(os.path.dirname(__file__), "..", "alembic"))
    cfg.attributes["url"] = url.render_as_string(hide_password=False)
    command.upgrade(cfg, "head")
    engine = create_engine(url)
    yield engine
    engine.dispose()


@pytest.fixture
def db(test_engine):
    """A session on the migrated test DB; all tables are truncated after each test."""
    Session = sessionmaker(bind=test_engine, autoflush=False)
    with Session() as session:
        yield session
        session.rollback()
    names = ", ".join(f'"{t.name}"' for t in Base.metadata.sorted_tables)
    with test_engine.begin() as c:
        c.execute(text(f"TRUNCATE {names} RESTART IDENTITY CASCADE"))


@pytest.fixture
def client(test_engine):
    Session = sessionmaker(bind=test_engine, autoflush=False)

    def override():
        with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    # tests never reach Token Factory, RDAP or the web: override per test for scripted behaviour
    app.dependency_overrides[get_llm] = DownLLM
    app.dependency_overrides[get_pipeline_deps] = offline_deps
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture
def register(client, db):
    """Callable: register() -> (device_id, auth_headers). Each call makes a new user."""

    def _register():
        body = client.post("/v1/devices/register").json()
        return body["device_id"], {"Authorization": f"Bearer {body['token']}"}

    return _register


@pytest.fixture
def auth_headers(register):
    return register()[1]
