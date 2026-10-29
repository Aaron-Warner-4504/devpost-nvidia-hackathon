# Devpost project text: Sentinel

Track: Personal AI (confirm the exact track name on the Devpost Rules tab). Side prize: Best Use of Tavily (confirm entry mechanics on Devpost).

## Elevator pitch (3 sentences)

Sentinel is an Android guardian for India that remembers every number, link and claim that has contacted you, and warns you before you pay, share an OTP or install an app. It catches scams that look harmless message by message, such as a courier SMS, then a call, then a UPI demand, by linking them in a temporal memory and having an NVIDIA Nemotron agent on Nebius Token Factory investigate with tools. Every alert shows the reason, a timeline of the earlier events that led to it.

## Inspiration

Fraud in India is rarely one message. A "courier" SMS arrives on Monday, an unknown number calls on Tuesday, and on Wednesday a "customs officer" on WhatsApp demands a UPI payment. Each step can pass a per-message filter. We wanted an assistant that has memory, so that the third contact is judged in the light of the first two, and that explains itself so that a worried parent can act on the warning.

## What it does

- Keeps a time-aware memory of phone numbers, domains, UPI ids, organisations and claims per user.
- Scores each event with deterministic rules that set a floor tier (info, caution, high, critical), then lets a Nemotron agent investigate escalated events and raise the tier only with tool-confirmed facts.
- Shows the alert with a "why" timeline of linked earlier events, one next step, and safe/scam feedback. Only high and critical interrupt.
- "Check this" lets you share a message or screenshot for an on-demand verdict.
- Family pairing by invite code is built; push to the family member is not live (see limitations).

## How we built it

Android app in Kotlin and Jetpack Compose talks to a FastAPI backend with Postgres and pgvector (Supabase). Each event is redacted, entities are extracted, and rules set the floor tier. Memory links events by shared entities and by embedding similarity (`Qwen/Qwen3-Embedding-8B` on Nebius Token Factory). Escalated events go to a tool-using agent on `nvidia/nemotron-3-super-120b-a12b` (Token Factory) with tools for entity history, domain age and lookalike checks, a sandboxed link inspector, and Tavily web lookup. A Nemotron vision model (`nvidia/Nemotron-3.5-Super-VL`) reads shared screenshots. An alert policy handles dedupe, caps and a flood guard.

Safety is part of the design: scam text is untrusted data in nonce-delimited blocks, tool arguments are allowlisted against entities from the event, the rules floor cannot be lowered by the model, and links are opened only by a separate sandboxed inspector with an SSRF guard. We also built an evaluation harness that replays 147 synthetic scenarios (55 scam timelines, 92 benign weeks; English, Hindi, Hinglish) through the real pipeline.

## Challenges

- Message-by-message alerts either miss slow scams or drown users in false alarms. Splitting rules (floor) from the agent (raise-only, evidence-gated) kept alerts auditable.
- Scam text is attacker-controlled input to an LLM with tools. We treated every string as untrusted and tested injection cases.
- Hindi and Hinglish: rules and multilingual embeddings behave differently across scripts. Our remaining memory misses are all Devanagari Hindi.
- Honest evaluation with no real labelled data. We tuned on one split, reported a held-out split, and documented a holdout leak we caused ourselves.

## Accomplishments

- A working end-to-end pipeline and Android app against a live server.
- Memory result on our synthetic "distributed slow burn" scams (each message harmless alone, different numbers each time): 0 of 12 caught without memory, 9 of 12 with semantic links and story progression, with no change in false interrupts on 80 original benign weeks (0.062 per user-week). Synthetic, template-generated data; see limitations.
- Overall on 147 scenarios: scam recall 51/55 = 0.927 and 0.054 false interrupts per user-week (rules, agent and hybrid modes tie; the agent in this eval is a script).
- A reproducible eval (`backend/eval/`) and a backend hardened through review rounds on redaction, injection, rate limits and SSRF, with the fixes pinned by tests.

## What we learned

Evidence-gated agent authority is easier to trust than a free-form LLM verdict. Memory matters more than model size for multi-step fraud. Explaining why (the timeline) is what makes an alert actionable. And a synthetic benchmark flatters you unless you state its limits next to the number.

## What's next

Finish always-on capture (notification and call log) and on-device redaction; wire Firebase so family members receive critical alerts; re-tune the semantic threshold for Qwen3 embeddings; run the eval with the live Nemotron agent; have humans review scenarios; a private beta in India (see `docs/PLAN.md` section 9); Google Play notification-access declaration and DPDP Act compliance before any wide release.

## Honest limitations

All eval numbers come from synthetic, template-generated scenarios written by the same team that tuned the rules; real traffic will be worse, and the held-out split is small (18 scams). The agent column in our ablation is a deterministic stand-in, so it measures plumbing and safety caps, not Nemotron's quality; a live-model run is not yet recorded. The semantic-link threshold was tuned for a local model, not Qwen3-Embedding-8B. Raw message text reaches our API over TLS and is redacted server-side before storage or any LLM call; on-device redaction is in progress. Family push is not wired, so family members are not yet notified. The sandboxed link inspector is built but not deployed on our shared VM. Always-on capture was still in progress when this was written.

## Built with

Nebius Token Factory, NVIDIA Nemotron, Qwen3-Embedding-8B, Tavily, Kotlin, Jetpack Compose, Python, FastAPI, PostgreSQL, pgvector, Supabase, SQLAlchemy, Alembic, Playwright, Docker, nginx, Android

## Tags

fraud-detection, personal-ai, ai-agents, tool-calling, memory, nemotron, nebius, token-factory, tavily, android, india, upi, privacy, prompt-injection-defence, pgvector

## Try it / links

- Repository: https://github.com/Aaron-Warner-4504/devpost-nvidia-hackathon (confirm visibility)
- Live API health: https://interns.enthral.ai/sentinel/health (the API has no public web UI)
- APK: add a link only if you decide to host one (not in the repo)
- Video: add the link after upload
