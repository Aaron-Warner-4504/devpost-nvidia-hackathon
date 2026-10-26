"""Evaluate a first-pass classifier as an escalation gate over the 123 synthetic eval scenarios.

    uv sync --extra laya
    uv run python eval/classifier_eval.py --model multilingual            # Laya zero-shot, all scenarios
    uv run python eval/classifier_eval.py --model auto --out-dir /tmp/x   # Laya router (english ckpt for English)

What it does (text only; no agent, no network):
  1. rules gate per event: the real pipeline in `rules` mode (tier >= caution) OR client triage >= 0.5,
     exactly the orchestrator's escalation rule (without force_agent).
  2. classifier per text event, on the text AS THE PIPELINE SEES IT (redacted). Calls without text are
     not classified (the classifier has no input) and are excluded from event-level rates.
  3. per split (tune / holdout) and language (en / hi / hinglish / all): scam-event escalation recall,
     scam-TIMELINE recall (>= 1 escalated event with index <= expect.first_alert_at_index, i.e. in time
     for an alert before the harm step), benign event escalation rate, precision/recall at thresholds,
     latency p50/p95, and gate vs classifier vs union (gate OR classifier).
  4. the threshold is chosen on the TUNE split only (max scam-event recall with classifier-alone benign
     event escalation <= 15%) and then reported on holdout.

Variants of the same forward pass are compared on tune: `noul` (probability the yes/no question is
"yes") and `choice` (P(scam) of a two-way choice). Zero-shot only; nothing is fine-tuned.

CAVEAT: all scenarios are synthetic, written by the same team that tuned the rules; the numbers are
optimistic and small-sample (see eval/README.md). Event-level "scam" labels are the timeline's label:
early messages of a scam timeline (a plain parcel SMS) can look benign, which depresses event recall.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from eval import run_eval as ev  # noqa: E402
from app.pipeline.redact import redact  # noqa: E402

THRESHOLDS = [0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98]
BENIGN_BUDGET = 0.15
LANGS = ("en", "hi", "hinglish")
SPLITS = ("tune", "holdout")
VARIANTS = ("noul", "choice")


# ---------------------------------------------------------------- scoring
def score_events(scs: list[dict], model: str, cache_path: Path | None) -> list[dict]:
    """One record per text event: scenario meta, redacted-text probabilities (variants) and the latency
    of the production call path (single noul question). Cached per (model, redacted text)."""
    from app.pipeline.classifier import LayaClassifier

    clf = LayaClassifier(model=model)
    cache: dict = {}
    if cache_path and cache_path.exists():
        cache = json.loads(cache_path.read_text())
    rows, warmed = [], False
    for sc in scs:
        for i, raw in enumerate(sc["events"]):
            if not raw.get("text"):
                continue
            text = redact(raw["text"])
            key = model + ":" + hashlib.sha256(text.encode()).hexdigest()
            if key not in cache:
                if not warmed:  # model load is a one-off, not per-message latency
                    clf.warmup()
                    warmed = True
                t0 = time.perf_counter()
                c = clf.classify(text, raw.get("lang"))
                lat = (time.perf_counter() - t0) * 1000
                ans = clf.raw(text, raw.get("lang"), {"kind": clf.questions()["kind"]})
                cache[key] = {"noul": c.scam_prob, "choice": ans["kind"]["probabilities"]["scam"],
                              "latency_ms": lat, "routed": c.source}
            rows.append({"id": sc["id"], "idx": i, "p": cache[key]})
    if cache_path:
        cache_path.write_text(json.dumps(cache))
    return rows


def build_events(scs: list[dict], rules_results: list[dict], probs: dict) -> list[dict]:
    by_id = {r["id"]: r for r in rules_results}
    out = []
    for sc in scs:
        res = by_id[sc["id"]]
        first = (sc.get("expect") or {}).get("first_alert_at_index")
        for i, raw in enumerate(sc["events"]):
            if not raw.get("text"):
                continue
            tier = res["events"][i]["tier"]
            gate = tier in ("caution", "high", "critical") or (raw.get("triage_score") or 0) >= 0.5
            out.append({"id": sc["id"], "idx": i, "label": sc["label"], "split": sc["split"], "lang": sc["lang"],
                        "first": first, "gate": bool(gate), "tier": tier, "p": probs[(sc["id"], i)]})
    return out


# ---------------------------------------------------------------- metrics (pure functions)
def _pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    return s[max(0, math.ceil(p / 100 * len(s)) - 1)]


def _rate(n, d):
    return n / d if d else None


def metrics(events: list[dict], variant: str, thr: float) -> dict:
    """Rates for a set of text events at threshold `thr` (classifier fires when prob >= thr)."""
    scam = [e for e in events if e["label"] == "scam"]
    ben = [e for e in events if e["label"] == "benign"]
    fire = lambda e: e["p"][variant] >= thr  # noqa: E731
    union = lambda e: e["gate"] or fire(e)  # noqa: E731
    tl: dict[str, list[dict]] = {}
    for e in scam:
        tl.setdefault(e["id"], []).append(e)

    def tl_recall(pred):
        ok = sum(1 for evs in tl.values() if any(pred(e) for e in evs if e["idx"] <= evs[0]["first"]))
        return _rate(ok, len(tl))

    tp = sum(fire(e) for e in scam)
    fp = sum(fire(e) for e in ben)
    return {
        "n_scam_events": len(scam), "n_benign_events": len(ben), "n_scam_timelines": len(tl),
        "clf": {"scam_event_recall": _rate(tp, len(scam)), "timeline_recall": tl_recall(fire),
                "benign_rate": _rate(fp, len(ben)), "precision": _rate(tp, tp + fp)},
        "gate": {"scam_event_recall": _rate(sum(e["gate"] for e in scam), len(scam)),
                 "timeline_recall": tl_recall(lambda e: e["gate"]),
                 "benign_rate": _rate(sum(e["gate"] for e in ben), len(ben))},
        "union": {"scam_event_recall": _rate(sum(union(e) for e in scam), len(scam)),
                  "timeline_recall": tl_recall(union),
                  "benign_rate": _rate(sum(union(e) for e in ben), len(ben))},
    }


def choose_threshold(tune_events: list[dict], variant: str, budget: float = BENIGN_BUDGET) -> float | None:
    """On TUNE only: the threshold with max scam-event recall s.t. classifier-alone benign event
    escalation <= budget. Ties -> higher threshold (fewer escalations). None if no threshold fits."""
    best = None
    for t in THRESHOLDS:
        m = metrics(tune_events, variant, t)["clf"]
        if m["benign_rate"] is None or m["benign_rate"] > budget:
            continue
        key = (m["scam_event_recall"] or 0, m["timeline_recall"] or 0, t)
        if best is None or key > best[0]:
            best = (key, t)
    return best[1] if best else None


def auc(events: list[dict], variant: str) -> float | None:
    """Event-level ROC AUC (scam-timeline events vs benign events), threshold-free; 0.5 = chance."""
    pos = [e["p"][variant] for e in events if e["label"] == "scam"]
    neg = [e["p"][variant] for e in events if e["label"] == "benign"]
    if not pos or not neg:
        return None
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


def latency(rows: list[dict]) -> dict:
    lat = [r["p"]["latency_ms"] for r in rows]
    return {"n": len(lat), "p50": _pct(lat, 50), "p95": _pct(lat, 95), "max": max(lat) if lat else None}


# ---------------------------------------------------------------- report
def _f(v, pct=True):
    return "-" if v is None else (f"{100 * v:.0f}%" if pct else f"{v:.2f}")


def render(report: dict) -> str:
    L = []
    for model, r in report["models"].items():
        L += [f"### Laya `{model}` (variant `{r['variant']}`, threshold {r['threshold']} chosen on tune)", ""]
        L += ["| split | lang | scam events | scam timelines | benign events | gate: scam-ev / timeline / benign "
              "| classifier: scam-ev / timeline / benign | union: scam-ev / timeline / benign |",
              "|---|---|---|---|---|---|---|---|"]
        for split in SPLITS:
            for lang in (*LANGS, "all"):
                m = r["by"].get(f"{split}/{lang}")
                if not m:
                    continue
                cell = lambda k: " / ".join(_f(m[k][x]) for x in (  # noqa: E731
                    "scam_event_recall", "timeline_recall", "benign_rate"))
                L.append(f"| {split} | {lang} | {m['n_scam_events']} | {m['n_scam_timelines']} | "
                         f"{m['n_benign_events']} | {cell('gate')} | {cell('clf')} | {cell('union')} |")
        L += ["", "Variant comparison on tune (best threshold under the benign budget; '-' = none fits):", "",
              "| variant | tune AUC | threshold | scam-ev recall | timeline recall | benign rate |",
              "|---|---|---|---|---|---|"]
        for v, c in r["variants_tune"].items():
            L.append(f"| {v} | {_f(c['auc'], False)} | {c['threshold'] or '-'} | {_f(c.get('scam_event_recall'))} | "
                     f"{_f(c.get('timeline_recall'))} | {_f(c.get('benign_rate'))} |")
        L += ["", "Event-level ROC AUC (0.5 = chance), noul / choice:", "", "| split | " + " | ".join(
            ("en", "hi", "hinglish", "all")) + " |", "|---|---|---|---|---|"]
        for sp in SPLITS:
            L.append(f"| {sp} | " + " | ".join(
                " / ".join(_f(r["auc"][f"{sp}/{lg}"][v], False) for v in VARIANTS) for lg in (*LANGS, "all")) + " |")
        L += ["", "Threshold sweep, tune+holdout all languages (classifier alone; event-level "
              "precision / scam-event recall / benign rate / timeline recall):", "",
              "| threshold | split | precision | recall | benign rate | timeline recall |", "|---|---|---|---|---|---|"]
        for t, per in r["sweep"].items():
            for split in SPLITS:
                c = per[split]["clf"]
                L.append(f"| {t} | {split} | {_f(c['precision'])} | {_f(c['scam_event_recall'])} | "
                         f"{_f(c['benign_rate'])} | {_f(c['timeline_recall'])} |")
        lat = r["latency_ms"]
        L += ["", f"Latency per message (production path: single question, CPU, after warm-up, n={lat['n']}): "
                  f"p50 {lat['p50']:.0f} ms, p95 {lat['p95']:.0f} ms, max {lat['max']:.0f} ms.", ""]
    return "\n".join(L)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--model", default="multilingual", help="laya model: multilingual | english | auto")
    ap.add_argument("--scenarios", type=Path, default=ev.SCENARIOS)
    ap.add_argument("--out-dir", type=Path, default=ev.EVAL_DIR)
    ap.add_argument("--cache", type=Path, default=None, help="json cache of classifier outputs")
    ap.add_argument("--benign-budget", type=float, default=BENIGN_BUDGET)
    a = ap.parse_args(argv)

    scs = ev.load_scenarios(a.scenarios)
    errs = [e for sc in scs for e in ev.validate_scenario(sc)]
    if errs:
        print("INVALID SCENARIOS:", errs[:5])
        return 2

    # 1. rules gate (real pipeline, rules mode) in a dedicated db so concurrent eval runs don't collide
    with ev.eval_database("_clf_eval") as sf:
        rules_res = ev.run_scenarios(scs, "rules", ev.ScriptedAgentLLM(), sf)

    # 2. classifier
    t0 = time.monotonic()
    rows = score_events(scs, a.model, a.cache)
    print(f"classified {len(rows)} events in {time.monotonic() - t0:.0f}s")
    probs = {(r["id"], r["idx"]): r["p"] for r in rows}
    events = build_events(scs, rules_res, probs)

    # 3/4. threshold on tune only, per variant; report the better tune variant
    tune = [e for e in events if e["split"] == "tune"]
    variants_tune = {}
    for v in VARIANTS:
        t = choose_threshold(tune, v, a.benign_budget)
        c = metrics(tune, v, t)["clf"] if t is not None else {}
        variants_tune[v] = {"threshold": t, "auc": auc(tune, v), **c}
    fit = [v for v in VARIANTS if variants_tune[v]["threshold"] is not None]
    if not fit:
        print("no threshold meets the benign budget on tune:", variants_tune)
        return 1
    variant = max(fit, key=lambda v: (variants_tune[v]["scam_event_recall"], variants_tune[v]["timeline_recall"]))
    thr = variants_tune[variant]["threshold"]

    by = {}
    for split in SPLITS:
        for lang in (*LANGS, "all"):
            sub = [e for e in events if e["split"] == split and (lang == "all" or e["lang"] == lang)]
            if sub:
                by[f"{split}/{lang}"] = metrics(sub, variant, thr)
    sweep = {str(t): {s: metrics([e for e in events if e["split"] == s], variant, t) for s in SPLITS}
             for t in THRESHOLDS}
    report = {"model": a.model, "caveat": "synthetic data, optimistic, small sample",
              "models": {a.model: {"variant": variant, "threshold": thr, "variants_tune": variants_tune,
                                   "by": by, "auc": {f"{sp}/{lg}": {v: auc([e for e in events if e["split"] == sp and (
                                       lg == "all" or e["lang"] == lg)], v) for v in VARIANTS}
                                       for sp in SPLITS for lg in (*LANGS, "all")}, "sweep": sweep, "latency_ms": latency(rows[1:] or rows)}}}
    md = render(report)
    print(md)
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / f"classifier_report_{a.model}.json").write_text(
        json.dumps(report, indent=1) + "\n", encoding="utf-8")
    (a.out_dir / f"classifier_report_{a.model}.md").write_text(md + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    os.environ.setdefault("ENVIRONMENT", "dev")
    sys.exit(main())
