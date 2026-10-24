# Sentinel test console

A plain HTML/CSS/JS page (no build, no CDN) to exercise every Sentinel backend feature by hand.
For testing only, not production: tokens are kept in sessionStorage (cleared when the tab closes).

## Run from scratch

```bash
# 1. database
cd backend && docker compose up -d db

# 2. environment (same shell as steps 3 and 4)
export DATABASE_URL=postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel
export ENVIRONMENT=dev
export CORS_ALLOW_ORIGINS=http://localhost:5173
export DEMO_ENABLED=true

# 3. migrations
PYTHONPATH=. uv run alembic upgrade head

# 4. backend (http://localhost:8000)
uv run uvicorn app.main:app --reload

# 5. console (new terminal)
cd frontend && python3 -m http.server 5173
```

6. Open <http://localhost:5173>, click **Register new device**, then **Run 3-day demo**.

If the red banner says the backend is unreachable, either it is not running or `CORS_ALLOW_ORIGINS` does not
exactly match the origin shown in the address bar (scheme, host and port).

## Token Factory key

Without `TOKEN_FACTORY_API_KEY` everything still works rules-only: scoring, signals and tiers come from the
deterministic rules, agent verdicts degrade (marked as degraded) and screenshot checks fail with 502 because
the vision model is unavailable. To enable the agent and vision, copy `backend/.env.example` to `backend/.env`,
set `TOKEN_FACTORY_API_KEY` (plus the model names there), and restart uvicorn.

## Testing family pairing (two profiles)

Pairing needs two different users, so two tokens. In Connection, add profiles `protected` and `guardian`
(select one, click Register for each). The selected profile signs every request. Then: as `protected` tick
consent and Create invite; switch to `guardian`, enter the code and Accept; GET links under either profile;
Unlink. Pushes need Firebase credentials on the backend and are not visible in this console.

## Checklist

| Feature | What to click | Expected |
|---|---|---|
| Duplicate delivery | Send event, tick "reuse same id", send twice | 2nd response `duplicate: true`, no alert |
| Bank OTP stays quiet | Preset "Bank OTP SMS", Send | No alert (or info-level only, never high/critical) |
| 3-day demo reaches critical | Run 3-day demo (fresh device) | Last step tier `critical` with a why list citing earlier events |
| Injection does not lower tier | Preset "Prompt-injection", Send | High/critical with `injection_attempt` signal, not info |
| Feedback safe/scam | Alerts > Load, press Safe or Scam | Toast `Feedback saved`; later events from that sender score lower (safe) or critical (scam content) |
| Invite without consent | Family: untick consent, Create invite | `HTTP 422` shown |
| Wrong code | Guardian profile, accept `ZZZZZZ` | `HTTP 404: invalid or expired code` |
| Rate limit 429 | Check text (30/h) or image (10/h): repeat; or Register 20+ times | `HTTP 429` message |
| Image check 413/415 | Check > Image: pick a >5 MB file; pick a non-image (e.g. .txt) | 413 `image too large`; 415 `unsupported image type`. Without a model key a real image gives 502 |
| Delete me | Account > DELETE /v1/me, confirm | `HTTP 204`, token cleared, `/v1/me` then returns 401 |
| CORS | Stop backend or unset CORS_ALLOW_ORIGINS | Red banner with the fix |
| Dashboard | "Dashboard" link | Works only with `DEMO_ENABLED=true` (asks for a token) |
