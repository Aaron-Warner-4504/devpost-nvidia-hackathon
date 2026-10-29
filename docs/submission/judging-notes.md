# Judging notes

The Judging tab on Devpost was not readable from here. Re-read it and re-map the table below to the real criteria and weights. The rows use the usual categories.

## Criteria to evidence

| Likely criterion | Evidence (paths in this repo) |
|---|---|
| Technical depth | Temporal memory with valid_from/valid_to edges and semantic links: `docs/memory.md`, `backend/app/pipeline/memory.py`, `semantic.py`. Tool-using agent with tier cap: `backend/app/pipeline/agent.py`, `tools.py`. Rules floor: `signals.py`. Two-stage background review: `review.py`. Alert policy: `policy.py`. Injection tests: `backend/tests/test_injection.py`. Sandboxed inspector: `inspector/` |
| Usefulness / real problem | Product definition and target user: `docs/PLAN.md` section 3. Scenario categories in `backend/eval/scenarios/` (courier/customs, digital arrest, UPI collect, KYC, job task, investment group, sextortion, remote access, electricity bill, slow-burn). Android app with the why timeline: `android/README.md`, `android/docs/screenshots/minimal/` |
| Use of sponsor tech | Token Factory client and model settings: `backend/app/llm.py`, `backend/app/config.py`, `backend/.env.example` (IDs there are placeholders; deployed IDs are in the team's prod env). Nemotron agent loop: `agent.py`. Embeddings: `backend/app/pipeline/embeddings.py`. Vision: `ocr.py`. Tavily: `web_lookup` in `tools.py`. Smoke script: `backend/scripts/smoke_llm.py` |
| Demo quality | Reliable demo story: `backend/sim/inject.py`, Android demo mode (debug build), script `docs/submission/demo-video-script.md`, web console `frontend/` |
| Evaluation / rigour | `backend/eval/` with generator, runner, report and caveats: `backend/eval/report.md`. Classifier spike that was rejected on evidence: `docs/classifier.md` |
| Business path | `docs/PLAN.md` sections 3 and 9. Release and deploy kit: `deploy/`, `android/README.md` "Release build" |

## What a judge can verify in 5 minutes

1. Server is up (read-only): `curl -s https://interns.enthral.ai/sentinel/health` returns `{"status":"ok"}`. API docs are switched off in production, so there is no Swagger page.
2. Read the headline result and its caveats: `backend/eval/report.md` (top table and "Caveats").
3. Reproduce the eval locally (needs Docker for Postgres): `cd backend && docker compose up -d db && export DATABASE_URL=postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel && uv run python eval/run_eval.py --ablation --embeddings fake`. With `--embeddings fake` semantic linking is lexical and not measured; the real number needs `uv sync --extra semantic` (471 MB model).
4. Run tests: `cd backend && uv run pytest` (Postgres with pgvector required); `cd inspector && uv sync && uv run pytest -q`.
5. Run the story: `uv run python -m sim.inject --user demo --base-url http://localhost:8000` and open the `frontend/` console, or look at `android/docs/screenshots/minimal/alert-detail-critical-light.png`.
6. Install the APK only if the team provides one; it is not in the repo.

Do not ask judges to run anything that registers devices on the live server in bulk; the endpoints are rate limited.

## Known gaps and honest answers

| Question a judge may ask | Answer |
|---|---|
| Is capture always-on? | Not in `main` at the time of writing. The app has the notification-access permission flow, "Check this" (text and screenshot) and demo mode. Always-on capture was in progress on another branch; check `docs/capture.md` if it has been merged, and update this answer to match what you verified on the phone. |
| Do family members get alerts? | Not live. Pairing (invite code, consent, unlink) is built on backend and app, but push needs Firebase, which is not wired. |
| Does the agent really use Nemotron? | The agent loop calls the Token Factory chat model with tools. The evaluation uses a scripted stand-in, so the ablation does not measure Nemotron quality. Say this before they ask. A `--llm live` run is available but no recorded result exists. |
| Are the embeddings tuned? | The link threshold (0.25) was tuned for a local MiniLM model; it was not re-tuned for Qwen3-Embedding-8B. `scripts/tune_semantic_threshold.py` exists for this. |
| Is the eval real? | Synthetic, template-generated, written by the team that tuned the rules. Tune recall of 1.000 is overfitting to our templates. The holdout is small (18 scams) and we state a holdout leak in `report.md`. |
| Raw text and privacy? | Raw text reaches the API over TLS, then is redacted before storage, embedding and any LLM call. A Kotlin redaction port for on-phone masking was in progress; do not claim it unless merged and verified. |
| Link inspector in production? | Built and tested; not deployed on the shared VM (the VM was low on disk and RAM, so an API-only deploy was used). With no inspector configured, link inspection degrades to an unfetched report (`fetched_ok=false`, see `backend/app/pipeline/link_client.py`) and the other checks still run. |
| Latency? | Eval latency (p50 about 23 ms, p95 about 96 ms) is in-process with fixtures and no network, so it is not production latency. The database is hosted on Supabase in a remote region, so each request pays network round trips; measure before quoting a figure. |
| Where does it run? | The API on the team's VM behind nginx; inference on Nebius Token Factory. Not on Nebius AI Cloud. |
| Hindi coverage? | Rules and scenarios cover English, Hindi and Hinglish; the three remaining memory misses are Devanagari Hindi. UI strings exist in English and Hindi; server-written alert text is English. |

## Business path (from `docs/PLAN.md`, a plan and not validated)

- Free tier: manual "Check this" and basic alerts. Paid tier: always-on monitoring, memory and family alerts. Later: B2B2C with banks, telcos and insurers. `docs/PLAN.md` section 3 gives a price hypothesis of about Rs 99-149 per month (individual) or Rs 299 per month (family); it has not been tested with any user, so describe it as a hypothesis or leave it out.
- Next 4 weeks: private beta of about 50 users in India, measuring false alerts per user-week and alerts acted on.
- Gates before launch: Google Play notification-access declaration, DPDP Act notice and consent process, data-retention policy.
- Moat claimed in the plan: the memory graph, a labelled scam-timeline dataset and the feedback loop. Today the dataset is synthetic.
- LLM cost control: rules and triage gate the agent so only escalated events call the model (`docs/PLAN.md` section 9 estimates about 95 percent gated; that estimate is not measured in the repo).
