"""enable row level security on every public table (Supabase exposes public via the Data API)

No policies are created: anon/authenticated are denied everything; the app connects as the owning role,
which bypasses RLS. Roles anon/authenticated/service_role only exist on Supabase: the REVOKEs are
skipped on plain Postgres. Every future migration that adds a table must enable RLS too
(tests/test_rls.py enforces it).

Revision ID: 0008
Revises: 0007
"""
from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
    DO $$
    DECLARE t record;
    BEGIN
        FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.tablename);
        END LOOP;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')
           AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
            REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
            ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;
            ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated;
        END IF;
    END $$;
    """)


def downgrade() -> None:
    # privileges are not restored (they were Supabase defaults we deliberately removed)
    op.execute("""
    DO $$
    DECLARE t record;
    BEGIN
        FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
            EXECUTE format('ALTER TABLE public.%I DISABLE ROW LEVEL SECURITY', t.tablename);
        END LOOP;
    END $$;
    """)
