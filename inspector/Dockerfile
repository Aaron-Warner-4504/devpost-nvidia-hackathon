# Sentinel link inspector. Fetches attacker-supplied URLs: run it read-only, unprivileged, on an
# internal network reachable only by the api (see README.md and backend/docker-compose.yml).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_PYTHON_DOWNLOADS=never \
    UV_NO_CACHE=1 \
    UV_PROJECT_ENVIRONMENT=/srv/.venv \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright

COPY --from=ghcr.io/astral-sh/uv:0.11.6 /uv /bin/uv
WORKDIR /srv
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev \
 && /srv/.venv/bin/playwright install --with-deps --only-shell chromium \
 && rm -rf /var/lib/apt/lists/* /root/.cache \
 && useradd --system --uid 10001 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin inspector

COPY app.py guard.py fetch.py detect.py render.py feeds.py ./

# No secrets are baked in: INSPECTOR_SECRET (required), SAFE_BROWSING_API_KEY and
# URLHAUS_AUTH_KEY (optional) come from the runtime environment.
USER 10001
ENV HOME=/tmp
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD ["/srv/.venv/bin/python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2).status == 200 else 1)"]
CMD ["/srv/.venv/bin/uvicorn", "--factory", "app:create_app", "--host", "0.0.0.0", "--port", "8080", \
     "--no-server-header", "--limit-concurrency", "64", "--timeout-keep-alive", "5"]
