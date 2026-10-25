# Sentinel link inspector. Fetches attacker-supplied URLs: run it read-only, unprivileged, on an
# internal network reachable only by the api (see README.md and backend/docker-compose.yml).
#
# Two stages keep the final image small: uv and the browser download live in `build`; the runtime image has
# only the venv, chromium-headless-shell (no firefox/webkit, no ffmpeg) and its OS libraries.
FROM python:3.12-slim AS build
ENV PYTHONDONTWRITEBYTECODE=1 \
    UV_PYTHON_DOWNLOADS=never \
    UV_NO_CACHE=1 \
    UV_PROJECT_ENVIRONMENT=/srv/.venv \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
COPY --from=ghcr.io/astral-sh/uv:0.11.6 /uv /bin/uv
WORKDIR /srv
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev \
 && /srv/.venv/bin/playwright install --only-shell chromium \
 && rm -rf /opt/ms-playwright/ffmpeg-* /root/.cache

# libgbm1 pulls in mesa's software GL stack (LLVM, z3, gallium: ~190 MB). chrome-headless-shell renders with its
# bundled SwiftShader and only links libgbm itself, so those three packages are removed (dpkg --force-depends;
# apt is not used afterwards). Verified: launch, JS, DOM text and screenshots work with the inspector's ARGS.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
WORKDIR /srv
COPY --from=build /srv/.venv /srv/.venv
COPY --from=build /opt/ms-playwright /opt/ms-playwright
RUN /srv/.venv/bin/playwright install-deps chromium \
 && rm -rf /var/lib/apt/lists/* /root/.cache \
 && dpkg --remove --force-depends libllvm19 libz3-4 mesa-libgallium \
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
