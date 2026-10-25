"""DOCS_ENABLED: /docs, /redoc, /openapi.json are off by default in ENVIRONMENT=prod, on elsewhere."""
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parent.parent

_PROBE = """
import json
from fastapi.testclient import TestClient
from app.main import app
c = TestClient(app)
paths = ["/openapi.json", "/docs", "/redoc", "/docs/oauth2-redirect", "/sentinel/openapi.json", "/sentinel/docs"]
out = {p: c.get(p).status_code for p in paths}
out["health"] = c.get("/health").status_code
out["me_noauth"] = c.get("/v1/me").status_code
print(json.dumps(out))
"""


def _probe(environment: str, docs: str | None = None, root_path: str | None = None) -> dict:
    env = {**os.environ, "ENVIRONMENT": environment}
    for k in ("DOCS_ENABLED", "ROOT_PATH"):
        env.pop(k, None)
    if environment == "prod":
        env["FAMILY_CODE_PEPPER"] = "x" * 24
    if docs is not None:
        env["DOCS_ENABLED"] = docs
    if root_path is not None:
        env["ROOT_PATH"] = root_path
    # TestClient used without a context manager: the lifespan (prod checks) is not run, so the DB URL is not checked here
    out = subprocess.run([sys.executable, "-c", _PROBE], cwd=BACKEND, env=env, capture_output=True, text=True, check=True)
    return json.loads(out.stdout.strip().splitlines()[-1])


DOC_PATHS = ["/openapi.json", "/docs", "/redoc", "/docs/oauth2-redirect"]


def test_prod_default_disables_docs_but_app_works():
    r = _probe("prod")
    for p in DOC_PATHS:
        assert r[p] == 404, p
    assert r["health"] == 200
    assert r["me_noauth"] in (401, 403)


def test_prod_docs_enabled_true():
    r = _probe("prod", docs="true")
    assert r["/openapi.json"] == 200 and r["/docs"] == 200 and r["/redoc"] == 200


@pytest.mark.parametrize("environment", ["dev", "test"])
def test_non_prod_default_enables_docs(environment):
    r = _probe(environment)
    assert r["/openapi.json"] == 200 and r["/docs"] == 200 and r["/redoc"] == 200


def test_dev_docs_can_be_disabled():
    assert _probe("dev", docs="false")["/openapi.json"] == 404


def test_blank_docs_enabled_means_default():
    assert _probe("prod", docs="")["/openapi.json"] == 404
    assert _probe("dev", docs="")["/openapi.json"] == 200


def test_prod_with_root_path_still_404():
    r = _probe("prod", root_path="/sentinel")
    for p in DOC_PATHS + ["/sentinel/openapi.json", "/sentinel/docs"]:
        assert r[p] == 404, p
    assert r["health"] == 200
