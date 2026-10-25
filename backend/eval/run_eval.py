"""Sentinel evaluation harness: replays scenario timelines through the REAL pipeline and reports the
rules / agent / hybrid ablation.

    uv run python eval/run_eval.py                          # all scenarios, three modes, fake agent
    uv run python eval/run_eval.py --split tune --no-fail   # tuning loop: tune split only
    uv run python eval/run_eval.py --holdout-only           # the held-out split only
    uv run python eval/run_eval.py --llm live               # real Token Factory model (needs a key)

Every scenario runs in-process through `orchestrator.process_event` (redact -> extract -> memory ->
signals -> [agent] -> policy -> DB) for a fresh user, in a dedicated Postgres database
`<db>_eval` created from the alembic migrations and dropped afterwards. Event timestamps drive the
pipeline clock (no sleeping). Nothing touches the network: RDAP domain age and the sandboxed link
inspector are replaced by each scenario's `fixtures`, web search has no key.

`--llm fake` (default) uses ScriptedAgentLLM: a deterministic, tool-using stand-in for the model. It
calls check_url / inspect_link / check_number on the event's entities, then answers with a tier that
follows the tool-confirmed facts. It SIMULATES an agent: the numbers measure the plumbing, the
tool-confirmed raise caps and the rules floor, NOT the quality of a real model.

Exit codes: 0 ok, 1 hybrid below thresholds (recall >= 0.85 and < 1 false interrupt per user-week,
checked on the holdout split AND overall), 2 invalid scenarios.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import statistics
import sys
import time
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:  # `python eval/run_eval.py` from the backend directory
    sys.path.insert(0, str(ROOT))

import httpx  # noqa: E402
from pydantic import ValidationError  # noqa: E402
from sqlalchemy import select  # noqa: E402

from app.config import settings as default_settings  # noqa: E402
from app.llm import ChatResult, FakeLLM, ToolCall  # noqa: E402
from app.models import Alert, Assessment, User  # noqa: E402
from app.pipeline import agent, embeddings, link_client, review, tools  # noqa: E402
from app.pipeline.link_client import LinkReport  # noqa: E402
from app.pipeline.orchestrator import PipelineDeps, process_event  # noqa: E402
from app.pipeline.signals import TIER_ORDER, DomainInfo  # noqa: E402
from app.schemas import EventIn  # noqa: E402

EVAL_DIR = Path(__file__).resolve().parent
SCENARIOS = EVAL_DIR / "scenarios"
MODES = ("rules", "agent", "hybrid")
RECALL_MIN = 0.85
FALSE_INTERRUPTS_MAX = 1.0  # strictly below
ALERT_TIERS = ("high", "critical")
GEN_BEGIN, GEN_END = "<!-- BEGIN GENERATED -->", "<!-- END GENERATED -->"


def parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s)


# ---------------------------------------------------------------- scenario schema
_TOP = {"id", "label", "category", "lang", "events", "expect", "split", "source", "harm_index"}
_DOMAIN_KEYS = {"age_days": (int, type(None)), "lookalike_of": (str, type(None)), "shortener": (bool,)}


def validate_scenario(sc: Any) -> list[str]:
    """Errors for one scenario dict ([] when valid)."""
    if not isinstance(sc, dict):
        return ["scenario is not an object"]
    errs: list[str] = [f"missing field {k}" for k in sorted(_TOP - set(sc))]
    if errs:
        return errs
    if not isinstance(sc["id"], str) or not sc["id"]:
        errs.append("id must be a non-empty string")
    if sc["label"] not in ("scam", "benign"):
        errs.append(f"label must be scam|benign, got {sc['label']!r}")
    if not isinstance(sc["category"], str) or not sc["category"]:
        errs.append("category must be a non-empty string")
    if sc["lang"] not in ("en", "hi", "hinglish"):
        errs.append(f"lang must be en|hi|hinglish, got {sc['lang']!r}")
    if sc["split"] not in ("tune", "holdout"):
        errs.append(f"split must be tune|holdout, got {sc['split']!r}")
    if sc["source"] != "synthetic":
        errs.append(f"source must be 'synthetic', got {sc['source']!r}")

    events = sc["events"]
    if not isinstance(events, list) or not events:
        errs.append("events must be a non-empty list")
        events = []
    prev, ids = None, set()
    for i, raw in enumerate(events):
        try:
            e = EventIn.model_validate(raw)
        except ValidationError as ex:
            errs.append(f"event {i}: {ex.errors()[0]['loc']} {ex.errors()[0]['msg']}")
            continue
        if e.ts.tzinfo is None:
            errs.append(f"event {i}: ts needs a timezone offset")
        elif prev is not None and e.ts <= prev:
            errs.append(f"event {i}: timestamps out of order")
        prev = e.ts if e.ts.tzinfo else prev
        if e.source == "call" and e.call is None:
            errs.append(f"event {i}: call source needs call metadata")
        if e.device_event_id in ids:
            errs.append(f"event {i}: duplicate device_event_id {e.device_event_id}")
        ids.add(e.device_event_id)

    exp = sc["expect"] if isinstance(sc["expect"], dict) else {}
    if set(exp) != {"first_alert_at_index", "min_tier"}:
        errs.append("expect must have exactly first_alert_at_index and min_tier")
    first, min_tier, harm = exp.get("first_alert_at_index"), exp.get("min_tier"), sc["harm_index"]
    if sc["label"] == "scam":
        if not isinstance(harm, int) or isinstance(harm, bool) or not 0 <= harm < len(events):
            errs.append(f"harm_index must be an event index for a scam, got {harm!r}")
        if not isinstance(first, int) or isinstance(first, bool) or first < 0 or (
                isinstance(harm, int) and first > harm):
            errs.append(f"first_alert_at_index must be an int in [0, harm_index], got {first!r}")
        if min_tier not in ALERT_TIERS:
            errs.append(f"min_tier must be high|critical for a scam, got {min_tier!r}")
    elif sc["label"] == "benign":
        if harm is not None:
            errs.append("harm_index must be null for benign")
        if first is not None or min_tier is not None:
            errs.append("benign expect.first_alert_at_index / min_tier must be null")

    fx = sc.get("fixtures") or {}
    if not isinstance(fx, dict) or set(fx) - {"domain_info", "link_reports"}:
        errs.append("fixtures may only hold domain_info and link_reports")
        fx = {}
    for d, info in (fx.get("domain_info") or {}).items():
        if not isinstance(info, dict) or set(info) - set(_DOMAIN_KEYS) or not all(
                isinstance(v, _DOMAIN_KEYS[k]) for k, v in info.items()):
            errs.append(f"fixtures.domain_info[{d}] must use age_days/lookalike_of/shortener")
    for u, rep in (fx.get("link_reports") or {}).items():
        if not isinstance(rep, dict) or set(rep) - set(LinkReport.model_fields):
            errs.append(f"fixtures.link_reports[{u}] has unknown fields")
            continue
        try:
            LinkReport.model_validate({"final_url": u, **rep})
        except ValidationError:
            errs.append(f"fixtures.link_reports[{u}] is not a LinkReport")
    return errs


def validate_set(scs: list[dict]) -> list[str]:
    seen: set[str] = set()
    errs = []
    for sc in scs:
        if sc.get("id") in seen:
            errs.append(f"duplicate scenario id {sc.get('id')}")
        seen.add(sc.get("id"))
    return errs


def load_scenarios(path: Path | str) -> list[dict]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(Path(path).glob("*.json"))]


# ---------------------------------------------------------------- offline fixtures
def _host(url: str) -> str:
    try:
        return (httpx.URL(url).host or "").lower().removeprefix("www.")
    except Exception:
        return ""


def fixture_domain_info(fx: dict, domain: str) -> DomainInfo:
    """RDAP stand-in: age from the fixture (unknown when absent); lookalike / shortener are the real
    LOCAL checks unless the fixture overrides them."""
    d = (domain or "").lower()
    if not tools.valid_domain(d):
        return DomainInfo()
    info = (fx.get("domain_info") or {}).get(d) or (fx.get("domain_info") or {}).get(d.removeprefix("www.")) or {}
    return DomainInfo(age_days=info.get("age_days"),
                      lookalike_of=info["lookalike_of"] if "lookalike_of" in info else tools.lookalike_of(d),
                      shortener=info["shortener"] if "shortener" in info else d in tools.SHORTENERS)


def fixture_link_report(fx: dict, url: str) -> LinkReport:
    """Link-inspector stand-in: the fixture report for this exact URL, else for the same host, else an
    unfetched report (exactly what the real client returns when the inspector is unavailable)."""
    reps = fx.get("link_reports") or {}
    rep = reps.get(url)
    if rep is None:
        h = _host(url)
        rep = next((r for u, r in sorted(reps.items()) if h and _host(u) == h), None)
    if rep is None:
        return LinkReport(final_url=url, fetched_ok=False)
    return link_client.sanitize_report(LinkReport.model_validate({"final_url": url, **rep}), url)


@contextmanager
def offline_tools(fx: dict):
    """Route check_url (RDAP) and inspect_link to the scenario fixtures; restored afterwards."""
    orig_dom, orig_link = tools.domain_info_for, link_client.inspect_link
    tools.domain_info_for = lambda ctx, d: fixture_domain_info(fx, d)
    link_client.inspect_link = lambda url, settings=None: fixture_link_report(fx, url)
    try:
        yield
    finally:
        tools.domain_info_for, link_client.inspect_link = orig_dom, orig_link


def _offline_http(timeout: float) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(404)))


# ---------------------------------------------------------------- the scripted fake agent
_BLOCK = re.compile(r"<<<UNTRUSTED_DATA nonce=\w+>>>\n(.*)\n<<<END_UNTRUSTED_DATA", re.S)
_TOOL_BLOCK = re.compile(r"<<<UNTRUSTED_TOOL_RESULT nonce=\w+>>>\n(.*)\n<<<END_UNTRUSTED_TOOL_RESULT", re.S)
_URL = re.compile(r"https?://[^\s<>\"'`]+", re.I)
_CONTENT_RISK = {"payment_request", "credential_or_otp_ask", "remote_access_ask", "secrecy_demand",
                 "authority_impersonation", "injection_attempt", "known_bad_entity"}
_ASKS = {"payment_request", "credential_or_otp_ask"}
CLEAN_DOMAIN_DAYS = 365


class ScriptedAgentLLM(FakeLLM):
    """Deterministic stand-in for a reasonable tool-using model (see module docstring).

    Round by round it requests: check_url for each domain entity, inspect_link for each domain's URL in
    the message, check_number for each phone entity (<= 3 tools per round, the agent's own limits
    apply). Final answer, from the facts the tools confirmed (agent.confirmed_facts):
      critical  threat-feed hit | APK | credential form + new/lookalike site | <7-day domain + pay/OTP ask
      high      lookalike site | new domain or repeat unknown number while the rules see some risk
                (or the base tier if that is already critical)
      info      every link checked out (old, not a lookalike, nothing flagged) and the message has no
                content risk (no pay/OTP/remote/secrecy/authority ask): the link was the only worry
      else      the rules' base tier
    It never reads meaning from the text beyond the rule signals it is given."""

    model = "scripted-fake-agent"

    def __init__(self):
        super().__init__(script=[])

    def chat(self, messages, tools=None, timeout: float = 20, model: str | None = None) -> ChatResult:
        self.calls.append({"messages": messages, "tools": tools, "timeout": timeout, "model": model})
        m = _BLOCK.search(messages[1]["content"]) if len(messages) > 1 else None
        payload = json.loads(m.group(1)) if m else {}
        done = self._done(messages)
        rounds = sum(1 for x in messages if x.get("role") == "assistant")
        todo = [c for c in self._plan(payload) if self._key(*c) not in {self._key(n, a) for n, a, _ in done}]
        if todo and rounds < agent.MAX_ROUNDS:
            batch = todo[:agent.MAX_TOOLS_PER_ROUND]
            return ChatResult(content=None, tool_calls=[
                ToolCall(id=f"fc{rounds}_{i}", name=n, arguments=a) for i, (n, a) in enumerate(batch)])
        return ChatResult(content=json.dumps(self._answer(payload, done)))

    @staticmethod
    def _key(name: str, args: dict) -> str:
        return name + json.dumps(args, sort_keys=True)

    @staticmethod
    def _plan(payload: dict) -> list[tuple[str, dict]]:
        ents = payload.get("entities") or []
        domains = [e["canonical"] for e in ents if e.get("type") == "domain"][:3]
        phones = [e["canonical"] for e in ents if e.get("type") == "phone"][:2]
        text = (payload.get("event") or {}).get("text") or ""
        urls = [u.rstrip(".,;:!?)]}'\"") for u in _URL.findall(text)]
        plan: list[tuple[str, dict]] = [("check_url", {"domain": d}) for d in domains]
        for d in domains:
            u = next((u for u in urls if _host(u) == d), f"https://{d}/")
            plan.append(("inspect_link", {"url": u}))
        plan += [("check_number", {"e164": p}) for p in phones]
        return plan

    @staticmethod
    def _done(messages: list[dict]) -> list[tuple[str, dict, dict]]:
        calls: dict[str, tuple[str, dict]] = {}
        out = []
        for msg in messages:
            for tc in msg.get("tool_calls") or []:
                fn = tc.get("function") or {}
                calls[tc.get("id")] = (fn.get("name"), json.loads(fn.get("arguments") or "{}"))
            if msg.get("role") == "tool" and msg.get("tool_call_id") in calls:
                m = _TOOL_BLOCK.search(msg.get("content") or "")
                try:
                    res = json.loads(m.group(1)) if m else {}
                except ValueError:
                    res = {}
                name, args = calls[msg["tool_call_id"]]
                out.append((name, args, res if isinstance(res, dict) else {}))
        return out

    @staticmethod
    def _answer(payload: dict, done: list[tuple[str, dict, dict]]) -> dict:
        base = payload.get("base_tier") if payload.get("base_tier") in TIER_ORDER else "caution"
        names = {s.get("name") for s in payload.get("rule_signals") or []}
        ok = [(n, a, r) for n, a, r in done if "error" not in r]
        facts = agent.confirmed_facts([agent.ToolRecord(n, a, r) for n, a, r in ok])
        fn = {f.name for f in facts}
        risky_base = TIER_ORDER.index(base) >= TIER_ORDER.index("caution")

        def pick(*wanted):
            return [{"tool": f.tool, "fact": f.name} for f in facts if f.name in wanted]

        strong = pick("threat_feed_hit", "apk_download")
        if "credential_form" in fn and fn & {"new_domain", "lookalike"}:
            strong += pick("credential_form", "new_domain", "lookalike")
        if "new_domain_7d" in fn and names & _ASKS:
            strong += pick("new_domain_7d")
        weak = pick("lookalike") + (pick("new_domain", "repeat_number") if risky_base else [])
        checked = [r for n, _, r in ok if n == "check_url"]
        inspected = [r for n, _, r in ok if n == "inspect_link"]
        clean = (bool(checked) and all(isinstance(r.get("age_days"), int) and r["age_days"] >= CLEAN_DOMAIN_DAYS
                                       and not r.get("lookalike_of") for r in checked)
                 and not any(r.get("threat_feed_hits") or r.get("apk_download") for r in inspected))
        if strong:
            tier, raises, head = "critical", strong, "Likely scam: the link is dangerous"
        elif weak:  # confirmed risk never argues the rules' tier down
            tier, raises, head = max(("high", base), key=TIER_ORDER.index), weak, "Possible scam: suspicious website or number"
        elif clean and not names & _CONTENT_RISK and base in ("info", "caution"):
            tier, raises, head = "info", [], "The link in this message checks out"
        else:
            tier, raises, head = base, [], "Be careful with this message"
        return {"tier": tier, "headline": head,
                "next_step": "Do not pay or share an OTP; verify through the official app or helpline.",
                "why": [], "raises": raises}


# ---------------------------------------------------------------- running scenarios
# memory ablation configurations (--ablation): settings overrides + whether stage 2 runs
OFF_MEMORY = {"story_progression": False, "semantic_memory": False, "agent_review": "escalated"}
CONFIGS: dict[str, dict] = {
    # (a) before this work: no semantic memory, no progression, single stage
    "baseline_rules": {"mode": "rules", "review": "off", **OFF_MEMORY},
    "baseline_hybrid": {"mode": "hybrid", "review": "off", **OFF_MEMORY},
    # (b) memory + progression, rules only (no LLM), two-stage so semantic links are built
    "memory_rules": {"mode": "rules", "review": "two-stage", "story_progression": True, "semantic_memory": True,
                     "agent_review": "escalated"},
    # (c) full two-stage: scripted fake agent on every event in stage 2
    "two_stage_hybrid": {"mode": "hybrid", "review": "two-stage", "story_progression": True,
                         "semantic_memory": True, "agent_review": "all"},
}


def run_scenarios(scenarios: list[dict], mode: str, llm, session_factory: Callable,
                  *, settings=None, review_mode: str = "off", overrides: dict | None = None) -> list[dict]:
    """Replay each scenario for a fresh user through process_event in `mode`. Returns per-scenario
    results with one record per event. `review_mode="two-stage"` runs the stage-2 background review
    after every event (flush_background) and counts upgrade alerts at that event's index."""
    base = settings or default_settings
    upd = {"agent_mode": mode, "tavily_api_key": "", "inspector_url": "", "inspector_secret": "",
           **(overrides or {})}
    if review_mode == "off":
        upd.update(semantic_memory=False, agent_review="escalated")
    run_settings = base.model_copy(update=upd)
    saved = default_settings.agent_mode
    default_settings.agent_mode = mode  # belt and braces: the pipeline reads deps.settings
    review.configure(workers=1, queue_size=10_000, deferred=True)
    try:
        return [_run_one(sc, mode, llm, session_factory, run_settings) for sc in scenarios]
    finally:
        default_settings.agent_mode = saved
        review.flush_background()
        review.configure(workers=default_settings.review_workers, queue_size=default_settings.review_queue_size)


def _run_one(sc: dict, mode: str, llm, session_factory, run_settings) -> dict:
    fx = sc.get("fixtures") or {}
    decisions: dict[str, Any] = {}

    def hook(db, user, alert, decision=None, **kw):
        decisions[alert.event_id] = decision

    deps = PipelineDeps(settings=run_settings, domain_info=lambda ctx, d: fixture_domain_info(fx, d),
                        http_client_factory=_offline_http, hooks=[hook], session_factory=session_factory)
    out_events = []
    with session_factory() as db, offline_tools(fx):
        user = User()
        db.add(user)
        db.commit()
        for i, raw in enumerate(sc["events"]):
            ev = EventIn.model_validate(raw)
            t0 = time.perf_counter()
            res = process_event(db, user, ev, llm=llm, deps=deps)
            ms = (time.perf_counter() - t0) * 1000
            review.flush_background()  # stage 2 (no-op unless enabled) completes before the next event
            db.expire_all()
            asm = db.scalar(select(Assessment).where(Assessment.event_id == res.event_id,
                                                     Assessment.user_id == user.id)
                            .order_by(Assessment.created_at, Assessment.id).limit(1))
            reason = db.scalar(select(Alert.reason).where(Alert.assessment_id == asm.id)) if asm else None
            dec = decisions.get(res.event_id)
            shown = res.alert is not None
            payload = asm.payload if asm is not None and isinstance(asm.payload, dict) else {}
            tier = asm.tier if asm else "info"
            signals = list(asm.signals or []) if asm else []
            interrupt = bool(shown and dec is not None and dec.interrupt)
            upgraded = False
            for a_tier, a_int, a_sig in db.execute(
                    select(Assessment.tier, Alert.interrupt, Assessment.signals).join(
                        Alert, Alert.assessment_id == Assessment.id).where(
                        Assessment.event_id == res.event_id, Assessment.user_id == user.id,
                        Alert.reason == review.REVIEW_UPGRADE, Alert.shown_at.is_not(None))).all():
                upgraded, shown = True, True
                interrupt = interrupt or bool(a_int)
                if TIER_ORDER.index(a_tier) > TIER_ORDER.index(tier) or not res.alert:
                    tier, signals = max((tier, a_tier), key=TIER_ORDER.index), list(a_sig or [])
            out_events.append({
                "index": i, "ts": raw["ts"], "source": raw["source"],
                "tier": tier, "shown": shown, "upgraded": upgraded,
                "interrupt": interrupt,
                "reason": reason, "signals": signals,
                "used_tools": list(payload.get("used_tools") or []), "mode_used": payload.get("mode"),
                "raised_by": list(payload.get("raised_by") or []),
                "degraded": bool(asm.degraded) if asm else False, "latency_ms": round(ms, 2),
                "text": (raw.get("text") or (f"[call {raw['call']['duration_s']}s]" if raw.get("call") else ""))[:160],
            })
    return {"id": sc["id"], "label": sc["label"], "category": sc["category"], "split": sc["split"],
            "lang": sc.get("lang"), "shape": sc.get("shape"), "harm_index": sc.get("harm_index"),
            "first_alert_at_index": (sc.get("expect") or {}).get("first_alert_at_index"), "events": out_events}


# ---------------------------------------------------------------- metrics
def _first_alert(r: dict) -> dict | None:
    return next((e for e in r["events"] if e["shown"] and e["tier"] in ALERT_TIERS), None)


def _pct(vals: list[float], p: float) -> float | None:
    if not vals:
        return None
    s = sorted(vals)
    return s[max(0, math.ceil(p / 100 * len(s)) - 1)]


def compute_metrics(results: list[dict]) -> dict:
    scams = [r for r in results if r["label"] == "scam"]
    benign = [r for r in results if r["label"] == "benign"]
    detected, missed, hours, idxs = [], [], [], []
    per_cat: dict[str, list[bool]] = {}
    for r in scams:
        fa = _first_alert(r)
        ok = fa is not None and fa["index"] <= r["first_alert_at_index"]
        per_cat.setdefault(r["category"], []).append(ok)
        if ok:
            detected.append(r)
            hours.append((parse_ts(fa["ts"]) - parse_ts(r["events"][0]["ts"])).total_seconds() / 3600)
            idxs.append(fa["index"])
        else:
            missed.append({"id": r["id"], "category": r["category"], "split": r["split"],
                           "harm_index": r["harm_index"], "first_alert_at_index": r["first_alert_at_index"],
                           "late_alert_index": fa["index"] if fa else None,
                           "events": [{"index": e["index"], "tier": e["tier"], "shown": e["shown"],
                                       "signals": e["signals"]} for e in r["events"]]})
    interrupts, cautions, fi = [], [], []
    for r in benign:
        hits = [e for e in r["events"] if e["shown"] and e["interrupt"]]
        interrupts.append(len(hits))
        cautions.append(sum(1 for e in r["events"] if e["shown"] and e["tier"] == "caution"))
        if hits:
            fi.append({"id": r["id"], "category": r["category"], "split": r["split"], "count": len(hits),
                       "events": [{"index": e["index"], "tier": e["tier"], "signals": e["signals"],
                                   "text": e["text"]} for e in hits]})
    lat = [e["latency_ms"] for r in results for e in r["events"]]
    firsts = [fa for r in scams if (fa := _first_alert(r)) is not None and fa["index"] <= r["first_alert_at_index"]]
    return {
        "n_scam": len(scams), "n_benign": len(benign), "n_detected": len(detected),
        "scam_recall": len(detected) / len(scams) if scams else None,
        "false_interrupts_per_user_week": statistics.mean(interrupts) if benign else None,
        "benign_with_interrupt_frac": sum(1 for n in interrupts if n) / len(benign) if benign else None,
        "caution_alerts_per_user_week": statistics.mean(cautions) if benign else None,
        "median_hours_to_warning": statistics.median(hours) if hours else None,
        "median_index_to_warning": statistics.median(idxs) if idxs else None,
        "per_category_recall": {c: sum(v) / len(v) for c, v in sorted(per_cat.items())},
        # severity of the first in-time warning, and how often a tool-confirmed agent raise changed a tier
        "critical_first_alert_frac": (sum(1 for fa in firsts if fa["tier"] == "critical") / len(firsts)
                                      if firsts else None),
        "agent_raised_events": sum(1 for r in results for e in r["events"] if e.get("raised_by")),
        "latency_ms": {"n": len(lat), "p50": _pct(lat, 50), "p95": _pct(lat, 95),
                       "mean": statistics.mean(lat) if lat else None, "max": max(lat) if lat else None},
        "failures": {"missed_scams": missed, "false_interrupts": fi},
    }


def gate_failures(by_split: dict[str, dict]) -> list[str]:
    """Threshold violations for the hybrid metrics of the given splits (None = empty subset, not gated)."""
    out = []
    for split, m in by_split.items():
        rec, fi = m.get("scam_recall"), m.get("false_interrupts_per_user_week")
        if rec is not None and rec < RECALL_MIN:
            out.append(f"{split}: scam recall {rec:.3f} < {RECALL_MIN}")
        if fi is not None and fi >= FALSE_INTERRUPTS_MAX:
            out.append(f"{split}: false interrupts {fi:.3f}/user-week >= {FALSE_INTERRUPTS_MAX}")
    return out


# ---------------------------------------------------------------- reporting
def _f(v, fmt="{:.2f}") -> str:
    return "–" if v is None else fmt.format(v)


def render_tables(report: dict) -> str:
    lines = ["| mode | split | scams | recall | benign weeks | false interrupts / week | weeks with ≥1 interrupt "
             "| caution / week | median h to warning | median index | first warning critical | agent raises "
             "| latency p50 / p95 ms |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for mode, by in report["modes"].items():
        for split in ("tune", "holdout", "all"):
            m = by.get(split)
            if not m or not (m["n_scam"] or m["n_benign"]):
                continue
            lat = m["latency_ms"]
            lines.append(
                f"| {mode} | {split} | {m['n_scam']} | {m['n_detected']}/{m['n_scam']} = {_f(m['scam_recall'], '{:.3f}')} "
                f"| {m['n_benign']} | {_f(m['false_interrupts_per_user_week'], '{:.3f}')} | {_f(m['benign_with_interrupt_frac'])} "
                f"| {_f(m['caution_alerts_per_user_week'])} | {_f(m['median_hours_to_warning'], '{:.1f}')} "
                f"| {_f(m['median_index_to_warning'], '{:.1f}')} | {_f(m['critical_first_alert_frac'])} "
                f"| {m['agent_raised_events']} | {_f(lat['p50'], '{:.0f}')} / {_f(lat['p95'], '{:.0f}')} |")
    cats = sorted({c for by in report["modes"].values() for c in (by.get("all") or {}).get("per_category_recall", {})})
    if cats:
        modes = list(report["modes"])
        lines += ["", "Per-category scam recall (all splits):", "",
                  "| category | " + " | ".join(modes) + " |", "|---|" + "---|" * len(modes)]
        for c in cats:
            lines.append(f"| {c} | " + " | ".join(
                _f(report["modes"][m]["all"]["per_category_recall"].get(c)) for m in modes) + " |")
    for mode in report["modes"]:
        fails = report["modes"][mode]["all"]["failures"]
        if fails["missed_scams"] or fails["false_interrupts"]:
            lines += ["", f"Failures ({mode}):", ""]
            for f in fails["missed_scams"]:
                sig = sorted({s for e in f["events"] for s in e["signals"]})
                lines.append(f"- missed `{f['id']}` ({f['split']}): tiers "
                             f"{[e['tier'] for e in f['events']]}, signals {sig}")
            for f in fails["false_interrupts"]:
                lines.append(f"- false interrupt `{f['id']}` ({f['split']}) x{f['count']}: "
                             + "; ".join(f"#{e['index']} {e['tier']} {e['signals']}" for e in f["events"]))
    return "\n".join(lines)


def render_report_md(report: dict, existing: str | None) -> str:
    gen = (f"{GEN_BEGIN}\n_Generated by `eval/run_eval.py` ({report['llm']} LLM, review: {report.get('review', 'off')}, "
           f"{report['n_scenarios']} scenarios, splits: {report['split']})._\n\n{render_tables(report)}\n{GEN_END}")
    if existing and GEN_BEGIN in existing and GEN_END in existing:
        head, rest = existing.split(GEN_BEGIN, 1)
        return head + gen + rest.split(GEN_END, 1)[1]
    return "# Sentinel evaluation report\n\n" + gen + "\n"


def print_summary(report: dict) -> None:
    print(render_tables(report))


# ---------------------------------------------------------------- database
@contextmanager
def eval_database(suffix: str = "_eval"):
    """A dedicated `<db><suffix>` database (default `<db>_eval`) created from the migrations, dropped afterwards."""
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text
    from sqlalchemy.engine import make_url
    from sqlalchemy.orm import sessionmaker

    admin_url = make_url(default_settings.database_url)
    url = admin_url.set(database=f"{admin_url.database}{suffix}")
    admin = create_engine(admin_url, isolation_level="AUTOCOMMIT")

    def drop():
        with admin.connect() as c:
            c.execute(text(f'DROP DATABASE IF EXISTS "{url.database}" WITH (FORCE)'))

    drop()
    with admin.connect() as c:
        c.execute(text(f'CREATE DATABASE "{url.database}"'))
    cfg = Config(str(ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(ROOT / "alembic"))
    cfg.attributes["url"] = url.render_as_string(hide_password=False)
    command.upgrade(cfg, "head")
    engine = create_engine(url)
    try:
        yield sessionmaker(bind=engine, autoflush=False)
    finally:
        engine.dispose()
        drop()
        admin.dispose()


# ---------------------------------------------------------------- memory ablation
MEM_BEGIN, MEM_END = "<!-- BEGIN MEMORY ABLATION -->", "<!-- END MEMORY ABLATION -->"
DSB = "distributed_slow_burn"


def subsets(results: list[dict]) -> dict[str, dict[str, dict]]:
    """metrics[subset][split]: subset = original (the 123 pre-existing scenarios) | dsb | all."""
    out: dict[str, dict[str, dict]] = {}
    for name, pick in (("original", lambda r: r["category"] != DSB), ("dsb", lambda r: r["category"] == DSB),
                       ("all", lambda r: True)):
        rs = [r for r in results if pick(r)]
        out[name] = {sp: compute_metrics([r for r in rs if sp == "all" or r["split"] == sp])
                     for sp in ("tune", "holdout", "all")}
    return out


def render_ablation(rep: dict) -> str:
    lines = [f"_Generated by `eval/run_eval.py --ablation` (embeddings: {rep['embeddings']}, semantic threshold "
             f"{rep['semantic_threshold']}, fake scripted agent, {rep['n_scenarios']} scenarios)._", "",
             "| config | subset | split | scams | recall | benign weeks | false interrupts / week | caution / week "
             "| upgrades shown |", "|---|---|---|---|---|---|---|---|---|"]
    for cfg, by in rep["configs"].items():
        for sub in ("original", "dsb"):
            for sp in ("tune", "holdout", "all"):
                m = by["metrics"][sub][sp]
                if not (m["n_scam"] or m["n_benign"]):
                    continue
                lines.append(f"| {cfg} | {sub} | {sp} | {m['n_scam']} | {m['n_detected']}/{m['n_scam']} = "
                             f"{_f(m['scam_recall'], '{:.3f}')} | {m['n_benign']} | "
                             f"{_f(m['false_interrupts_per_user_week'], '{:.3f}')} | "
                             f"{_f(m['caution_alerts_per_user_week'])} | {by['upgrades'][sub][sp]} |")
    for cfg, by in rep["configs"].items():
        f = by["metrics"]["all"]["all"]["failures"]
        dsb_miss = [x["id"] for x in f["missed_scams"] if x["category"] == DSB]
        dsb_fi = [x["id"] for x in f["false_interrupts"] if x["category"] == DSB]
        new_fi = [x["id"] for x in f["false_interrupts"] if x["category"] != DSB]
        lines.append(f"\n- `{cfg}`: missed dsb {dsb_miss or '-'}; dsb false interrupts {dsb_fi or '-'}; "
                     f"original false interrupts {new_fi or '-'}")
    return "\n".join(lines)


def _ablation(a, scs, split, mem_overrides, emb, session_factory) -> int:
    names = [c.strip() for c in a.configs.split(",") if c.strip()]
    if not names or set(names) - set(CONFIGS):
        print(f"--configs must be a subset of {','.join(CONFIGS)}")
        return 2
    thr = mem_overrides.get("semantic_threshold", default_settings.semantic_threshold)
    rep: dict[str, Any] = {"embeddings": emb, "semantic_threshold": thr, "split": split,
                           "n_scenarios": len(scs), "configs": {}}

    def run(sf):
        for name in names:
            c = dict(CONFIGS[name])
            mode, rv = c.pop("mode"), c.pop("review")
            ov = {**mem_overrides, **c} if rv == "two-stage" else c
            t = time.monotonic()
            res = run_scenarios(scs, mode, ScriptedAgentLLM(), sf, review_mode=rv, overrides=ov)
            ups = {sub: {sp: sum(1 for r in res for e in r["events"] if e.get("upgraded")
                                 and (sub == "all" or (r["category"] == DSB) == (sub == "dsb"))
                                 and (sp == "all" or r["split"] == sp))
                         for sp in ("tune", "holdout", "all")} for sub in ("original", "dsb", "all")}
            rep["configs"][name] = {"metrics": subsets(res), "upgrades": ups, "results": res}
            print(f"[{name}] {len(res)} scenarios in {time.monotonic() - t:.1f}s")

    if session_factory is not None:
        run(session_factory)
    else:
        with eval_database("_evalmem") as sf:
            run(sf)
    table = render_ablation(rep)
    print(table)
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / "report_memory.json").write_text(json.dumps(rep, ensure_ascii=False, indent=1, default=str) + "\n",
                                                  encoding="utf-8")
    md = a.out_dir / "report.md"
    old = md.read_text(encoding="utf-8") if md.exists() else "# Sentinel evaluation report\n"
    block = f"{MEM_BEGIN}\n{table}\n{MEM_END}"
    if MEM_BEGIN in old and MEM_END in old:
        head, rest = old.split(MEM_BEGIN, 1)
        old = head + block + rest.split(MEM_END, 1)[1]
    else:
        old = old.rstrip("\n") + "\n\n" + block + "\n"
    md.write_text(old, encoding="utf-8")
    return 0


# ---------------------------------------------------------------- CLI
def main(argv: list[str] | None = None, *, session_factory: Callable | None = None) -> int:
    ap = argparse.ArgumentParser(description="Sentinel rules / agent / hybrid ablation")
    ap.add_argument("--scenarios", type=Path, default=SCENARIOS)
    ap.add_argument("--modes", default=",".join(MODES))
    ap.add_argument("--llm", choices=("fake", "live"), default="fake")
    ap.add_argument("--split", choices=("all", "tune", "holdout"), default="all")
    ap.add_argument("--holdout-only", action="store_true", help="run only the holdout split")
    ap.add_argument("--ids", default="", help="comma-separated scenario ids (debugging)")
    ap.add_argument("--out-dir", type=Path, default=EVAL_DIR)
    ap.add_argument("--no-fail", action="store_true", help="always exit 0 (exploration)")
    ap.add_argument("--review", choices=("off", "two-stage"), default="off",
                    help="off: today's single-stage pipeline; two-stage: run the background review after each event")
    ap.add_argument("--ablation", action="store_true",
                    help="memory ablation: baseline vs memory+progression (rules) vs full two-stage (fake agent)")
    ap.add_argument("--configs", default=",".join(CONFIGS), help="subset of ablation configs")
    ap.add_argument("--embeddings", "--embedding-provider", dest="embeddings",
                    choices=("local", "token_factory", "fake", "none"), default="local",
                    help="embedding provider for two-stage runs (local needs `uv sync --extra semantic`; "
                         "token_factory uses TOKEN_FACTORY_API_KEY + EMBED_MODEL from backend/.env and costs tokens)")
    ap.add_argument("--semantic-threshold", type=float, default=None)
    a = ap.parse_args(argv)

    modes = [m.strip() for m in a.modes.split(",") if m.strip()]
    if not modes or set(modes) - set(MODES):
        print(f"--modes must be a subset of {','.join(MODES)}")
        return 2
    scs = load_scenarios(a.scenarios)
    errs = [f"{sc.get('id', '?')}: {e}" for sc in scs for e in validate_scenario(sc)] + validate_set(scs)
    if not scs:
        errs.append(f"no scenarios in {a.scenarios}")
    if errs:
        print("INVALID SCENARIOS:\n  " + "\n  ".join(errs))
        return 2
    split = "holdout" if a.holdout_only else a.split
    if split != "all":
        scs = [s for s in scs if s["split"] == split]
    if a.ids:
        want = {x.strip() for x in a.ids.split(",")}
        scs = [s for s in scs if s["id"] in want]

    emb = a.embeddings
    if emb == "local" and not embeddings.available("local"):
        print("NOTE: sentence-transformers not installed (uv sync --extra semantic): falling back to the FAKE "
              "lexical embedding provider; semantic linking is NOT measured.")
        emb = "fake"
    if emb == "token_factory" and not (default_settings.token_factory_api_key and default_settings.embed_model):
        print("SKIPPED: --embedding-provider token_factory needs TOKEN_FACTORY_API_KEY and EMBED_MODEL "
              "(backend/.env); nothing was run.")
        return 0
    mem_overrides: dict[str, Any] = {"embedding_provider": "" if emb == "none" else emb}
    if emb == "none":
        mem_overrides["semantic_memory"] = False
    if a.semantic_threshold is not None:
        mem_overrides["semantic_threshold"] = a.semantic_threshold

    if a.ablation:
        return _ablation(a, scs, split, mem_overrides, emb, session_factory)

    if a.llm == "live":
        if not (default_settings.token_factory_api_key and default_settings.llm_model):
            print("SKIPPED: --llm live needs TOKEN_FACTORY_API_KEY and LLM_MODEL (backend/.env); nothing was run.")
            return 0
        from app.llm import get_llm
        make_llm = get_llm
    else:
        make_llm = ScriptedAgentLLM

    report: dict[str, Any] = {"llm": a.llm, "split": split, "review": a.review, "n_scenarios": len(scs), "modes": {}, "results": {},
                              "thresholds": {"recall_min": RECALL_MIN, "false_interrupts_max_exclusive":
                                             FALSE_INTERRUPTS_MAX}}

    def run(sf):
        for mode in modes:
            t = time.monotonic()
            res = run_scenarios(scs, mode, make_llm(), sf, review_mode=a.review,
                                overrides=mem_overrides if a.review == "two-stage" else None)
            report["results"][mode] = res
            report["modes"][mode] = {
                "tune": compute_metrics([r for r in res if r["split"] == "tune"]),
                "holdout": compute_metrics([r for r in res if r["split"] == "holdout"]),
                "all": compute_metrics(res)}
            print(f"[{mode}] {len(res)} scenarios in {time.monotonic() - t:.1f}s")

    if session_factory is not None:
        run(session_factory)
    else:
        with eval_database() as sf:
            run(sf)

    print_summary(report)
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    md = a.out_dir / "report.md"
    md.write_text(render_report_md(report, md.read_text(encoding="utf-8") if md.exists() else None),
                  encoding="utf-8")

    if "hybrid" not in report["modes"]:
        return 0
    h = report["modes"]["hybrid"]
    fails = gate_failures({"holdout": h["holdout"], "all": h["all"]})
    if fails:
        print("HYBRID BELOW THRESHOLDS:\n  " + "\n  ".join(fails))
        return 0 if a.no_fail else 1
    print("hybrid meets thresholds (recall >= 0.85, false interrupts < 1 per user-week)")
    return 0


if __name__ == "__main__":
    os.environ.setdefault("ENVIRONMENT", "dev")
    sys.exit(main())
