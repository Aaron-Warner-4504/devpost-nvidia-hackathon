"""scripts/tune_semantic_threshold.py: cache keying, selection logic, TUNE-only, no-network sweep (fake provider)."""
import copy
import importlib.util
import json
from pathlib import Path

import pytest
from sqlalchemy.orm import sessionmaker

from tests.test_eval_runner import BENIGN, SLOW, write_dir

spec = importlib.util.spec_from_file_location(
    "tune_semantic_threshold", Path(__file__).resolve().parent.parent / "scripts" / "tune_semantic_threshold.py")
tune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tune)


@pytest.fixture
def sf(test_engine, db):
    return sessionmaker(bind=test_engine, autoflush=False)


def row(t, rec, ben, n=8):
    return {"threshold": t, "dsb_scams": n, "dsb_detected": int(rec * n), "dsb_recall": rec,
            "benign_upgrades": ben, "benign_dsb_upgrades": ben}


def test_cache_key_depends_on_model_and_text():
    k = tune.cache_key("m1", "hello")
    assert k == tune.cache_key("m1", "hello") and len(k) == 64
    assert k != tune.cache_key("m2", "hello") and k != tune.cache_key("m1", "hello!")
    assert tune.cache_key("ab", "c") != tune.cache_key("a", "bc")  # separator, no ambiguity


def test_vector_cache_roundtrip_and_torn_line(tmp_path):
    c = tune.VectorCache(tmp_path, "tf:Qwen/Qwen3-Embedding-8B")
    assert c.get("x") is None
    c.put_many([("x", [0.5, 0.25])])
    with c.path.open("a") as f:
        f.write('{"k": "tor')  # torn write
    c2 = tune.VectorCache(tmp_path, "tf:Qwen/Qwen3-Embedding-8B")
    assert c2.get("x") == [0.5, 0.25]
    assert tune.VectorCache(tmp_path, "other").get("x") is None
    assert "/" not in c.path.name


def test_select_prefers_zero_benign_then_recall_then_plateau_middle():
    rows = [row(0.10, 1.0, 3), row(0.125, 1.0, 1), row(0.15, 0.75, 0), row(0.175, 0.875, 0), row(0.20, 0.875, 0),
            row(0.225, 0.875, 0), row(0.25, 0.875, 0), row(0.275, 0.875, 0), row(0.30, 0.5, 0)]
    r = tune.select_threshold(rows)
    assert r["recall"] == 0.875 and r["plateau"] == [0.175, 0.275] and r["threshold"] == 0.225
    assert r["margin_steps"] == 2


def test_select_none_when_every_threshold_has_benign_upgrades():
    r = tune.select_threshold([row(0.1, 1.0, 2), row(0.2, 0.5, 1)])
    assert r["threshold"] is None and "SEMANTIC_MEMORY=false" in r["reason"]


def test_estimate_tokens():
    assert tune.estimate_tokens(["abc", "abcd"]) == 1 + 2


def test_thresholds_grid():
    assert tune.THRESHOLDS[0] == 0.1 and tune.THRESHOLDS[-1] == 0.6 and len(tune.THRESHOLDS) == 21


def test_holdout_scenarios_never_loaded(tmp_path):
    hold = copy.deepcopy(SLOW)
    hold.update(id="fx-hold", split="holdout")
    for e in hold["events"]:
        e["device_event_id"] = "h-" + e["device_event_id"]
    d = write_dir(tmp_path, [SLOW, BENIGN, hold])
    scs = tune.tune_scenarios(d)
    assert {s["id"] for s in scs} == {"fx-scam-slow", "fx-benign"}
    with pytest.raises(SystemExit):
        tune.main(["--split", "holdout"])  # no such flag


def test_fake_provider_end_to_end_writes_small_report(sf, tmp_path):
    dsb = copy.deepcopy(SLOW)
    dsb.update(id="fx-dsb", category="distributed_slow_burn", shape="distributed")
    for e in dsb["events"]:
        e["device_event_id"] = "d-" + e["device_event_id"]
    d = write_dir(tmp_path, [dsb, BENIGN])
    out = tmp_path / "out"
    rc = tune.main(["--embedding-provider", "fake", "--scenarios", str(d), "--out-dir", str(out)], session_factory=sf)
    assert rc == 0
    rep = json.loads((out / "threshold_fake-384.json").read_text())
    assert rep["split"] == "tune" and len(rep["rows"]) == 21 and rep["n_scenarios"] == 2
    assert (out / "threshold_fake-384.json").stat().st_size < 20_000


def test_token_factory_requires_yes_and_uses_cache(sf, tmp_path):
    d = write_dir(tmp_path, [SLOW, BENIGN])
    cache = tmp_path / "cache"
    calls = []

    class Real:
        def embed(self, texts):
            calls.append(list(texts))
            return tune.FakeEmbeddings(32).embed(texts)

    args = ["--embedding-provider", "token_factory", "--model", "Qwen/Qwen3-Embedding-8B", "--scenarios", str(d),
            "--cache-dir", str(cache), "--out-dir", str(tmp_path / "o")]
    assert tune.main(args, session_factory=sf, real_provider=Real) == 3 and calls == []  # no --yes: nothing sent
    assert tune.main(args + ["--yes"], session_factory=sf, real_provider=Real) == 0
    n = sum(len(c) for c in calls)
    assert n > 0 and (tmp_path / "o" / "threshold_tf_Qwen_Qwen3-Embedding-8B.json").exists()
    assert tune.main(args, session_factory=sf, real_provider=Real) == 0  # rerun: fully cached, free, no --yes
    assert sum(len(c) for c in calls) == n


def test_run_eval_embedding_provider_option_skips_without_key(sf, tmp_path, monkeypatch, capsys):
    from eval import run_eval as ev
    monkeypatch.setattr(ev.default_settings, "token_factory_api_key", "")
    d = write_dir(tmp_path, [SLOW])
    assert ev.main(["--scenarios", str(d), "--review", "two-stage", "--embedding-provider", "token_factory",
                    "--out-dir", str(tmp_path / "o")], session_factory=sf) == 0
    assert "SKIPPED" in capsys.readouterr().out
