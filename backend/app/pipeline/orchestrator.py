"""process_event: the per-event pipeline behind POST /v1/events and POST /v1/check.

    dedupe -> redact -> extract -> store (idempotent) -> link -> HistoryCtx -> signals -> rules tier
    -> [escalate: domain info -> signals again -> agent -> link reports -> signals again]
    -> policy -> persist assessment + alert (one transaction, one commit) -> alert hooks

Guarantees:
- A duplicate delivery (same device_event_id, also two concurrent requests) is not re-processed and
  produces no new alert and no hook call; the loser of a race gets duplicate=True.
- Never raises for failures in the agent, tools, LLM, RDAP, link inspector or hooks: those degrade to the
  rules-only verdict. Only database failures propagate (the transaction is rolled back, so no
  half-written assessment/alert is left behind).
- Raw message text is never stored or sent to the LLM: everything downstream sees the redacted text.
"""

from __future__ import annotations

import hashlib
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor, wait
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import urlsplit

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.config import Settings, settings as default_settings
from app.models import Alert, Assessment, Event, Override
from app.pipeline import agent, classifier as clf_mod, memory, policy, tools
from app.pipeline.extract import Entity, extract_entities
from app.pipeline.link_client import LinkReport
from app.pipeline.memory import HistoryItem
from app.pipeline.redact import redact
from app.pipeline.signals import (LINK_HARD, STORY_WINDOW, TIER_ORDER, DomainInfo, HistoryCtx, LinkedEvent,
                                  Overrides, detect_signals, max_tier, stages_from_signals, story_stages,
                                  tier_for)
from app.schemas import AlertOut, EventIn, EventResult, Tier, WhyItem

log = logging.getLogger(__name__)

HISTORY_TYPES = frozenset({"org", "claim", "phone", "domain", "upi_id"})
RELATED_TYPES = frozenset({"phone", "domain", "org", "claim"})
HISTORY_LIMIT = 20  # prior events per entity
RELATED_LIMIT = 20  # related entities per entity
AGENT_HISTORY_MAX = 20
AGENT_RELATED_MAX = 20
LINKED_MAX = 40  # linked prior events considered for story progression
MAX_DOMAIN_LOOKUPS = 3
ESCALATE_TRIAGE = 0.5

AlertHook = Callable[..., None]
# Called after an alert is SHOWN and committed as hook(db, user, alert, decision=Decision). Shown alerts
# include non-interrupting ones (caution, /v1/check results, criticals beyond the daily ceiling):
# push / family notifiers (Tasks 11/17) MUST only notify when `decision.interrupt` is True.
# Exceptions are logged and swallowed.
alert_hooks: list[AlertHook] = []


def register_alert_hook(fn: AlertHook) -> AlertHook:
    if fn not in alert_hooks:
        alert_hooks.append(fn)
    return fn


@dataclass
class PipelineDeps:
    """Injectable side-effecting dependencies (overridden in tests)."""
    settings: Settings = field(default_factory=lambda: default_settings)
    domain_info: Callable[[tools.ToolContext, str], DomainInfo] = tools.domain_info_for
    http_client_factory: Callable | None = None
    hooks: list[AlertHook] | None = None  # None -> the module registry `alert_hooks`
    domain_timeout: float = 3.0  # wall clock for all RDAP lookups of one event
    classifier: clf_mod.Classifier | None = None  # None -> clf_mod.get_classifier(settings)


# ---------------------------------------------------------------- helpers
def _utc(d: datetime) -> datetime:
    return (d if d.tzinfo else d.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)


def _rank(t: str) -> int:
    return TIER_ORDER.index(t) if t in TIER_ORDER else 0


_HEADER_PREFIX = re.compile(r"^[A-Z]{2}-(?=[A-Z0-9]{3,})")


def sender_key_for(sender: str | None, call=None) -> str | None:
    """Opaque sender identity: the originating phone (E.164) if any, else the normalised SMS header /
    sender string (operator prefix "VM-"/"AD-" dropped). Hashed; raw senders are not stored."""
    try:
        phones = [e.canonical for e in extract_entities(None, sender, call) if e.type == "phone"]
    except Exception:
        phones = []
    if phones:
        raw = "phone:" + phones[0]
    elif sender and sender.strip():
        raw = "s:" + _HEADER_PREFIX.sub("", " ".join(sender.split()).upper())
    else:
        return None
    return hashlib.sha256(raw.encode()).hexdigest()


def load_overrides(db: Session, user) -> Overrides:
    rows = db.execute(select(Override.kind, Override.type, Override.canonical)
                      .where(Override.user_id == user.id)).all()
    return Overrides(safe=frozenset(Entity(t, c) for k, t, c in rows if k == "safe"),
                     known_bad=frozenset(Entity(t, c) for k, t, c in rows if k == "known_bad"))


def build_history_ctx(db: Session, user, *, event_id: str, now: datetime, entities: list[Entity],
                      sender_key: str | None = None, origin: list[Entity] | tuple = (),
                      semantic: bool = False, progression: bool = True) -> HistoryCtx:
    """Pure-data context for signals.py: PRIOR events (ts <= now, current event excluded) per entity,
    related entities per entity (time-filtered edges), whether the sender was seen before
    (`origin` = the originator's phone entities; `sender_key` = hashed sender string), and the linked
    prior events of the last 7 days (entity-linked, plus `similar`-edge neighbours when `semantic`) with
    their stored signal names and story stages."""
    now = _utc(now)
    by_entity: dict[Entity, list[HistoryItem]] = {}
    related: dict[Entity, list[Entity]] = {}
    for e in sorted(set(entities), key=lambda x: (x.type, x.canonical)):
        if e.type in HISTORY_TYPES:
            items = memory.history(db, user, e, now, exclude_event_id=event_id, limit=HISTORY_LIMIT)
            if items:
                by_entity[e] = items
        if e.type in RELATED_TYPES:
            rel = [r for r in memory.related_entities(db, user, e, now=now, types=set(RELATED_TYPES),
                                                      limit=RELATED_LIMIT) if r != e]
            if rel:
                related[e] = rel
    seen = any(by_entity.get(o) for o in origin if o.type == "phone")
    if not seen and sender_key:
        seen = db.scalar(select(Event.id).where(
            Event.user_id == user.id, Event.sender_key == sender_key, Event.id != event_id,
            Event.ts <= now).limit(1)) is not None
    linked = _linked_events(db, user, event_id, now, by_entity, semantic) if (progression or semantic) else ()
    return HistoryCtx(now=now, events_by_entity=by_entity, related=related, sender_seen_before=seen,
                      linked_events=linked, sender_key=sender_key, progression=progression)


def _linked_events(db: Session, user, event_id: str, now: datetime, by_entity: dict[Entity, list[HistoryItem]],
                   semantic: bool) -> tuple[LinkedEvent, ...]:
    lo = now - STORY_WINDOW
    items: dict[str, tuple[HistoryItem, str]] = {}
    for hs in by_entity.values():
        for h in hs:
            if h.event_id != event_id and lo <= _utc(h.ts) <= now:
                items.setdefault(h.event_id, (h, "entity"))
    if semantic:
        for h in memory.similar_events(db, user, event_id, now=now, window=STORY_WINDOW):
            items.setdefault(h.event_id, (h, "semantic"))
    chosen = sorted(items.values(), key=lambda x: (_utc(x[0].ts), x[0].event_id))[-LINKED_MAX:]
    meta = memory.event_meta(db, user, [h.event_id for h, _ in chosen])
    out = []
    for h, via in chosen:
        m = meta.get(h.event_id)
        if m is None:
            continue
        stages = m.stages if m.stages is not None else stages_from_signals(m.signals)
        out.append(LinkedEvent(h.event_id, _utc(h.ts), h.source, m.sender_key, stages, m.signals,
                               h.redacted_text, via))
    return tuple(out)


def history_for_agent(ctx: HistoryCtx) -> list[HistoryItem]:
    """Distinct prior events the agent may cite (newest AGENT_HISTORY_MAX, oldest first)."""
    seen: dict[str, HistoryItem] = {}
    for items in ctx.events_by_entity.values():
        for h in items:
            seen.setdefault(h.event_id, h)
    return sorted(seen.values(), key=lambda h: (h.ts, h.event_id))[-AGENT_HISTORY_MAX:]


def related_for_agent(ctx: HistoryCtx) -> list[Entity]:
    out: dict[Entity, None] = {}
    for rel in ctx.related.values():
        for e in rel:
            out.setdefault(e, None)
    return list(out)[:AGENT_RELATED_MAX]


def _local_domain_info(d: str) -> DomainInfo:
    try:
        return DomainInfo(lookalike_of=tools.lookalike_of(d), shortener=d in tools.SHORTENERS)
    except Exception:
        return DomainInfo()


def _domain_infos(deps: PipelineDeps, tctx: tools.ToolContext, domains: list[str]) -> dict[str, DomainInfo]:
    """RDAP age + lookalike for up to MAX_DOMAIN_LOOKUPS domains in parallel, bounded by
    deps.domain_timeout overall. A failed / slow lookup falls back to the local (no network)
    lookalike / shortener check with unknown age."""
    domains = list(dict.fromkeys(domains))[:MAX_DOMAIN_LOOKUPS]
    if not domains:
        return {}
    ex = ThreadPoolExecutor(max_workers=len(domains))
    try:
        futs = {d: ex.submit(deps.domain_info, tctx, d) for d in domains}
        done, _ = wait(list(futs.values()), timeout=deps.domain_timeout)
        out: dict[str, DomainInfo] = {}
        for d, f in futs.items():
            di = None
            if f in done:
                try:
                    di = f.result()
                except Exception:
                    di = None
            out[d] = di if isinstance(di, DomainInfo) else _local_domain_info(d)
        return out
    finally:
        ex.shutdown(wait=False)  # never wait for an overrunning RDAP call


def _host_key(url: Any) -> str | None:
    try:
        h = (urlsplit(url).hostname or "").lower().rstrip(".") if isinstance(url, str) else ""
    except ValueError:
        return None
    return h.removeprefix("www.") or None


def _link_reports(verdict: agent.AgentVerdict, entities: list[Entity]) -> dict[str, LinkReport]:
    """inspect_link results from this run, keyed by the message's domain entity they were run on."""
    own = {e.canonical for e in entities if e.type == "domain"}
    out: dict[str, LinkReport] = {}
    for r in verdict.tool_results or []:
        if r.tool != "inspect_link" or not isinstance(r.result, dict):
            continue
        host = _host_key((r.arguments or {}).get("url"))
        if host not in own:
            continue
        try:
            rep = LinkReport.model_validate({k: v for k, v in r.result.items() if k in LinkReport.model_fields})
        except Exception:
            continue
        prev = out.get(host)
        if prev is None or (rep.fetched_ok and not prev.fetched_ok) or len(rep.threat_feed_hits) > len(
                prev.threat_feed_hits):
            out[host] = rep
    return out


def _settle_savepoint(db: Session, sp) -> None:
    """Keep the agent's (read-only) savepoint if the transaction is healthy, else roll back to it so a
    DB error swallowed inside a tool cannot poison the rest of the request."""
    try:
        db.execute(text("SELECT 1"))
        sp.commit()
    except Exception:
        log.warning("agent left the transaction unusable; rolled back to savepoint")
        sp.rollback()


def _why_json(why: list[WhyItem]) -> list[dict]:
    return [w.model_dump(mode="json") for w in why]


def alert_out(alert: Alert, asm: Assessment) -> AlertOut:
    """AlertOut from stored rows (GET /v1/alerts, duplicate /v1/check)."""
    p = asm.payload if isinstance(asm.payload, dict) else {}
    why = []
    for w in p.get("why") or []:
        try:
            why.append(WhyItem.model_validate(w))
        except Exception:
            continue
    tier: Tier = asm.tier if asm.tier in TIER_ORDER else "caution"  # type: ignore[assignment]
    return AlertOut(id=alert.id, event_id=asm.event_id, tier=tier,
                    headline=p.get("headline") or asm.explanation or "", why=why,
                    next_step=p.get("next_step") or "", signals=list(asm.signals or []),
                    created_at=alert.created_at)


def _stored_alert(db: Session, user, event_id: str) -> AlertOut | None:
    row = db.execute(select(Alert, Assessment).join(Assessment, Assessment.id == Alert.assessment_id)
                     .where(Alert.user_id == user.id, Assessment.user_id == user.id,
                            Assessment.event_id == event_id)
                     .order_by(Alert.created_at.desc(), Alert.id.desc()).limit(1)).first()
    return alert_out(*row) if row else None


# ---------------------------------------------------------------- main entry point
@dataclass
class _Outcome:
    alert: AlertOut | None
    decision: policy.Decision


def process_event(db: Session, user, ev: EventIn, force_agent: bool = False, *, llm=None,
                  deps: PipelineDeps | None = None) -> EventResult:
    t0 = time.monotonic()
    deps = deps or PipelineDeps()
    ts = _utc(ev.ts)
    red_text = redact(ev.text) if ev.text else None
    red_sender = redact(ev.sender) if ev.sender else None
    rev = ev.model_copy(update={"ts": ts, "text": red_text, "sender": red_sender})
    entities = extract_entities(red_text, red_sender, ev.call)
    skey = sender_key_for(red_sender, ev.call)
    try:
        stored = memory.store_event(db, user, rev, red_text or "", entities, sender_key=skey)
        if stored.duplicate:
            existing = _stored_alert(db, user, stored.id) if force_agent else None
            db.rollback()  # nothing new to keep (entity/membership upserts were no-ops)
            return EventResult(event_id=stored.id, duplicate=True, alert=existing)
        memory.link(db, user, stored, entities)
        out = _assess(db, user, rev, ev, entities, stored.id, skey, force_agent, llm, deps, t0)
        db.commit()  # the only commit: event, links, assessment and alert land together
    except Exception:
        db.rollback()
        raise
    if out.decision.show and out.alert is not None:
        _run_hooks(db, user, out.alert, out.decision, deps)
    return EventResult(event_id=stored.id, duplicate=False,
                       alert=out.alert if out.decision.show else None)


def _assess(db: Session, user, rev: EventIn, ev: EventIn, entities: list[Entity], event_id: str,
            skey: str | None, force_agent: bool, llm, deps: PipelineDeps, t0: float) -> _Outcome:
    ts = rev.ts
    overrides = load_overrides(db, user)
    origin = [e for e in extract_entities(None, rev.sender, rev.call) if e.type == "phone"]
    ctx = build_history_ctx(db, user, event_id=event_id, now=ts, entities=entities, sender_key=skey,
                            origin=origin, progression=bool(deps.settings.story_progression))
    signals = detect_signals(rev, entities, ctx, overrides=overrides)
    floor = tier_for(signals, ctx)
    triage = ev.triage_score if isinstance(ev.triage_score, (int, float)) else 0.0
    escalate = force_agent or _rank(floor) >= _rank("caution") or triage >= ESCALATE_TRIAGE
    # Optional first-pass classifier: may only ADD an escalation (never lowers floor / alerts / the gate).
    cls_info: dict | None = None
    if (not escalate and (deps.settings.classifier or "rules").strip().lower() != "rules"
            and (cls := deps.classifier or clf_mod.get_classifier(deps.settings)) is not None):
        res = clf_mod.classify_safe(cls, rev.text, ev.lang, deps.settings.classifier_timeout_ms)
        if res is not None:
            hit = res.scam_prob >= deps.settings.classifier_threshold
            escalate = escalate or hit
            cls_info = {"source": res.source, "scam_prob": round(res.scam_prob, 4),
                        "threshold": deps.settings.classifier_threshold, "escalated": hit,
                        "latency_ms": round(res.latency_ms, 1)}
            log.info("classifier %s: escalated=%s", type(cls).__name__, hit)
    tctx = tools.ToolContext(db=db, user=user, now=ts, settings=deps.settings,
                             http_client_factory=deps.http_client_factory)

    domains = sorted(e.canonical for e in entities if e.type == "domain")
    if escalate and domains:  # network lookups only on the escalated path (rules-only stays fast)
        ctx = replace(ctx, domain_info=_domain_infos(deps, tctx, domains))
        signals = detect_signals(rev, entities, ctx, overrides=overrides)
        floor = tier_for(signals, ctx)

    history = history_for_agent(ctx)
    related = related_for_agent(ctx)
    mode = (deps.settings.agent_mode or "hybrid") if escalate else "rules"
    if mode == "rules":
        verdict = agent.rules_verdict(floor, signals, history, rev)
    else:
        sp = db.begin_nested()
        try:
            verdict = agent.investigate(llm, rev, entities, signals, floor, ctx=tctx, history=history,
                                        mode=mode, related=related, event_id=event_id)
        except Exception:  # investigate never raises by contract; belt and braces
            log.exception("agent failed; using rules verdict")
            verdict = agent.rules_verdict(floor, signals, history, rev, degraded=True)
        _settle_savepoint(db, sp)

    # sandboxed link inspections the agent ran feed the rules engine (signals can raise the floor)
    try:
        reports = _link_reports(verdict, entities)
        if reports:
            info = dict(ctx.domain_info or {})
            finals = sorted({h for rep in reports.values() if (h := _host_key(rep.final_url))
                             and h not in info and tools.valid_domain(h)})
            info.update(_domain_infos(deps, tctx, finals))
            ctx = replace(ctx, link_reports=reports, domain_info=info)
            signals = detect_signals(rev, entities, ctx, overrides=overrides)
            floor = tier_for(signals, ctx)
    except Exception:
        log.exception("link report re-scoring failed; keeping earlier signals")

    names = [s.name for s in signals]
    stages = sorted(story_stages(rev, entities, names))
    tier: Tier = verdict.tier if verdict.tier in TIER_ORDER else floor
    if mode != "agent" or set(names) & (LINK_HARD | {"injection_attempt"}):
        tier = max_tier(tier, floor)
    headline, next_step, why = verdict.headline, verdict.next_step, list(verdict.why)
    if tier != verdict.tier:  # raised after the agent: its wording would understate the risk
        t = agent.rules_verdict(tier, signals, history, rev)
        headline, next_step = t.headline, t.next_step
        why = why or t.why

    model = "rules" if mode == "rules" else (getattr(llm, "model", "") or deps.settings.llm_model or "llm")
    asm = Assessment(user_id=user.id, event_id=event_id, tier=tier, signals=names, explanation=headline,
                     model=model, latency=round(time.monotonic() - t0, 4), degraded=bool(verdict.degraded),
                     payload={"headline": headline, "next_step": next_step, "why": _why_json(why),
                              "used_tools": list(verdict.used_tools), "raised_by": list(verdict.raised_by),
                              "mode": mode, "stages": stages,
                              **({"classifier": cls_info} if cls_info else {})})
    db.add(asm)
    db.flush()
    text_hash = hashlib.sha256((rev.text or "").encode()).hexdigest()
    key = policy.dedupe_key(entities, text_hash)
    decision = policy.decide(db, user, policy.AlertCandidate(event_id, tier, key, ts), always=force_agent)
    now = datetime.now(timezone.utc)
    alert = Alert(user_id=user.id, assessment_id=asm.id, shown_at=now if decision.show else None,
                  dedupe_key=key, interrupt=decision.interrupt, reason=decision.reason, created_at=now)
    db.add(alert)
    db.flush()
    out = AlertOut(id=alert.id, event_id=event_id, tier=tier, headline=headline, why=why,
                   next_step=next_step, signals=names, created_at=now)
    return _Outcome(alert=out, decision=decision)


def _run_hooks(db: Session, user, alert: AlertOut, decision: policy.Decision, deps: PipelineDeps) -> None:
    hooks = deps.hooks if deps.hooks is not None else alert_hooks
    for hook in list(hooks):
        try:
            hook(db, user, alert, decision=decision)
        except Exception:
            log.exception("alert hook %r failed", getattr(hook, "__name__", hook))
            try:
                db.rollback()
            except Exception:
                pass
