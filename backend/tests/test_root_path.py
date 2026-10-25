"""ROOT_PATH: serving under a stripped URL prefix (e.g. nginx `location /sentinel/ { proxy_pass ...:8100/; }`)."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urljoin

from fastapi.testclient import TestClient

from app.main import app

BACKEND = Path(__file__).resolve().parent.parent
STATIC = BACKEND / "app" / "static"

_PROBE = """
import json, sys
from fastapi.testclient import TestClient
from app.main import app
c = TestClient(app)
spec = c.get("/openapi.json").json()
print(json.dumps({"root_path": app.root_path, "servers": spec.get("servers"), "paths": sorted(spec["paths"]),
                  "health": c.get("/health").status_code, "prefixed_health": c.get("/sentinel/health").status_code,
                  "docs": c.get("/docs").text}))
"""


def _probe(root_path: str | None) -> dict:
    env = {**os.environ, "ENVIRONMENT": "test"}
    env.pop("ROOT_PATH", None)
    if root_path is not None:
        env["ROOT_PATH"] = root_path
    out = subprocess.run([sys.executable, "-c", _PROBE], cwd=BACKEND, env=env, capture_output=True, text=True, check=True)
    return json.loads(out.stdout.strip().splitlines()[-1])


def test_default_is_unprefixed():
    r = _probe(None)
    assert r["root_path"] == "" and not r["servers"]
    assert "/v1/devices/register" in r["paths"]
    assert r["health"] == 200
    assert "/openapi.json" in r["docs"] and "/sentinel" not in r["docs"]


def test_root_path_prefixes_openapi_and_docs_but_not_routes():
    r = _probe("/sentinel")
    assert r["root_path"] == "/sentinel"
    assert r["servers"] == [{"url": "/sentinel"}]
    assert "/v1/devices/register" in r["paths"]  # routes themselves stay unprefixed (proxy strips the prefix)
    assert r["health"] == 200  # (Starlette also tolerates a leading root_path, so /sentinel/health is 200 too)
    assert "/sentinel/openapi.json" in r["docs"]


def test_trailing_slash_in_root_path_is_normalised():
    assert _probe("/sentinel/")["servers"] == [{"url": "/sentinel"}]


def test_default_app_in_suite_is_unprefixed():
    assert app.root_path == ""
    assert TestClient(app).get("/health").json() == {"status": "ok"}


def test_dashboard_asset_urls_resolve_under_a_prefix():
    """Page at <prefix>/dashboard references assets/API by RELATIVE URL so they follow the prefix."""
    html = (STATIC / "dashboard.html").read_text()
    refs = re.findall(r'(?:src|href)="([^"]+)"', html)
    assert refs
    for ref in refs:
        assert not ref.startswith("/"), ref
    page = "https://interns.enthral.ai/sentinel/dashboard"
    resolved = {urljoin(page, r) for r in refs}
    assert "https://interns.enthral.ai/sentinel/dashboard/dashboard.css" in resolved
    assert "https://interns.enthral.ai/sentinel/dashboard/dashboard.js" in resolved
    js = (STATIC / "dashboard.js").read_text()
    m = re.search(r'fetch\("([^"]+)"', js)
    assert m and not m.group(1).startswith("/")
    assert urljoin(page, m.group(1)) == "https://interns.enthral.ai/sentinel/v1/dashboard/timeline"
    # and still correct with no prefix
    assert urljoin("http://h/dashboard", m.group(1)) == "http://h/v1/dashboard/timeline"
