# Targets: `prod` (non-root, locked deps, migrates then serves; used by deploy/docker-compose.prod.yml) and
# `dev` (default when no --target is given; what backend/docker-compose.yml builds).
FROM python:3.12-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_PYTHON_DOWNLOADS=never \
    UV_NO_CACHE=1 \
    UV_PROJECT_ENVIRONMENT=/srv/.venv \
    PYTHONPATH=/srv \
    PATH="/srv/.venv/bin:$PATH"
COPY --from=ghcr.io/astral-sh/uv:0.11.6 /uv /bin/uv
WORKDIR /srv
COPY pyproject.toml uv.lock .python-version ./

# Builder: needs uv; the final prod image does not (uv is ~56 MB we never use at runtime).
FROM base AS prod-build
RUN uv sync --frozen --no-dev

FROM python:3.12-slim AS prod
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/srv \
    PATH="/srv/.venv/bin:$PATH"
WORKDIR /srv
COPY --from=prod-build /srv/.venv /srv/.venv
RUN useradd --system --uid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin sentinel
COPY app ./app
COPY alembic.ini .
COPY alembic ./alembic
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh
USER sentinel
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=4s --start-period=30s --retries=4 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status == 200 else 1)"
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

# dev stays last so a plain `docker build` / compose `build: .` keeps the old behaviour
FROM base AS dev
RUN uv sync --frozen --no-dev
COPY app ./app
COPY alembic.ini .
COPY alembic ./alembic
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
