# Sentinel

**A privacy-first Android guardian for India that remembers who has contacted you and warns you, with the reasons, before you pay, share an OTP or install something.**

Built for the Nebius x NVIDIA Global AI Hackathon (Devpost), track Personal AI. Inference runs on Nebius Token Factory with NVIDIA Nemotron; web lookups use Tavily.

## The problem

Fraud in India is increasingly multi-step: a courier SMS today, a call from an unknown number tomorrow, a "customs officer" on WhatsApp demanding a UPI payment the day after. UPI collect requests, "digital arrest" calls, courier/customs, fake KYC and job-task scams share this shape. Each message can look harmless on its own. A filter that judges one message at a time misses the scam; the scam only shows up when the messages are put together.

## What Sentinel does

- Keeps a time-aware memory of the phone numbers, domains, UPI ids, organisations and claims that have contacted you (Postgres with pgvector). Entities are linked by shared identifiers and by semantic similarity, so a scam spread over different numbers can still be connected.
- Scores every event with deterministic rules that set a **floor tier** (info, caution, high, critical). A Nemotron tool-using agent investigates (history, domain age and lookalikes, sandboxed link inspection, Tavily web lookup) and may raise the tier only by citing tool-confirmed facts. It can never lower the floor.
- Alerts with the reason: a "why" timeline of the earlier linked events, one next step, and one-tap safe/scam feedback. Only high and critical interrupt.
- Family pairing by a 6-character invite code, built on the backend and in the app. Push to the family member is **not live** (Firebase not wired); see the status table.

## 60-second look

| Home | Critical alert with the "why" timeline | Family invite | Onboarding |
|---|---|---|---|
| ![Home](android/docs/screenshots/minimal/home-light.png) | ![Critical alert](android/docs/screenshots/minimal/alert-detail-critical-light.png) | ![Family invite](android/docs/screenshots/minimal/family-invite-light.png) | ![Onboarding](android/docs/screenshots/minimal/onboarding-1-welcome-light.png) |

These are emulator screenshots of the Android app (`android/docs/screenshots/`). The critical alert shown is the demo story: courier SMS, then a call, then a WhatsApp payment demand, linked by memory.

## Architecture

Source: [`docs/submission/architecture.mmd`](docs/submission/architecture.mmd) (rendered: [`architecture.svg`](docs/submission/architecture.svg)).

```mermaid
flowchart LR
  subgraph Phone["Android phone"]
    CAP["Capture: share sheet, notifications, call log"]
    UI["Alerts, why timeline, feedback, family"]
  end
  subgraph Host["Team VM: nginx -> FastAPI"]
    RED["Redact"] --> EXT["Extract entities"] --> MEM["Temporal memory + semantic links"]
    MEM --> RULES["Rules: floor tier"] --> AGENT["Tool-using risk agent"] --> POL["Alert policy"]
    INS["Link inspector (sandbox)"]
  end
  subgraph Nebius["Nebius Token Factory"]
    NEM["NVIDIA Nemotron chat"]
    VL["NVIDIA Nemotron VL"]
    EMB["Qwen3-Embedding-8B"]
  end
  DB[("Supabase Postgres + pgvector")]
  TAV["Tavily"]
  CAP -->|HTTPS event| RED
  MEM <--> DB
  MEM <--> EMB
  AGENT <--> NEM
  AGENT --> TAV
  AGENT --> INS
  POL -->|alert + why| UI
  UI --> CAP
```

Walk-through of one event:

1. **Capture.** The app sends an event (SMS, WhatsApp or other notification text, call metadata, or a "Check this" share) to the API over HTTPS with a per-device bearer token.
2. **Redact.** OTPs, card, account, Aadhaar and PAN numbers are masked before storage, embedding or any LLM call (`backend/app/pipeline/redact.py`). See the privacy section for what runs where.
3. **Rules floor.** Entities are extracted and rule signals (payment request, OTP ask, urgency, lookalike domain, channel hop, story progression and others in `backend/app/pipeline/signals.py`) set the minimum tier.
4. **Temporal memory and semantic links.** The event is linked to earlier ones by shared entities and by embedding similarity (valid_from/valid_to edges, evidence event ids).
5. **Nemotron agent.** For escalated events the agent calls tools (`get_entity_history`, `check_url`, `check_number`, `inspect_link`, `web_lookup`) and returns a verdict and explanation. Without a live model it degrades to the rules result.
6. **Alert policy.** Dedupe, daily cap and flood guard decide whether the alert is shown and whether it interrupts. The phone shows a local notification and the "why" timeline.

## Where the sponsor technology is used

| Component | Technology | Used for |
|---|---|---|
| Agent reasoning and tool calling | NVIDIA Nemotron chat (`nvidia/nemotron-3-super-120b-a12b`) on Nebius Token Factory | Investigates escalated events, writes the explanation |
| Screenshot checks | NVIDIA Nemotron vision (`nvidia/Nemotron-3.5-Super-VL`) on Token Factory | Reads text from a shared screenshot |
| Semantic memory | `Qwen/Qwen3-Embedding-8B` on Token Factory | Embeds redacted text to link messages with no shared identifier |
| Web evidence | Tavily search | `web_lookup` tool for a brand, domain or number |

Model IDs are environment settings (`LLM_MODEL`, `VISION_MODEL`, `EMBED_MODEL`), not code. The IDs above are the team's deployed configuration; `backend/.env.example` still lists earlier placeholder IDs. Hosting: the API runs on the team's own VM (`https://interns.enthral.ai/sentinel`) with Supabase Postgres; the inference is on Nebius Token Factory. The API is not hosted on Nebius AI Cloud.

## Tech stack

Android: Kotlin, Jetpack Compose, own design system. Backend: Python, FastAPI, SQLAlchemy, Alembic, Postgres with pgvector (Supabase). Link inspector: separate FastAPI service with headless Chromium (Playwright). Deploy: Docker, nginx. Eval: seeded synthetic scenario generator plus runner.

## Demo timeline

1. **Day 1.** An unknown "BlueDart" SMS says a parcel is held and gives a number and a link. Info only; Sentinel remembers it quietly.
2. **Day 2.** A 4-minute call from the same number. Memory links the number to the courier claim: caution.
3. **Day 3.** A WhatsApp "customs officer" demands Rs 25,000 to a UPI id and says not to tell anyone. **Critical alert** with the "why" timeline showing the SMS, the call and this message.

The exact payloads are in `backend/sim/inject.py`. Script for the video: [`docs/submission/demo-video-script.md`](docs/submission/demo-video-script.md).

## Privacy and safety design

- **Redaction before storage and before the LLM.** The backend stores and embeds only redacted text and never sends raw text to a model (`orchestrator.py`, `redact.py`; redaction is covered by `backend/tests/test_redact.py`). Raw text does reach the API over TLS before it is redacted; a Kotlin port that masks on the phone first is in progress and is listed as not yet verified below.
- **Delete everything.** `DELETE /v1/me` removes the device and its data, including family links and stored events.
- **Prompt-injection defences** (`backend/app/pipeline/agent.py`, `backend/tests/test_injection.py`): message text, history, rule evidence and tool results are passed as untrusted data inside nonce-delimited blocks, and delimiter-like text in the data is neutralised. Tool arguments are allowlisted against the entities extracted from the event. Injection phrasing is treated as evidence of a scam, not obeyed. Model headlines have phone numbers, UPI ids and emails stripped.
- **Floor tier cannot be lowered.** Rules set a minimum; the agent can only raise it, and only up to a cap derived from tool-confirmed facts. A clean link result never lowers a tier (scammers cloak).
- **Sandboxed link inspector** (`inspector/`): the API never fetches message URLs itself. A separate container (non-root, read-only filesystem, no route to the database) follows redirects with an SSRF guard that blocks internal addresses, with timeouts and size caps.
- **Rate limits and caps** on registration, checks, pairing attempts and alerts (`backend/app/ratelimit.py`, `policy.py`).

## Honest status

| Area | Status |
|---|---|
| Backend pipeline: redact, extract, rules, temporal memory, agent loop with fake/recorded LLM, alert policy, family pairing API | Built and verified by the automated test suite (`backend/tests/`) |
| Evaluation harness, 147 synthetic scenarios, rules/agent/hybrid and memory ablations | Built and run. Results in [`backend/eval/report.md`](backend/eval/report.md). Synthetic data, scripted stand-in agent: see caveats |
| Live API on the team VM, signed release APK | API deployed (`GET /health` returned `{"status":"ok"}` on 29 Oct). Signed release APK build is documented; a full on-phone run against the live server is for the team to confirm |
| Android app screens: onboarding and consent, home, alerts, why timeline, Check this (text), family pairing, settings, delete my data | Built and verified on an emulator against a live backend (see `android/README.md`, "Verified vs not") |
| Always-on capture (notification listener, call log) | In progress on a separate branch at the time of writing. Not in `main`; confirm before relying on it |
| Real-model agent quality (Nemotron) | Not measured. The eval agent is a deterministic script. A live run (`run_eval.py --llm live`) has not been recorded |
| Semantic links with Qwen3-Embedding-8B | Wired, but `SEMANTIC_THRESHOLD` (0.25) was tuned for a local MiniLM model, not Qwen3. Not re-tuned |
| Screenshot check (vision model) | Built; end-to-end on the live server not verified |
| Link inspector on the VM | Built and tested separately; not deployed on the shared VM (API-only deploy) |
| Family push alerts | Not live: Firebase is not wired, so family members receive nothing |
| On-device redaction (Kotlin) | Not in `main`; redaction currently runs on the server |
| Gmail ingest, iOS, billing, B2B portal | Not built |

## Repo map

| Path | What it is |
|---|---|
| `backend/app/` | FastAPI app. `pipeline/` has redact, extract, signals, memory, semantic, agent, tools, policy, review |
| `backend/eval/` | Evaluation harness, scenarios and results (`report.md`) |
| `backend/sim/inject.py` | Demo timeline simulator (courier, call, WhatsApp UPI) |
| `backend/tests/` | Backend test suite (needs Postgres with pgvector) |
| `inspector/` | Sandboxed link inspector service |
| `android/` | Android app, design docs, screenshots |
| `frontend/` | Plain HTML test console for the API (testing only) |
| `deploy/` | Production and shared-host deployment kit |
| `docs/` | Plan, API contract, memory and classifier notes, submission pack |

## How to run

**Backend (local).**
```bash
cd backend
cp .env.example .env          # add TOKEN_FACTORY_API_KEY (and TAVILY_API_KEY) for live models
docker compose up -d db       # Postgres + pgvector on 127.0.0.1:5440
export DATABASE_URL=postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel
PYTHONPATH=. uv run alembic upgrade head
uv run uvicorn app.main:app --port 8000
uv run python -m sim.inject --user demo --base-url http://localhost:8000   # injects the 3-day story
```
A full run-from-scratch with the web test console is in [`frontend/README.md`](frontend/README.md).

**Tests.** `cd backend && uv run pytest` (needs the database container above). Inspector: `cd inspector && uv sync && uv run pytest -q`. Android: `cd android && source scripts/env.sh && ./gradlew testDebugUnitTest assembleDebug lintDebug`.

**Evaluation.** `cd backend && uv run python eval/run_eval.py` (147 scenarios x 3 modes); memory ablation: `uv run python eval/run_eval.py --ablation`. Details and flags: [`backend/eval/README.md`](backend/eval/README.md).

**Android release build.**
```bash
cd android && source scripts/env.sh
./gradlew assembleRelease -PsentinelApiBaseUrl=https://interns.enthral.ai/sentinel
```
Signing and install steps: [`android/README.md`](android/README.md).

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md): product, hackathon fit, scope, business path
- [`docs/api-contract.md`](docs/api-contract.md): API v1
- [`docs/memory.md`](docs/memory.md): temporal and semantic memory, two-stage review
- [`docs/classifier.md`](docs/classifier.md): zero-shot classifier spike (not adopted)
- [`backend/eval/report.md`](backend/eval/report.md): evaluation results and caveats
- [`android/docs/design.md`](android/docs/design.md): design system
- [`inspector/README.md`](inspector/README.md), [`deploy/shared-host/README.md`](deploy/shared-host/README.md)
- Submission pack: [`docs/submission/`](docs/submission/) (Devpost text, video script, slides, judging notes, checklist)

## Licence

No licence file is present in the repository yet.
