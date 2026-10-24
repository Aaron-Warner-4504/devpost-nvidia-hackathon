"""Check the hosted Postgres this deployment will use (reads DATABASE_URL; never prints the password).

    DATABASE_URL=postgresql+psycopg://... uv run python scripts/db_check.py

Reports server version, pgvector (installed / installable), Alembic revision vs head and whether
pg_advisory_xact_lock works. Exit 0 if everything is OK, 1 on any failure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from alembic.config import Config  # noqa: E402
from alembic.script import ScriptDirectory  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402

from app.db import connect_args  # noqa: E402
from app.db_url import check_database_url  # noqa: E402


def main() -> int:
    url = os.environ.get("DATABASE_URL", "")
    try:
        check_database_url(url, os.environ.get("ENVIRONMENT", "dev"))
    except ValueError as e:
        print(f"FAIL url: {e}")
        return 1
    ok = True
    eng = create_engine(url, connect_args=connect_args())
    print(f"target: {eng.url.render_as_string(hide_password=True)}")
    try:
        with eng.connect() as c:
            print("OK   version:", c.execute(text("SHOW server_version")).scalar())
            tls = c.execute(text("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")).scalar()
            print("OK   tls:" if tls else "WARN tls:", "connection encrypted" if tls else "connection NOT encrypted")
            if c.execute(text("SELECT 1 FROM pg_extension WHERE extname='vector'")).scalar():
                print("OK   pgvector: installed")
            else:
                avail = c.execute(text("SELECT default_version FROM pg_available_extensions "
                                       "WHERE name='vector'")).scalar()
                if not avail:
                    print("FAIL pgvector: not available on this server; use a provider/image with pgvector")
                    ok = False
                else:
                    try:
                        with c.begin_nested() as sp:
                            c.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
                            sp.rollback()
                        print(f"OK   pgvector: available ({avail}), not yet enabled; this user can create it")
                    except Exception:
                        print(f"FAIL pgvector: available ({avail}) but this user cannot create it; "
                              "enable it in the provider console or as an admin before migrating")
                        ok = False
            sch = c.execute(text("SELECT n.nspname FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace "
                                 "WHERE t.typname='vector'")).scalars().all()
            if sch:
                print(f"OK   vector type schema: {', '.join(sch)}")
                try:
                    with c.begin_nested() as sp:
                        c.execute(text("SELECT '[1,2,3]'::vector(3)"))
                        sp.rollback()
                    print("OK   vector(3) resolves with the connection search_path "
                          f"({c.execute(text('SHOW search_path')).scalar()})")
                except Exception:
                    print("FAIL vector(...) does not resolve for this role: add the schema above to "
                          "DB_SEARCH_PATH (default public,extensions)")
                    ok = False
            cur = None
            if c.execute(text("SELECT to_regclass('alembic_version')")).scalar():
                cur = c.execute(text("SELECT version_num FROM alembic_version")).scalar()
            cfg = Config(str(Path(__file__).resolve().parent.parent / "alembic.ini"))
            cfg.set_main_option("script_location", str(Path(__file__).resolve().parent.parent / "alembic"))
            head = ScriptDirectory.from_config(cfg).get_current_head()
            if cur == head:
                print(f"OK   alembic: at head ({head})")
            else:
                print(f"INFO alembic: current={cur} head={head} (the api entrypoint migrates on start)")
            nors = c.execute(text("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                                  "WHERE n.nspname='public' AND c.relkind='r' AND NOT c.relrowsecurity")).scalars().all()
            if nors:
                print(f"WARN rls: tables without row level security: {', '.join(nors)} "
                      "(run migrations; required on Supabase)")
            else:
                print("OK   rls: enabled on all public tables")
            c.rollback()
            with c.begin():
                c.execute(text("SELECT pg_advisory_xact_lock(hashtext('db_check'))"))
            print("OK   pg_advisory_xact_lock works")
    except Exception as e:
        print(f"FAIL connection/query: {type(e).__name__}: check host, credentials, sslmode, IP allow-list")
        return 1
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
