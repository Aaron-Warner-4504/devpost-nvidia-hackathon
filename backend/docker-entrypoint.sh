#!/bin/sh
# Validate DATABASE_URL, wait for the (hosted) database, apply migrations, then exec the server.
set -eu
python - <<'PY'
import os, sys, time
from sqlalchemy import create_engine, text
from app.db_url import check_database_url

url = os.environ.get("DATABASE_URL", "")
try:
    check_database_url(url, os.environ.get("ENVIRONMENT", "dev"))
except ValueError as e:
    sys.exit(f"FATAL: {e}")
eng = create_engine(url)
deadline = time.monotonic() + 60
while True:
    try:
        with eng.connect() as c:
            c.execute(text("SELECT 1"))
        break
    except Exception as e:
        # only the exception type and host: the message can echo connection details
        if time.monotonic() > deadline:
            sys.exit(f"FATAL: database {eng.url.host}:{eng.url.port} unreachable after 60s "
                     f"({type(e).__name__}); check DATABASE_URL, sslmode, credentials and the "
                     "provider's IP allow-list")
        print(f"waiting for database {eng.url.host} ({type(e).__name__})", flush=True)
        time.sleep(3)
print("database reachable", flush=True)
PY
echo "running alembic upgrade head"
alembic upgrade head
exec "$@"
