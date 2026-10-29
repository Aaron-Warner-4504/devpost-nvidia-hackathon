# Slides outline (8 slides, 16:9)

Style: white background, one accent colour (harbour ink `#0C5A73`, from `android/docs/design.md`), short text, no stock art. Tier colours from the app: sage, amber, clay, crimson. Images come from the repo only.

## 1. Title
- Text: "Sentinel" / "A guardian that remembers, and explains." / "Nebius x NVIDIA Global AI Hackathon, track Personal AI" / repo URL.
- Image: none, or `android/docs/screenshots/minimal/home-light.png` on the right.

## 2. Problem
- Headline: "Scams in India are multi-step. Each message looks harmless."
- Three-step strip: Day 1 courier SMS with link; Day 2 call from unknown number; Day 3 WhatsApp "customs officer" demands a UPI payment.
- One line: "Filters that judge one message at a time miss it."
- Do not add statistics unless you have a sourced figure and cite it on the slide.

## 3. Solution
- Headline: "An Android guardian with memory."
- Bullets: remembers numbers, links, UPI ids and claims; warns before you pay, share an OTP or install an app; shows why, with a timeline; privacy first: redacted before storage and before any model.
- Image: `android/docs/screenshots/minimal/alert-detail-critical-light.png`.

## 4. Architecture (what runs where)
- Image: `docs/submission/architecture.svg` (source `architecture.mmd`).
- Callouts, exact text:
  - "NVIDIA Nemotron chat (nvidia/nemotron-3-super-120b-a12b) on Nebius Token Factory: agent + tool calls"
  - "NVIDIA Nemotron VL (nvidia/Nemotron-3.5-Super-VL): screenshot text"
  - "Qwen/Qwen3-Embedding-8B on Token Factory: semantic memory"
  - "Tavily: web lookup tool"
  - "Supabase Postgres + pgvector: temporal memory"
  - "API on our own VM; inference on Nebius Token Factory"
- Confirm the model IDs match the deployed `.env` before recording.

## 5. Memory differentiator (the demo)
- Headline: "Caught on day 3 because of days 1 and 2."
- Left: the three events (SMS: info; call: caution; WhatsApp UPI demand: critical).
- Right: `android/docs/screenshots/minimal/alert-detail-critical-light.png` (the why timeline).
- Line: "Same story, different numbers: semantic links connect messages with no shared identifier."

## 6. Safety design
- Headline: "Scam text is hostile input."
- Bullets (each maps to code): redaction before storage and before any LLM call; untrusted data in nonce-delimited blocks; tool arguments allowlisted against entities from the event; rules set a floor tier the agent cannot lower; raise only with tool-confirmed facts; links opened only by a sandboxed inspector with an SSRF guard; rate limits and alert caps; delete-all endpoint.

## 7. Results (with caveats on the slide)
- Headline: "Memory turns slow-burn scams from missed to mostly caught (synthetic data)."
- Table 1 (memory ablation, `backend/eval/report.md`, "Memory upgrade"):

| config | slow-burn scams caught (12) | original 43 scams caught | false interrupts / user-week (80 benign weeks) |
|---|---|---|---|
| baseline (no memory) | 0/12 | 41/43 = 0.953 | 0.062 |
| + semantic links + story progression | 9/12 = 0.750 | 42/43 = 0.977 | 0.062 |

- Line 2: "Full set, 147 scenarios (55 scam, 92 benign): recall 51/55 = 0.927, 0.054 false interrupts per user-week. Rules, agent and hybrid modes tie."
- Caveat box (must be visible): "Synthetic, template-generated scenarios written by the team that tuned the rules. The agent in this eval is a scripted stand-in: it tests plumbing and safety caps, not Nemotron quality. Small holdout (18 scams). Remaining misses are Hindi (Devanagari)."
- Optional mention: severity, not detection, is what the agent changed: first-warning critical 0.55 (rules) vs 0.61 (hybrid), 5 agent raises.

## 8. Roadmap and business path
- Next (engineering): always-on capture and on-device redaction; Firebase push for family alerts; re-tune the semantic threshold for Qwen3; live-model eval; human-reviewed scenarios.
- Business path, from `docs/PLAN.md` section 3 and 9 (a plan, not validated): free manual "Check this"; paid individual/family plan with always-on monitoring and family alerts; later B2B2C with banks and telcos. 50-user private beta in India; Google Play notification-access declaration and DPDP Act notice and consent before launch.
- Do not show pricing as validated; if you show the PLAN.md price range, label it "hypothesis".
- Closing line: "Sentinel" + repo URL.

## Images used

| Slide | File |
|---|---|
| 1, 3, 5 | `android/docs/screenshots/minimal/alert-detail-critical-light.png`, `home-light.png` |
| 4 | `docs/submission/architecture.svg` |
| Optional | `android/docs/screenshots/minimal/family-invite-light.png`, `alert-detail-critical-dark.png`, `onboarding-1-welcome-light.png` |

Do not use `android/docs/screenshots/before/` (pre-redesign).
