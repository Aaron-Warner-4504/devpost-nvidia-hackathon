#!/bin/sh
# Apply database migrations, then exec the server (PID 1 gets the signals).
set -eu
echo "running alembic upgrade head"
alembic upgrade head
exec "$@"
