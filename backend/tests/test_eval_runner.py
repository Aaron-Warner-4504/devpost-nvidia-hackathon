"""Task 12: evaluation harness (eval/run_eval.py) and scenario set (eval/scenarios, eval/generate_scenarios.py).

Small fixture scenarios run through the REAL pipeline (process_event, Postgres test DB) with the
scripted fake agent; the full 120-scenario run is a script, not part of the suite (one smoke test
runs 6 committed scenarios)."""
import copy
import json
from collections import Counter
from pathlib import Path

import pytest
from sqlalchemy.orm import sessionmaker

from eval import generate_scenarios as gen
from eval import run_eval as ev

SCEN_DIR = Path(__file__).resolve().parent.parent / "eval" / "scenarios"


def _e(i, sid, source, ts, text=None, sender=None, call=None, triage=0.3, lang="en"):
    return {"device_event_id": f"{sid}-e{i}", "source": source, "ts": ts, "sender": sender, "text": text,
            "call": call, "triage_score": triage, "lang": lang if text else None}


SLOW = {
    "id": "fx-scam-slow", "label": "scam", "category": "courier_customs", "lang": "en", "shape": "slow_burn",
    "harm_index": 2, "split": "tune", "source": "synthetic",
    "expect": {"first_alert_at_index": 2, "min_tier": "high"},
    "fixtures": {"domain_info": {}, "link_reports": {}},
    "events": [
        _e(0, "fx-scam-slow", "sms", "2026-03-01T09:00:00+05:30",
           "Your BlueDart parcel is held at the depot due to an incomplete address. Call 9876543210 to reschedule.",
           sender="+917700112233"),
        _e(1, "fx-scam-slow", "call", "2026-03-02T09:00:00+05:30",
           call={"number": "+91 98765 43210", "duration_s": 240, "is_contact": False, "direction": "in"}),
        _e(2, "fx-scam-slow", "whatsapp", "2026-03-03T09:00:00+05:30",
           "This is a customs officer. Your parcel is seized with illegal items. Pay Rs 25,000 penalty "
           "immediately to customs.clear@ybl or face arrest. Do not tell anyone.", sender="+919876543210",
           triage=0.9),
    ],
}
SINGLE = {
    "id": "fx-scam-single", "label": "scam", "category": "kyc_bank", "lang": "en", "shape": "single",
    "harm_index": 0, "split": "holdout", "source": "synthetic",
    "expect": {"first_alert_at_index": 0, "min_tier": "high"},
    "fixtures": {"domain_info": {"hdfc-kyc-update.xyz": {"age_days": 2}},
                 "link_reports": {"https://hdfc-kyc-update.xyz/": {
                     "final_url": "https://hdfc-kyc-update.xyz/", "fetched_ok": True,
                     "has_password_field": True, "has_otp_or_upi_or_card_field": True}}},
    "events": [_e(0, "fx-scam-single", "sms", "2026-03-05T10:00:00+05:30",
                  "Dear customer your HDFC KYC is pending, update at https://hdfc-kyc-update.xyz/ now",
                  sender="+918800112233", triage=0.7)],
}
BENIGN = {
    "id": "fx-benign", "label": "benign", "category": "bank_otp", "lang": "en", "shape": "user_week",
    "harm_index": None, "split": "tune", "source": "synthetic",
    "expect": {"first_alert_at_index": None, "min_tier": None},
    "fixtures": {"domain_info": {}, "link_reports": {}},
    "events": [_e(i, "fx-benign", "sms", f"2026-03-0{i + 1}T10:00:00+05:30",
                  f"12345{i} is your OTP for login to HDFC Bank. Do not share OTP with anyone.", sender="VM-HDFCBK")
               for i in range(3)],
}
FIXTURES = [SLOW, SINGLE, BENIGN]


@pytest.fixture
def sf(test_engine, db):  # `db` truncates every table after the test
    return sessionmaker(bind=test_engine, autoflush=False)


def write_dir(tmp_path, scenarios, name="scen"):
    d = tmp_path / name
    d.mkdir()
    for s in scenarios:
        (d / f"{s['id']}.json").write_text(json.dumps(s, ensure_ascii=False))
    return d


# ---------------------------------------------------------------- schema validation
def test_fixture_scenarios_validate():
    for s in FIXTURES:
        assert ev.validate_scenario(s) == [], s["id"]


@pytest.mark.parametrize("mutate,needle", [
    (lambda s: s.update(label="maybe"), "label"),
    (lambda s: s.update(split="dev"), "split"),
    (lambda s: s.update(source="human"), "source"),
    (lambda s: s.update(harm_index=5), "harm_index"),
    (lambda s: s["expect"].update(first_alert_at_index=3), "first_alert_at_index"),
    (lambda s: s["expect"].update(min_tier="caution"), "min_tier"),
    (lambda s: s["events"].reverse(), "order"),
    (lambda s: s["events"][0].pop("triage_score"), "event 0"),
    (lambda s: s["events"][1].update(call=None), "call"),
    (lambda s: s.update(events=[]), "events"),
    (lambda s: s["events"][0].update(ts="2026-03-01T09:00:00"), "timezone"),
    (lambda s: s["fixtures"]["domain_info"].update({"x.com": {"age": 3}}), "domain_info"),
    (lambda s: s["fixtures"]["link_reports"].update({"https://x.com": {"bogus": 1}}), "link_reports"),
    (lambda s: s["events"][1].update(device_event_id="fx-scam-slow-e0"), "device_event_id"),
])
def test_validator_rejects_bad_scenarios(mutate, needle):
    s = copy.deepcopy(SLOW)
    mutate(s)
    errs = ev.validate_scenario(s)
    assert errs and any(needle in e for e in errs), errs


def test_benign_must_not_have_harm_index():
    s = copy.deepcopy(BENIGN)
    s["harm_index"] = 0
    assert any("harm_index" in e for e in ev.validate_scenario(s))


def test_all_committed_scenarios_validate_and_set_properties():
    scs = ev.load_scenarios(SCEN_DIR)
    assert len(scs) == 120
    for s in scs:
        assert ev.validate_scenario(s) == [], s["id"]
    assert ev.validate_set(scs) == []
    labels = Counter(s["label"] for s in scs)
    assert labels == {"scam": 40, "benign": 80}
    assert len({s["id"] for s in scs}) == 120
    for label, n in labels.items():
        hold = sum(1 for s in scs if s["label"] == label and s["split"] == "holdout")
        assert 0.25 <= hold / n <= 0.35, (label, hold)
    assert {s["category"] for s in scs if s["label"] == "scam"} == set(gen.SCAM_COUNTS)
    assert all(s["source"] == "synthetic" for s in scs)
    assert {s["lang"] for s in scs} == {"en", "hi", "hinglish"}
    shapes = Counter(s["shape"] for s in scs if s["label"] == "scam")
    assert shapes["single"] >= 8 and shapes["slow_burn"] >= 20
    for s in scs:  # benign scenarios are one user-week each
        if s["label"] == "benign":
            assert len(s["events"]) >= 3
            span = ev.parse_ts(s["events"][-1]["ts"]) - ev.parse_ts(s["events"][0]["ts"])
            assert span.days <= 7


def test_validate_set_catches_duplicate_ids():
    assert any("duplicate" in e for e in ev.validate_set([SLOW, copy.deepcopy(SLOW)]))


# ---------------------------------------------------------------- generator
def test_generator_is_deterministic_and_matches_committed_files(tmp_path):
    a, b = tmp_path / "a", tmp_path / "b"
    gen.write(a)
    gen.write(b)
    fa = sorted(p.name for p in a.glob("*.json"))
    assert fa == sorted(p.name for p in b.glob("*.json")) and len(fa) == 120
    for name in fa:
        assert (a / name).read_bytes() == (b / name).read_bytes()
        assert (a / name).read_bytes() == (SCEN_DIR / name).read_bytes(), f"{name}: regenerate eval/scenarios"


def test_device_triage_is_label_blind():
    e = {"source": "sms", "text": "Your parcel is out for delivery https://amazon.in/t/1", "call": None}
    assert gen.device_triage(e) == pytest.approx(0.4)
    assert gen.device_triage({"source": "sms", "text": "hello", "call": None}) == pytest.approx(0.1)


# ---------------------------------------------------------------- metrics (pure)
def _r(sid, label, split, events, *, cat="c", harm=None, first=None):
    return {"id": sid, "label": label, "category": cat, "split": split, "harm_index": harm,
            "first_alert_at_index": first, "events": events}


def _ev(i, hours, tier="info", shown=False, interrupt=False, ms=10.0, signals=()):
    return {"index": i, "ts": f"2026-03-01T{hours:02d}:00:00+00:00", "tier": tier, "shown": shown,
            "interrupt": interrupt, "latency_ms": ms, "signals": list(signals), "text": "x"}


def test_compute_metrics_exact_values():
    results = [
        _r("s1", "scam", "tune", [_ev(0, 0), _ev(1, 2, "caution", True), _ev(2, 6, "high", True, True)],
           cat="a", harm=2, first=2),  # detected at index 2, 6h
        _r("s2", "scam", "tune", [_ev(0, 0, "critical", True, True)], cat="a", harm=0, first=0),  # 0h
        _r("s3", "scam", "holdout", [_ev(0, 0, "caution", True), _ev(1, 1), _ev(2, 3, "high", True, True)],
           cat="b", harm=1, first=1),  # alert too late -> missed
        _r("b1", "benign", "tune", [_ev(0, 0, "high", True, True), _ev(1, 1, "critical", True, True),
                                    _ev(2, 2, "caution", True)]),
        _r("b2", "benign", "tune", [_ev(0, 0), _ev(1, 1, "high", True, False)]),  # shown but capped: no interrupt
        _r("b3", "benign", "holdout", [_ev(0, 0, ms=30.0)]),
    ]
    m = ev.compute_metrics(results)
    assert m["n_scam"] == 3 and m["n_benign"] == 3
    assert m["scam_recall"] == pytest.approx(2 / 3)
    assert m["false_interrupts_per_user_week"] == pytest.approx(2 / 3)
    assert m["benign_with_interrupt_frac"] == pytest.approx(1 / 3)
    assert m["caution_alerts_per_user_week"] == pytest.approx(1 / 3)
    assert m["median_hours_to_warning"] == pytest.approx(3.0)
    assert m["median_index_to_warning"] == pytest.approx(1.0)
    assert m["per_category_recall"] == {"a": 1.0, "b": 0.0}
    assert m["latency_ms"]["max"] == pytest.approx(30.0)
    assert m["latency_ms"]["p50"] == pytest.approx(10.0)
    assert [f["id"] for f in m["failures"]["missed_scams"]] == ["s3"]
    assert [f["id"] for f in m["failures"]["false_interrupts"]] == ["b1"]
    assert m["failures"]["false_interrupts"][0]["count"] == 2

    hold = ev.compute_metrics([r for r in results if r["split"] == "holdout"])
    assert hold["scam_recall"] == 0.0 and hold["false_interrupts_per_user_week"] == 0.0


def test_metrics_empty_subsets_are_none():
    m = ev.compute_metrics([])
    assert m["scam_recall"] is None and m["false_interrupts_per_user_week"] is None
    assert m["median_hours_to_warning"] is None


def test_gate():
    ok = {"scam_recall": 0.9, "false_interrupts_per_user_week": 0.5}
    assert ev.gate_failures({"holdout": ok, "all": ok}) == []
    assert ev.gate_failures({"holdout": {**ok, "scam_recall": 0.84}, "all": ok})
    assert ev.gate_failures({"holdout": ok, "all": {**ok, "false_interrupts_per_user_week": 1.0}})
    assert ev.gate_failures({"holdout": {"scam_recall": None, "false_interrupts_per_user_week": None},
                             "all": ok}) == []  # empty subsets are not gated


# ---------------------------------------------------------------- the runner on the real pipeline
def test_runner_on_fixture_scenarios_hybrid(sf):
    llm = ev.ScriptedAgentLLM()
    res = ev.run_scenarios(FIXTURES, "hybrid", llm, sf)
    by = {r["id"]: r for r in res}
    slow = by["fx-scam-slow"]["events"]
    assert slow[0]["shown"] is False and slow[2]["tier"] == "critical" and slow[2]["shown"] is True
    assert slow[2]["interrupt"] is True and "payment_request" in slow[2]["signals"]
    single = by["fx-scam-single"]["events"][0]
    assert single["shown"] and single["tier"] in ("high", "critical")
    assert "inspect_link" in single["used_tools"] and "check_url" in single["used_tools"]
    assert all(not e["shown"] for e in by["fx-benign"]["events"])
    assert all(e["latency_ms"] > 0 for r in res for e in r["events"])
    m = ev.compute_metrics(res)
    assert m["scam_recall"] == 1.0 and m["false_interrupts_per_user_week"] == 0.0
    assert m["median_index_to_warning"] == pytest.approx(1.0)  # (2 + 0) / 2


def test_rules_mode_never_calls_the_llm(sf):
    llm = ev.ScriptedAgentLLM()
    res = ev.run_scenarios(FIXTURES, "rules", llm, sf)
    assert llm.calls == []
    assert all(e["used_tools"] == [] for r in res for e in r["events"])


def test_modes_change_behaviour_link_inspection_only_with_agent(sf):
    rules = ev.run_scenarios([SINGLE], "rules", ev.ScriptedAgentLLM(), sf)[0]["events"][0]
    llm = ev.ScriptedAgentLLM()
    hybrid = ev.run_scenarios([SINGLE], "hybrid", llm, sf)[0]["events"][0]
    assert llm.calls
    assert "credential_form_on_new_domain" not in rules["signals"]
    assert "credential_form_on_new_domain" in hybrid["signals"]  # fixture link report fed back


def test_fixtures_are_offline_and_restored(sf, monkeypatch):
    from app.pipeline import link_client, tools
    orig_link, orig_dom = link_client.inspect_link, tools.domain_info_for
    ev.run_scenarios([SINGLE], "hybrid", ev.ScriptedAgentLLM(), sf)
    assert link_client.inspect_link is orig_link and tools.domain_info_for is orig_dom


def test_main_exit_codes_and_holdout_flag(sf, tmp_path):
    missed = copy.deepcopy(SINGLE)  # a "scam" with no scam content: guaranteed miss -> recall 0.5 overall
    missed.update(id="fx-miss", split="holdout", fixtures={"domain_info": {}, "link_reports": {}})
    missed["events"] = [_e(0, "fx-miss", "sms", "2026-03-06T10:00:00+05:30", "Hi, how are you?",
                           sender="Mummy")]
    good = write_dir(tmp_path, FIXTURES)
    out = tmp_path / "out"
    args = ["--scenarios", str(good), "--modes", "rules,hybrid", "--out-dir", str(out)]
    assert ev.main(args, session_factory=sf) == 0
    rep = json.loads((out / "report.json").read_text())
    assert set(rep["modes"]) == {"rules", "hybrid"}
    assert rep["modes"]["hybrid"]["holdout"]["n_scam"] == 1
    assert (out / "report.md").read_text().count("| hybrid") >= 1

    bad = write_dir(tmp_path, FIXTURES + [missed], name="bad")
    assert ev.main(["--scenarios", str(bad), "--modes", "hybrid", "--out-dir", str(out)], session_factory=sf) == 1
    assert ev.main(["--scenarios", str(bad), "--modes", "hybrid", "--out-dir", str(out), "--no-fail"],
                   session_factory=sf) == 0

    assert ev.main(["--scenarios", str(bad), "--modes", "hybrid", "--out-dir", str(out), "--holdout-only",
                    "--no-fail"], session_factory=sf) == 0
    rep = json.loads((out / "report.json").read_text())
    ids = {r["id"] for r in rep["results"]["hybrid"]}
    assert ids == {"fx-scam-single", "fx-miss"}


def test_main_rejects_invalid_scenarios(sf, tmp_path):
    bad = copy.deepcopy(SLOW)
    bad["label"] = "maybe"
    d = write_dir(tmp_path, [bad])
    assert ev.main(["--scenarios", str(d), "--modes", "rules", "--out-dir", str(tmp_path / "o")],
                   session_factory=sf) == 2


def test_live_llm_without_key_skips_cleanly(sf, tmp_path, monkeypatch, capsys):
    from app.config import settings
    monkeypatch.setattr(settings, "token_factory_api_key", "")
    d = write_dir(tmp_path, FIXTURES)
    assert ev.main(["--scenarios", str(d), "--llm", "live", "--out-dir", str(tmp_path / "o")],
                   session_factory=sf) == 0
    assert "SKIPPED" in capsys.readouterr().out


def test_smoke_six_committed_scenarios(sf):
    scs = ev.load_scenarios(SCEN_DIR)
    pick = ([s for s in scs if s["label"] == "scam"][:3] + [s for s in scs if s["label"] == "benign"][:3])
    res = ev.run_scenarios(pick, "hybrid", ev.ScriptedAgentLLM(), sf)
    assert len(res) == 6 and all(len(r["events"]) == len(s["events"]) for r, s in zip(res, pick))
    m = ev.compute_metrics(res)
    assert m["n_scam"] == 3 and m["n_benign"] == 3
