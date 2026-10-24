# First-pass classifier gate (spike)

Status: built, evaluated zero-shot, **not recommended for adoption as-is**. Default stays `CLASSIFIER=rules`.

## What was built

- `backend/app/pipeline/classifier.py`: `Classification(scam_prob, label, source, latency_ms)`, the `Classifier`
  protocol (`classify(text, lang) -> Classification | None`), `RulesClassifier` (default, no opinion without a
  scorer; the orchestrator never consults it in rules mode), `LayaClassifier` (lazy import, loaded once under a
  lock, CPU, one "noul" yes/no question: "Is this message likely a scam, phishing, impersonation or payment/OTP
  fraud attempt?", long text clipped to first 700 + last 400 chars), `classify_safe` (hard timeout, single
  worker, skips instead of queueing, never raises), `get_classifier(settings)`.
- Orchestrator: when `CLASSIFIER != rules` and the rules/triage gate did **not** already escalate, the redacted
  text is classified; `scam_prob >= CLASSIFIER_THRESHOLD` adds an escalation (the agent runs). It can never
  lower a tier, suppress an alert or the triage gate. Timeout, error, `None` or a missing library behave exactly
  like rules mode. The probability is stored in the assessment payload (`payload.classifier`: source, scam_prob,
  threshold, escalated, latency_ms); logs name the classifier type only.
- Tests: `tests/test_classifier.py` (FakeClassifier: default off, rules mode unaffected, threshold boundary,
  error/None/timeout/missing-library identical to rules, never lowers tiers, triage gate intact),
  `tests/test_classifier_eval.py`. No network, no Laya needed.

## Enable

```bash
cd backend
uv sync --extra laya        # laya + CPU torch (optional extra; base install does not need it)
# .env
CLASSIFIER=laya             # default: rules
CLASSIFIER_THRESHOLD=0.5    # tune on real data; see results
CLASSIFIER_TIMEOUT_MS=300
LAYA_MODEL=multilingual     # multilingual | english | auto (Laya router)
```

Weights download from Hugging Face on first use (~1.5 GB for both checkpoints; set `HF_HOME` and
`HF_HUB_OFFLINE=1` after pre-fetching for an air-gapped host). Inference is in-process: message text is never
sent to a third party. The model loads in a background thread at first use; messages arriving before it is
ready are skipped (rules behaviour).

## Laya facts (measured here, 16-core CPU, shared machine)

| | english (`convaiinnovations/laya`) | multilingual (`.../laya-multilingual`) |
|---|---|---|
| licence | Apache-2.0 (HF card, PyPI) | Apache-2.0 |
| params / disk | 421M / 843 MB | 322M / 644 MB |
| context | 512 tokens | 1024 (up to 8192 via `max_len`) |
| CPU latency / message (single question) | p50 ~390 ms in the `auto` run, p95 ~1 s (includes router model swaps) | p50 160 ms, p95 230 ms, max 277 ms |

Process RSS with the multilingual model loaded: about 2.3 GB (4 GB with both loaded). With the default 300 ms
timeout only the multilingual model is usable; the English checkpoint would time out regularly. The arXiv link
given with the task (2410.19845) is a different paper (LLM-powered trust and safety in payments), not the Laya
paper; Laya's own claims (33 ms on a T4, accuracy tables) were not reproduced here.

## Results (zero-shot, 123 synthetic scenarios, 621 text events; calls without text are not classified)

Threshold chosen on **tune only** (max scam-event recall with classifier-alone benign escalation <= 15%), then
holdout reported once. Both Laya variants (`noul` probability; `choice` P(scam)) were compared on tune; `noul`
had no threshold within the benign budget, `choice` at 0.9 was selected. Model `multilingual`
(`eval/classifier_report_multilingual.md`; `auto` in `..._auto.md`, same conclusion).
"timeline" = scam timelines with at least one escalated event at or before `first_alert_at_index`.
Cells: scam-event recall / timeline recall / benign-event escalation rate.

| split | lang | scam ev / timelines / benign ev | current gate | Laya alone | gate OR Laya |
|---|---|---|---|---|---|
| tune | en | 36 / 17 / 128 | 69 / 100 / 12 | 17 / 29 / 6 | 69 / 100 / 18 |
| tune | hi | 14 / 7 / 120 | 57 / 100 / 2 | 36 / 43 / 13 | 71 / 100 / 16 |
| tune | hinglish | 13 / 5 / 116 | 54 / 100 / 10 | 38 / 60 / 17 | 69 / 100 / 22 |
| tune | all | 63 / 29 / 364 | 63 / 100 / 8 | 25 / 38 / 12 | 70 / 100 / 19 |
| holdout | en | 12 / 7 / 105 | 58 / 100 / 10 | 17 / 29 / 11 | 67 / 100 / 21 |
| holdout | hi | 3 / 3 / 59 | 67 / 67 / 20 | 33 / 33 / 7 | 67 / 67 / 25 |
| holdout | hinglish | 9 / 4 / 6 | 44 / 75 / 17 | 44 / 75 / 0 | 67 / 100 / 17 |
| holdout | all | 24 / 14 / 170 | 54 / 86 / 14 | 29 / 43 / 9 | 67 / 93 / 22 |

Event-level ROC AUC (0.5 = chance), multilingual, noul / choice: tune all 0.57 / 0.55 (en 0.58/0.56, hi
0.44/0.53, hinglish 0.76/0.64); holdout all 0.63 / 0.62 (hi and hinglish holdout have 3 and 9 scam events, so
those cells are anecdotes). Precision across thresholds sits at 12-37% against a 14% base rate. Latency above.

Reading: zero-shot Laya is only weakly better than chance at separating our scam events from benign ones, far
below the existing rules gate. Adding it to the gate buys +7 points of scam-event recall on tune (+13 on
holdout) and 0 extra timelines on tune (+1 of 14 on holdout), at the price of +11 (tune) / +8 (holdout) points
of benign escalations, i.e. more agent calls and more text leaving for the LLM. That trade is not worth it.

## Recommendation

**Don't adopt zero-shot Laya as the always-on gate; keep `CLASSIFIER=rules`.** English-only adoption is not
justified either (English AUC 0.55-0.58 on the multilingual checkpoint, 0.66-0.67 on the English one, and its
latency does not fit a 300 ms budget). The plumbing is in place, safe by construction, and costs nothing when
off, so the decision can be revisited with a fine-tuned checkpoint. Only one prompt framing (the one specified)
and two output types were tried; prompt iteration on the tune split might help but was out of scope.

Fine-tuning would need (estimate, not measured): a few thousand real, consented, labelled messages per
language (en / hi / Hinglish), balanced with hard negatives (bank OTPs and alerts, delivery links, legitimate KYC
reminders, family money requests, awareness notices that quote scam words), labelled at message level plus
timeline context, with a separate untouched real holdout. Laya's README reports a fine-tune moving its typed
decisions benchmark from 0.362 to 0.766 accuracy (vendor figure, different task, not verified).

## Known limits

- Eval is synthetic, written by the team that tuned the rules: optimistic for the rules gate, and Laya's text
  distribution mismatch (templated Hindi/Hinglish) may help or hurt it. Small samples (holdout Hindi: 3 scam
  timelines, Hinglish: 6 benign events). Event labels inherit the timeline label, so innocuous early messages of
  a scam depress event-level recall.
- Hindi: the multilingual checkpoint is the only one that reads Devanagari; Hindi AUC is unstable (0.44 tune,
  0.66 holdout). Hinglish (Latin script) looked best (0.76 / 0.72) but on 13 / 9 scam events.
- Context: 512 tokens (English) / 1024 (multilingual); longer messages are clipped to head + tail. The classifier
  sees one message, not the timeline (history is what the rules and agent add).
- The model is shared in-process: heavy RAM (~2.3 GB), one inference at a time, no GPU path tested. Timed-out
  calls keep running in the worker; further messages are skipped while it is busy.
- Latency numbers are from a shared 16-core box and were not measured under load.

## Swapping in Jev later

Jev (TypeSafe, a hosted proprietary decision model; as far as we could tell not on Nebius Token Factory; reachable
via the TypeSafe API or OpenRouter as `typesafe/jev`) was not evaluated. Its API and terms were not checked, and
text would go to a third party, which our self-hosting rule forbids for message text unless the user/legal
decides otherwise. If allowed, implement a `JevClassifier` with `classify(text, lang) -> Classification | None`
(HTTP call, `source="jev"`), add a `jev` branch in `get_classifier`, and nothing else changes: the timeout
guard, escalation-only rule, audit payload and the eval (`score_events` is the only Laya-specific part) all sit
behind the same interface. Redacted text only, still.
