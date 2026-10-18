# Sentinel evaluation harness

Replays scam and benign message timelines through the real backend pipeline and reports the
rules / agent / hybrid ablation (`eval/report.md` is the demo slide).

```bash
cd backend
docker compose up -d db
export DATABASE_URL=postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel   # as in .env.example
uv run python eval/generate_scenarios.py            # regenerate eval/scenarios (byte-identical, seeded)
uv run python eval/run_eval.py                      # 120 scenarios x 3 modes, writes report.json/report.md
uv run python eval/run_eval.py --split tune --no-fail --out-dir /tmp/x   # tuning loop: tune split only
uv run python eval/run_eval.py --holdout-only       # held-out split only
uv run python eval/run_eval.py --llm live           # real Token Factory model (skips if no key)
uv run pytest tests/test_eval_runner.py             # runner, metrics, schema, generator determinism
```

Exit code: `0` ok, `1` hybrid below the thresholds (scam recall >= 0.85 and < 1 false interrupt per
user-week, on the holdout split AND overall), `2` invalid scenarios. `--no-fail` always exits 0.

## How a run works

- Each scenario gets a fresh user in a dedicated database `<db>_eval`, created from the alembic
  migrations at the start and dropped at the end. Events go through
  `orchestrator.process_event` exactly as `POST /v1/events` does (redact, extract, memory, signals,
  agent, policy, DB). Event timestamps are the clock; nothing sleeps.
- Mode is set per run (`settings.agent_mode` = `rules` | `agent` | `hybrid`, passed via `PipelineDeps`).
- No network. Each scenario's `fixtures` replace RDAP (`domain_info[domain].age_days`; lookalike and
  shortener use the real local checks unless overridden) and the sandboxed link inspector
  (`link_reports[url]`, matched by URL then by host; missing = an unfetched report, as when the
  inspector is down). Web search has no key and returns nothing.
- `--llm fake` (default) is `ScriptedAgentLLM`: a deterministic stand-in that calls `check_url`,
  `inspect_link` and `check_number` on the event's entities and answers with a tier that follows the
  tool-confirmed facts (see its docstring). **It simulates an agent. The agent and hybrid numbers
  measure the plumbing, the tool-confirmed raise caps and the rules floor, NOT real model quality.**
  `--llm live` uses the real model with the same offline tool fixtures.

## Metrics

- **Scam recall**: fraction of scam timelines with a SHOWN alert >= `high` at an event index <=
  `expect.first_alert_at_index` (which is <= `harm_index`, the step where the payment / credential /
  remote-access ask is made; the user would act right after it).
- **False interrupts per user-week**: shown, interrupting (`high`/`critical`, after dedupe and the daily
  cap) alerts per benign scenario (each benign scenario is one user-week), averaged. Also reported: the
  fraction of benign weeks with >= 1 interrupt and caution alerts (feed only) per week.
- **Time to warning**: median hours (and event index) from the first event of a detected scam to its
  first shown >= high alert. Plus the share of in-time warnings that are `critical`, the number of
  events whose tier a tool-confirmed agent raise changed, and `process_event` latency.
- Failures (missed scams, false interrupts) are listed with ids and the signals present.

## Scenarios: what they are and are not

- 120 scenarios in `eval/scenarios/`: 40 scam timelines in nine categories (digital arrest,
  courier/customs, KYC/bank, UPI collect, job/task, electricity bill, remote access, sextortion,
  investment group), single-message and multi-step slow-burn shapes; 80 benign user-weeks (bank OTPs
  and alerts, UPI confirmations, deliveries with links, family chat incl. money asks from contacts,
  salary/EMI, app OTPs, appointments, telecom, tax/EPFO, 2FA, legit KYC reminders, "you are now
  eligible" marketing, adversarial-benign messages full of scary words). English, Hindi (Devanagari)
  and Hinglish.
- `triage_score` comes from a small label-blind keyword/link heuristic (`device_triage`), standing in
  for the app's on-device triage; it never sees the label.
- Split: stratified by label and category, seeded, 27 + 55 `tune` / 13 + 25 `holdout`. Rules tuning
  looked at tune-split results only (`--split tune`); holdout was run once after tuning.

### Honesty statement

- **All 120 scenarios are synthetic** (`source: "synthetic"`), produced by a seeded template generator
  because no LLM key was available. They were written by the same team / agent that tuned the rules,
  with knowledge of how the rules work. **Reported numbers are therefore optimistic** and say little
  about real-world recall or false-alarm rates. Real traffic is messier: more variety in phrasing,
  typos, code-mixing, forwarded messages, images and voice.
- No human has reviewed these scenarios. The plan's "hand-review 20 scenarios" step is **left for a
  human**. Suggested 20 (across categories, languages, shapes and both splits):

  | # | id | why look at it |
  |---|---|---|
  | 1 | scam-digital-arrest-01 | English slow-burn: TRAI SMS -> call -> "digital arrest" -> transfer |
  | 2 | scam-digital-arrest-02 | Hindi CBI video-call threat with deposit ask |
  | 3 | scam-courier-customs-02 | Hindi India Post fee + link, single message |
  | 4 | scam-courier-customs-04 | Hinglish BlueDart SMS -> call -> OTP ask (holdout) |
  | 5 | scam-kyc-bank-03 | Hindi SBI KYC link (holdout) |
  | 6 | scam-kyc-bank-04 | Hinglish Paytm KYC -> call -> OTP |
  | 7 | scam-upi-collect-01 | OLX "army buyer" collect request |
  | 8 | scam-upi-collect-03 | Hinglish "wrong transfer, return it" + fake credit SMS |
  | 9 | scam-job-task-02 | Hindi hotel-rating task scam |
  | 10 | scam-job-task-03 | Hinglish task site + "recharge" ask (holdout, missed) |
  | 11 | scam-electricity-bill-03 | Hindi disconnection threat with a number |
  | 12 | scam-remote-access-02 | Hindi bank "TeamViewer QuickSupport" |
  | 13 | scam-sextortion-03 | Hindi conditional-threat extortion (holdout, missed) |
  | 14 | scam-investment-group-01 | WhatsApp stock-tips group -> app + deposit |
  | 15 | benign-family-chat-01 | contacts asking for money (en) |
  | 16 | benign-adversarial-benign-01 | scary-word notices (passport police verification, KYC reminder) |
  | 17 | benign-adversarial-benign-07 | cyber-crime awareness SMS naming AnyDesk (holdout, false interrupt) |
  | 18 | benign-delivery-updates-04 | Amazon delivery OTP + amzn.in links (holdout, false interrupt) |
  | 19 | benign-upi-confirmations-02 | Hindi PhonePe payment confirmations (holdout, false interrupt) |
  | 20 | benign-legit-kyc-reminder-02 | Hindi bank KYC reminder with "never share OTP" |

  Reviewers should check that each scam is realistic for India in 2026, that `harm_index` is the
  right step, that benign weeks look like real traffic, and that the Hindi/Hinglish reads naturally.
