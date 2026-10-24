"""Supabase exposes every public table through its Data API unless RLS is on: after `upgrade head`
every public table must have row level security enabled. A new migration that forgets fails here."""
from sqlalchemy import text


def test_every_public_table_has_rls(test_engine):
    with test_engine.connect() as c:
        rows = c.execute(text(
            "SELECT c.relname, c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')")).all()
    assert rows
    missing = [r[0] for r in rows if not r[1]]
    assert not missing, f"tables without row level security (enable it in your migration): {missing}"
