# Persistent memory and two-stage review

Sentinel has to catch a scam that is told in installments: three or four messages, each harmless on its
own, often from different numbers, with no phone, link, organisation or claim in common. This document
describes how memory links such messages and how a background review turns the link into an alert.

Postgres (with pgvector) is the only memory store. A shared scam-network graph (Neo4j) is planned for later
and is not part of this design.

## 1. What is remembered (per user)

| store | table | written | used for |
|---|---|---|---|
| events | `events` | stage 1 | redacted text, source, time, hashed sender key |
| entities and links | `entities`, `event_entities`, `edges` (`co_occurs`, `claims`) | stage 1 | entity history, channel hops, repeat contact |
| assessments | `assessments` (`signals`, `payload.stages`) | stage 1 and stage-2 upgrades | stored signal names and story stages of every event |
| vectors | `embeddings` (one per event, `model` = provider name) | stage 2 | semantic nearest neighbours |
| semantic links | `edges` with `relation = "similar"` between two `entities` of `type = "event"` (canonical = event id), `evidence_event_ids` = both ids | stage 2 | linked history, dashboard graph, `why` timeline |
| review jobs | `event_jobs` | stage 1 (queued), stage 2 (state) | idempotent background work |

Every query filters on `user_id`; neighbours, links and history never cross users (tests:
`test_semantic.py::test_two_users_near_identical_messages_never_link`,
`test_review.py::test_story_of_one_user_never_upgrades_another`, `test_story.py::test_api_story_is_per_user`).

## 2. Semantic links

`app/pipeline/embeddings.py` provides `embed(texts) -> list[list[float]]`:

| provider | setting | where text goes | notes |
|---|---|---|---|
| `token_factory` | `EMBEDDING_PROVIDER=token_factory` (auto when `TOKEN_FACTORY_API_KEY` + `EMBED_MODEL` are set) | Nebius Token Factory | hosted; production model `Qwen/Qwen3-Embedding-8B`; live embeddings NOT verified (no key in this environment) |
| `local` | `EMBEDDING_PROVIDER=local`, `uv sync --extra semantic` | stays on the host | default model `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` |
| `fake` | tests only | stays in process | hashed bag of words; lexical, not semantic |
| none | default without key / extra | nowhere | semantic memory off |

### Production embedding model (Token Factory)

Nebius Token Factory's only public embedding model is `Qwen/Qwen3-Embedding-8B` (multilingual including
Hindi, 4096 dimensions natively, trained with Matryoshka representation learning so a prefix of the vector
is itself a valid embedding). Confirm the exact ID with `GET {TOKEN_FACTORY_BASE_URL}/models`. Settings:
`EMBEDDING_PROVIDER=token_factory`, `EMBED_MODEL=Qwen/Qwen3-Embedding-8B`, `EMBED_DIM=1024`.

Dimension handling (token_factory provider):
- With `EMBED_SEND_DIMENSIONS=true` (default) requests carry `dimensions=EMBED_DIM`. If the server rejects it
  (HTTP 400/422 or an error naming dimensions) the request is retried once without it and the parameter is
  not sent again for the rest of the process.
- Whatever comes back is post-processed: a vector longer than `EMBED_DIM` is truncated to its first
  `EMBED_DIM` values and L2-normalised (cosine stays meaningful); a shorter one is unit-normalised and
  zero-padded; non-finite or all-zero vectors are rejected (no embedding for that event, never an error
  for the pipeline).
- Requests hold at most 16 texts and use `EMBED_TIMEOUT_S`. Text is redacted again before sending and is
  never logged.

Local model: paraphrase-multilingual-MiniLM-L12-v2, Apache-2.0, 118M parameters, 471 MB safetensors download
(fp32), 384 dimensions, 50+ languages including Hindi; about 20 ms per message on a laptop CPU after a few
seconds' load. It was chosen over intfloat/multilingual-e5-small (MIT, 384-d) because e5 put every pair of
our messages between 0.77 and 0.88 cosine (no usable separation), while MiniLM spread unrelated pairs at
0 to 0.3 and English/Hindi paraphrases at about 0.55. Romanised Hinglish is handled less well than English
or Devanagari Hindi.

Rules:
- Only redacted text is embedded (`embed_safe` redacts again before sending). Calls have a timeout
  (`EMBED_TIMEOUT_S`) and never raise: a failure means "no semantic links" for that event.
- One vector per event, stored with the provider name; vectors of different providers are never compared.
  `EMBED_DIM` is the column width; a shorter vector is zero-padded (cosine unchanged), a longer one disables
  linking. Migration 0009 resizes the column to `EMBED_DIM` only while `embeddings` is empty and refuses
  with a clear message otherwise.
- Neighbours of a new event: same user, PRIOR events within `SEMANTIC_WINDOW_DAYS` (14), at most
  `SEMANTIC_K` (<= 5), cosine >= `SEMANTIC_THRESHOLD`, excluding the event itself, events already linked
  by a shared entity and exact text duplicates. They are persisted as `similar` edges.
- `SEMANTIC_THRESHOLD = 0.25` was tuned on the eval TUNE split for the local model (sweep below). Other
  models need their own threshold: the value was NOT tuned for Qwen3-Embedding-8B and must be re-tuned
  (below) before semantic links are relied on. Until then set `SEMANTIC_MEMORY=false` if unsure.

### Re-tuning the threshold for a new model

```bash
cd backend && docker compose up -d db
export DATABASE_URL=postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel   # as in .env.example
export TOKEN_FACTORY_API_KEY=...            # or in backend/.env; never printed or stored by the script
uv run python scripts/tune_semantic_threshold.py          # prints the token estimate, sends nothing
uv run python scripts/tune_semantic_threshold.py --yes    # embeds, caches, sweeps, recommends
```

It reads the TUNE split only (no flag for the holdout), embeds every event the pipeline would embed
(order of 10^3 short messages, roughly 10^5 tokens: the script prints the exact estimate and refuses to call
the API without `--yes`), caches vectors in `backend/eval/.embed_cache/` (gitignored, keyed by
sha256(model + redacted text), so reruns are free), sweeps 0.10 to 0.60 in steps of 0.025 and reports
distributed_slow_burn scam-timeline recall and false upgrades on benign TUNE scenarios. The recommendation
is the highest-recall threshold with zero benign upgrades, centred in its plateau for margin; it is printed
and written to `backend/eval/threshold_<model>.json`. Set `SEMANTIC_THRESHOLD` to it, then check the
holdout once with `uv run python eval/run_eval.py --ablation --embedding-provider token_factory
--semantic-threshold <t>`.

## 3. Story progression (rules, no LLM)

`signals.story_stages` tags each event with stages from its signals plus short cue patterns
(English, Hindi, Hinglish), stored in `assessments.payload.stages`:

| stage | from signals | cues (examples) |
|---|---|---|
| contact | `unknown_sender` from a phone / WhatsApp / call; a scam-story claim | "got your number from", "new number", "wrong number", "hello dear", "नया नंबर", "aapka number ... mila" |
| trust | `authority_impersonation` | guaranteed / % returns, profit, part-time, stock tips group, selected, मुनाफा, कमाई |
| pressure | `urgency`, `secrecy_demand` | only today, limited slots, last chance, aaj hi, आज ही |
| ask | `payment_request`, `credential_or_otp_ask`, `remote_access_ask` | registration / kit / membership fee, verification deposit, "to secure your slot", advance, शुल्क, फीस, "fees bhar" |

No stages for saved contacts (`is_contact` / call contact), trusted or user-safe senders. Business SMS
headers (VM-HDFCBK, AX-SWIGGY) get stages from their signals only and never "contact".

`HistoryCtx.linked_events` = entity-linked prior events plus `similar` neighbours, last 7 days, each with
its stored signals, stages and hashed sender. `story_progression` (weight 3) fires on the current event when:
- (a) three distinct stages appear in story order across at least two linked events, ending in this event,
  with at least one earlier stage this event does not show itself; or
- (b) this event is an ask and a linked event from a DIFFERENT sender was a contact / trust stage.

A stage the same sender already reached in a linked event is a repeat, not a progression. When this event
is the ask, the tier floor is `high`. The signal only adds weight: it never lowers a tier, its evidence
contains stage names only (no message text), and `STORY_PROGRESSION=false` turns it off. In stage 1 it sees
entity links only; semantic neighbours exist from stage 2 on.

## 4. Two-stage flow

```
 device            API (stage 1, request)                         worker (stage 2, background)
   |  POST /v1/events  |                                                   |
   |------------------>| redact, extract, store, entity links              |
   |                   | signals (+ story over entity links), rules tier   |
   |                   | [agent if escalated], policy                      |
   |                   | INSERT assessment, alert, event_jobs(queued)      |
   |                   | COMMIT; hooks (push / family if interrupt)        |
   |<------------------| 200 {alert | null}     (latency unchanged)        |
   |                   | submit(event_id) ------------------------------->|
   |                   |                                  advisory lock(event), state=running
   |                   |                                  embed redacted text, store vector
   |                   |                                  nearest neighbours -> `similar` edges
   |                   |                                  re-score: signals with linked history
   |                   |                                  agent if AGENT_REVIEW=all (or newly
   |                   |                                    escalated), under the daily cap
   |                   |                                  tier > every tier assessed for the event?
   |                   |                                    yes: new assessment + alert via policy
   |                   |                                         (reason review_upgrade), COMMIT,
   |                   |                                         hooks with the decision
   |                   |                                  state=done, payload cleared
   |  GET /v1/alerts   |                                                   |
   |------------------>| shown alerts newest first (upgrade included)      |
```

- Stage 1 is unchanged except one extra INSERT (`event_jobs`) in the same transaction (and, with `FAST_FIRST`, no inline agent: see below).
- Stage 2 runs only when it can change something: semantic memory is on, or `AGENT_REVIEW=all`.
- Upgrades only: a new alert row for the same event with a higher tier, created through `policy.decide`
  (the tier increase passes dedupe; the daily cap, critical flood guard and ceiling still apply). Nothing is
  ever downgraded or retracted. `/v1/check` events are embedded and linked but never upgraded.
- Hooks are unchanged: own-device push and family pushes act only when `decision.interrupt` is true, family
  only for `critical`.
- Safety: one job per event (unique), per-event advisory lock held on a dedicated connection, at most 2
  attempts, a full queue (`REVIEW_QUEUE_SIZE`) or a user over `REVIEW_MAX_QUEUED_PER_USER` never blocks the request
  and never drops the job: it stays `queued` in the DB, a periodic sweeper (every `REVIEW_SWEEP_MINUTES`,
  never two at once) submits it later, round-robin across users and oldest first, so one flooding user
  cannot starve another; an LLM or embedding failure finishes the job with no upgrade, a job whose user was
  deleted exits quietly, and the sweeper also resubmits `running` jobs older than `REVIEW_SWEEP_MINUTES`
  (crash). Jobs are only
  created by the pipeline for the event it just stored; no request parameter can trigger one.
- Cost: with `AGENT_REVIEW=all` the agent runs on every event, at most `AGENT_REVIEW_DAILY_CAP` stage-2 runs
  per user per 24 h; beyond the cap stage 2 does embedding + rules only. The slot is reserved before the
  LLM call (`agent_ran=true` committed under a per-user advisory lock), so concurrent jobs cannot exceed the
  cap; a failed agent run keeps its slot (it was spent) and a retry of that job does not call the agent again.
- `review.flush_background()` runs pending jobs synchronously (tests and the eval).

### Fast-first alerts (`FAST_FIRST`, default true)

The agent needs 20 to 35 s on the live model, too slow for a real-time warning. With `FAST_FIRST=true`
(POST /v1/events only; `/v1/check` stays synchronous; requires `AGENT_MODE=hybrid`) the stages are:

```
 stage 1 (request, ~tens of ms)                      stage 2 (worker, background)
  rules floor + local lookalike/shortener checks       re-score with semantic links + RDAP
  template verdict (the degraded-path wording)          agent (floor rule, daily cap, slot reserved first)
  assessment payload stage="rules"                      tier  > stage-1 tier: new upgrade alert
  policy.decide, persist alert, hooks (once)            tier == stage-1 tier: REFINE IN PLACE
  event_jobs row (payload.fast.assessment)              tier  <  stage-1 tier, agent failed, cap reached,
                                                         queue refused: the rules alert stays
```

- Escalation (tier >= caution, triage >= 0.5, classifier hit) is decided by stage 1 as before, but the agent
  and the RDAP lookups are skipped on the request path. The job exists even when stage 2 is otherwise off
  (no semantic memory, `AGENT_REVIEW=escalated`), and the sweeper runs whenever fast-first is on.
- Refinement (`review.refine_alert`) rewrites `headline`, `next_step`, `why`, `signals`, `used_tools`,
  `raised_by` of the stage-1 assessment and marks `stage="agent"`. Same alert id, `created_at`, tier and
  `user_label`. No new row, no `policy.decide`, no hooks, so refinement never re-notifies or spends a cap.
  It locks the assessment and alert rows (`FOR UPDATE`), only runs while `stage == "rules"` (idempotent, a
  duplicate or older run cannot overwrite) and does not touch the alert row's label column.
- Stage 2 ALWAYS re-evaluates the rules with the network data stage 1 skipped (RDAP age, lookalike, with
  the usual short timeouts and local fallback) and the richer history, whether or not the agent runs. The
  agent cap, a missing key, an LLM outage or a refused slot only gate the LLM call, never this recomputation,
  so an attacker cannot lower the floor by exhausting the agent budget. If the recomputed floor is higher
  than the stage-1 tier it is raised through the upgrade path (`review_upgrade`, `policy.decide`, hooks
  only if shown and interrupting); nothing is ever lowered. A job the in-memory queue refused stays
  `queued` and gets the same recomputation when the sweeper submits it (the window is up to
  `REVIEW_SWEEP_MINUTES`). Tests: `test_fast_first.py::test_rdap_floor_is_recomputed_when_agent_cannot_run`
  (cap exhausted, LLM down, agent answering lower, queue full: final tier equals the synchronous floor).
- An agent that lowered its tier below the floor, or whose wording was replaced because the floor raised
  the tier, is not used for refinement (its text would understate the risk): the template text stays.
- A higher agent tier (e.g. a tool-confirmed raise) takes the upgrade path above: a new alert, reason
  `review_upgrade`, pushed only if it interrupts. A user can therefore get a `high` push first and a
  `critical` push seconds later for the same event; that is the price of not waiting.
- `stage` is internal (assessment payload), not part of the API. `FAST_FIRST=false` restores the synchronous
  agent in the request.

## 5. Linked history for the agent

`history_for_agent` passes entity-linked history plus linked events (including semantic neighbours) with
their real event ids, so `why` items can cite them. History text is attacker-controlled: it goes to the
model only inside the nonce-delimited untrusted block, with delimiter-like runs neutralised; event ids are
passed verbatim only when they are plain ids (redaction would have mangled hex digit runs). Tool argument
allowlisting is unchanged: history text never widens the allowed tool targets.

## 6. Settings

| setting | default | meaning |
|---|---|---|
| `EMBEDDING_PROVIDER` | empty = auto | `token_factory` (auto with key + `EMBED_MODEL`), `local`, `fake`, `none` |
| `SEMANTIC_MEMORY` | unset = auto | `false` forces off; otherwise on only when the provider is usable |
| `LOCAL_EMBED_MODEL` | paraphrase-multilingual-MiniLM-L12-v2 | local sentence-transformers model |
| `EMBED_TIMEOUT_S` | 5 | per embedding call |
| `EMBED_DIM` | 1024 | pgvector column width |
| `EMBED_SEND_DIMENSIONS` | true | send `dimensions=EMBED_DIM`; auto-off per process if rejected |
| `SEMANTIC_THRESHOLD` | 0.25 | cosine needed for a `similar` link (tuned for the local model; re-tune for Qwen3) |
| `SEMANTIC_K` / `SEMANTIC_WINDOW_DAYS` | 5 / 14 | neighbours per event / look-back |
| `STORY_PROGRESSION` | true | story signal on/off |
| `FAST_FIRST` | true | answer escalated `/v1/events` from the rules at once; the agent refines / upgrades in stage 2 |
| `AGENT_REVIEW` | escalated | `escalated` or `all` |
| `AGENT_REVIEW_DAILY_CAP` | 200 | stage-2 agent runs per user per 24 h |
| `REVIEW_WORKERS` / `REVIEW_QUEUE_SIZE` / `REVIEW_SWEEP_MINUTES` | 2 / 256 / 5 | worker pool, in-memory bound, sweeper period and stale-`running` age |
| `REVIEW_MAX_QUEUED_PER_USER` | 20 | in-queue + running jobs per user; more stay `queued` in the DB (0 = unlimited) |

With no key and no provider configured, semantic memory is off, stage 2 does not run and behaviour is the
single-stage pipeline plus the story signal over entity links.

## 7. Privacy

- Embedding provider: receives the redacted event text only (OTPs, card / account / Aadhaar numbers masked;
  phone numbers and links are NOT masked by redaction). With `local` nothing leaves the host; with
  `token_factory` the redacted text goes to Nebius Token Factory, the same provider that already gets it for
  escalated events.
- Agent: receives the redacted current event, rule signals and up to 20 linked prior events (redacted text,
  source, time, id). With `AGENT_REVIEW=all` this happens for every event, not only escalated ones.
- `event_jobs.payload` holds the redacted event input (including the redacted sender) only until the job
  finishes; it is cleared on `done` / `failed`. Raw senders are otherwise only stored hashed.
- Logs: ids, counts, provider names and exception types only, never text.
- Retention: vectors, links and jobs live as long as the event; `DELETE /v1/me` cascades all of them.

## 8. Evaluation

See `backend/eval/report.md` (section "Memory ablation") and `backend/eval/README.md`. In short, on 12
synthetic distributed slow-burn scams the baseline catches 0, memory + progression catches 9 (tune 6/8,
holdout 3/4) with no new false interrupts; the original 123 scenarios do not regress. Synthetic data,
scripted fake agent, small holdout: optimistic and not a measure of real-world behaviour.
