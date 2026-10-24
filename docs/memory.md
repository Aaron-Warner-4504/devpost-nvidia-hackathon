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
| `token_factory` | `EMBEDDING_PROVIDER=token_factory` (auto when `TOKEN_FACTORY_API_KEY` + `EMBED_MODEL` are set) | Nebius Token Factory | hosted; live embeddings NOT verified (no key in this environment) |
| `local` | `EMBEDDING_PROVIDER=local`, `uv sync --extra semantic` | stays on the host | default model `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` |
| `fake` | tests only | stays in process | hashed bag of words; lexical, not semantic |
| none | default without key / extra | nowhere | semantic memory off |

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
  models need their own threshold.

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

- Stage 1 is unchanged except one extra INSERT (`event_jobs`) in the same transaction.
- Stage 2 runs only when it can change something: semantic memory is on, or `AGENT_REVIEW=all`.
- Upgrades only: a new alert row for the same event with a higher tier, created through `policy.decide`
  (the tier increase passes dedupe; the daily cap, critical flood guard and ceiling still apply). Nothing is
  ever downgraded or retracted. `/v1/check` events are embedded and linked but never upgraded.
- Hooks are unchanged: own-device push and family pushes act only when `decision.interrupt` is true, family
  only for `critical`.
- Safety: one job per event (unique), per-event advisory lock held on a dedicated connection, at most 2
  attempts, a full queue (`REVIEW_QUEUE_SIZE`) drops the job to `failed` without blocking the request, an
  LLM or embedding failure finishes the job with no upgrade, a job whose user was deleted exits quietly,
  and a startup sweeper resubmits `queued` / `running` jobs older than `REVIEW_SWEEP_MINUTES`. Jobs are only
  created by the pipeline for the event it just stored; no request parameter can trigger one.
- Cost: with `AGENT_REVIEW=all` the agent runs on every event, at most `AGENT_REVIEW_DAILY_CAP` stage-2 runs
  per user per 24 h; beyond the cap stage 2 does embedding + rules only.
- `review.flush_background()` runs pending jobs synchronously (tests and the eval).

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
| `SEMANTIC_THRESHOLD` | 0.25 | cosine needed for a `similar` link (tuned for the local model) |
| `SEMANTIC_K` / `SEMANTIC_WINDOW_DAYS` | 5 / 14 | neighbours per event / look-back |
| `STORY_PROGRESSION` | true | story signal on/off |
| `AGENT_REVIEW` | escalated | `escalated` or `all` |
| `AGENT_REVIEW_DAILY_CAP` | 200 | stage-2 agent runs per user per 24 h |
| `REVIEW_WORKERS` / `REVIEW_QUEUE_SIZE` / `REVIEW_SWEEP_MINUTES` | 2 / 256 / 5 | worker pool, bound, sweeper age |

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
