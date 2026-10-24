"""Pure metric functions of eval/classifier_eval.py (no Laya, no DB)."""
from eval import classifier_eval as ce


def E(i, label, p, gate=False, idx=0, first=0, split="tune", lang="en"):
    return {"id": i, "idx": idx, "label": label, "split": split, "lang": lang, "first": first, "gate": gate,
            "p": {"noul": p, "choice": p, "latency_ms": 10.0}}


EVENTS = [E("s1", "scam", 0.9), E("s2", "scam", 0.2, gate=True), E("s3", "scam", 0.1),
          E("b1", "benign", 0.95), E("b2", "benign", 0.1), E("b3", "benign", 0.1, gate=True),
          E("b4", "benign", 0.0)]


def test_metrics_gate_clf_union():
    m = ce.metrics(EVENTS, "noul", 0.5)
    assert m["clf"]["scam_event_recall"] == 1 / 3 and m["clf"]["benign_rate"] == 1 / 4
    assert m["gate"]["scam_event_recall"] == 1 / 3 and m["gate"]["benign_rate"] == 1 / 4
    assert m["union"]["scam_event_recall"] == 2 / 3 and m["union"]["benign_rate"] == 2 / 4
    assert m["union"]["timeline_recall"] == 2 / 3


def test_threshold_boundary_inclusive():
    assert ce.metrics(EVENTS, "noul", 0.9)["clf"]["scam_event_recall"] == 1 / 3
    assert ce.metrics(EVENTS, "noul", 0.91)["clf"]["scam_event_recall"] == 0


def test_timeline_recall_counts_only_pre_harm_events():
    ev = [E("s", "scam", 0.1, idx=0, first=1), E("s", "scam", 0.9, idx=2, first=1)]  # fires after the deadline
    assert ce.metrics(ev, "noul", 0.5)["clf"]["timeline_recall"] == 0


def test_choose_threshold_respects_budget_and_auc():
    t = ce.choose_threshold(EVENTS, "noul", budget=0.25)
    assert t is not None and ce.metrics(EVENTS, "noul", t)["clf"]["benign_rate"] <= 0.25
    assert ce.choose_threshold(EVENTS, "noul", budget=0.0) is not None  # 0.98 > 0.95 -> no benign fires
    assert 0 <= ce.auc(EVENTS, "noul") <= 1
