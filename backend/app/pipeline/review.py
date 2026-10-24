"""Stage-2 background review: re-assess an event after its stage-1 response, possibly UPGRADING its alert.

    stage 1 (request): process_event -> assessment + alert -> commit (one row in event_jobs, same tx)
                       -> hooks -> response;  then submit(job) to a bounded in-process worker pool
    stage 2 (worker):  advisory lock -> embed + `similar` links -> re-score with the richer history
                       -> agent (AGENT_REVIEW=all, or newly escalated; daily cap per user) -> if the tier is
                       HIGHER than any tier already assessed for the event: new assessment + alert via
                       policy.decide (reason review_upgrade) -> commit -> alert hooks

Guarantees: never downgrades or retracts an alert; one job per event (unique event_id), per-event
advisory lock, at most MAX_ATTEMPTS attempts; a full queue drops the job to `failed` without blocking
the request; LLM / embedding failures finish the job with no upgrade; a job whose user was deleted
exits quietly (rows cascade); jobs are only created by process_event for an event it just stored,
never from request parameters. Logs carry ids and exception types, never text.
"""

from __future__ import annotations

import logging
import threading
from concurrent.futures import Future, ThreadPoolExecutor, wait
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from sqlalchemy import func, select, text, update
from sqlalchemy.orm import Session

from app.models import Alert, Assessment, EventJob, User
from app.pipeline import embeddings, policy, semantic
from app.pipeline.signals import TIER_ORDER
from app.schemas import AlertOut, EventIn

log = logging.getLogger(__name__)
MAX_ATTEMPTS = 2
REVIEW_UPGRADE = "review_upgrade"


def enabled(settings) -> bool:
    """Stage 2 only exists when it can change something: semantic memory or AGENT_REVIEW=all."""
    return embeddings.semantic_enabled(settings) or (settings.agent_review or "").strip().lower() == "all"


def enqueue(db: Session, user, event_id: str, rev: EventIn, *, force: bool, settings) -> str | None:
    """Add the job row inside the caller's transaction (lands with the event). Returns the event id."""
    if not enabled(settings):
        return None
    db.add(EventJob(user_id=user.id, event_id=event_id, state="queued",
                    payload={"event": rev.model_dump(mode="json"), "force": bool(force)}))
    db.flush()
    return event_id


# ---------------------------------------------------------------- worker pool
@dataclass
class _Task:
    event_id: str
    session_factory: Callable
    llm: Any = None
    deps: Any = None


@dataclass
class Runner:
    workers: int = 2
    queue_size: int = 256
    deferred: bool = False  # tests / eval: jobs wait for flush_background()
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _pending: list = field(default_factory=list)
    _futures: set = field(default_factory=set)
    _pool: ThreadPoolExecutor | None = None

    def submit(self, task: _Task) -> bool:
        with self._lock:
            busy = len(self._pending) + len(self._futures)
            if busy >= self.queue_size:
                full = True
            else:
                full = False
                if self.deferred:
                    self._pending.append(task)
                else:
                    if self._pool is None:
                        self._pool = ThreadPoolExecutor(max_workers=max(1, self.workers),
                                                        thread_name_prefix="review")
                    fut: Future = self._pool.submit(_run_safe, task)
                    self._futures.add(fut)
                    fut.add_done_callback(self._done)
        if full:
            _mark_failed(task, "queue_full")
            return False
        return True

    def _done(self, fut) -> None:
        with self._lock:
            self._futures.discard(fut)

    def flush(self) -> None:
        """Run / wait for every pending job (deferred jobs run in the calling thread, in order)."""
        while True:
            with self._lock:
                batch, self._pending = self._pending, []
                futs = list(self._futures)
            if not batch and not futs:
                return
            for t in batch:
                _run_safe(t)
            if futs:
                wait(futs)


runner = Runner()


def configure(*, workers: int, queue_size: int, deferred: bool = False) -> Runner:
    global runner
    runner = Runner(workers=workers, queue_size=queue_size, deferred=deferred)
    return runner


def submit(event_id: str, *, session_factory: Callable, llm=None, deps=None) -> bool:
    return runner.submit(_Task(event_id, session_factory, llm, deps))


def flush_background() -> None:
    """Tests / eval: run all pending stage-2 jobs now, synchronously."""
    runner.flush()


def _mark_failed(task: _Task, reason: str) -> None:
    try:
        with task.session_factory() as db:
            db.execute(update(EventJob).where(EventJob.event_id == task.event_id, EventJob.state == "queued")
                       .values(state="failed", error=reason, payload=None, updated_at=func.now()))
            db.commit()
        log.warning("review job for event %s dropped (%s)", task.event_id, reason)
    except Exception as exc:
        log.warning("could not mark review job failed (%s)", type(exc).__name__)


def _run_safe(task: _Task) -> str:
    try:
        return run_job(task.session_factory, task.event_id, llm=task.llm, deps=task.deps)
    except Exception as exc:  # never escapes into the pool
        log.warning("review job for event %s crashed (%s)", task.event_id, type(exc).__name__)
        return "error"


# ---------------------------------------------------------------- one job
def run_job(session_factory: Callable, event_id: str, *, llm=None, deps=None) -> str:
    """Process the job of `event_id`. Returns a short status (for tests and logs)."""
    from app.pipeline.orchestrator import PipelineDeps

    deps = deps or PipelineDeps()
    with session_factory() as db:
        key = f"review:{event_id}"
        # the lock lives on a DEDICATED connection: a Session may switch pooled connections between commits
        with db.get_bind().connect() as lock_conn:
            if not lock_conn.scalar(text("SELECT pg_try_advisory_lock(hashtext(:k))"), {"k": key}):
                return "locked"
            try:
                return _locked(db, event_id, llm, deps)
            finally:
                try:
                    db.rollback()
                except Exception:
                    pass
                lock_conn.execute(text("SELECT pg_advisory_unlock(hashtext(:k))"), {"k": key})
                lock_conn.commit()


def _locked(db: Session, event_id: str, llm, deps) -> str:
    job = db.scalar(select(EventJob).where(EventJob.event_id == event_id))
    if job is None:
        return "gone"
    if job.state in ("done", "failed"):
        return job.state
    if job.attempts >= MAX_ATTEMPTS:
        job.state, job.payload, job.updated_at = "failed", None, datetime.now(timezone.utc)
        db.commit()
        return "failed"
    job.state, job.attempts, job.updated_at = "running", job.attempts + 1, datetime.now(timezone.utc)
    job_id, user_id, payload = job.id, job.user_id, dict(job.payload or {})
    db.commit()
    user = db.get(User, user_id)
    if user is None:
        return "gone"
    if llm is None and (deps.settings.agent_mode or "hybrid") != "rules":
        try:
            from app.llm import get_llm
            llm = get_llm()
        except Exception:
            llm = None
    try:
        outcome = _review(db, user, event_id, payload, llm, deps)
        job = db.get(EventJob, job_id)
        if job is None:  # user deleted meanwhile (cascade)
            db.rollback()
            return "gone"
        job.state, job.payload, job.error = "done", None, None
        job.agent_ran, job.upgraded = outcome.agent_ran, outcome.alert is not None
        job.updated_at = datetime.now(timezone.utc)
        db.commit()
    except Exception as exc:
        db.rollback()
        log.warning("review job %s failed (%s)", job_id, type(exc).__name__)
        try:
            job = db.get(EventJob, job_id)
            if job is None or db.get(User, user_id) is None:
                db.rollback()
                return "gone"
            failed = job.attempts >= MAX_ATTEMPTS
            job.state = "failed" if failed else "queued"
            job.error = type(exc).__name__[:64]
            job.payload = None if failed else job.payload
            job.updated_at = datetime.now(timezone.utc)
            db.commit()
        except Exception:
            db.rollback()
        return "retry"
    if outcome.alert is not None and outcome.decision is not None and outcome.decision.show:
        from app.pipeline.orchestrator import _run_hooks
        _run_hooks(db, user, outcome.alert, outcome.decision, deps)
        return "upgraded"
    return "done"


@dataclass
class _ReviewOutcome:
    agent_ran: bool = False
    alert: AlertOut | None = None
    decision: policy.Decision | None = None


def _rank(t: str | None) -> int:
    return TIER_ORDER.index(t) if t in TIER_ORDER else 0


def agent_runs_today(db: Session, user_id: str, now: datetime) -> int:
    return db.scalar(select(func.count(EventJob.id)).where(
        EventJob.user_id == user_id, EventJob.agent_ran.is_(True),
        EventJob.updated_at > now - timedelta(hours=24))) or 0


def _review(db: Session, user, event_id: str, payload: dict, llm, deps) -> _ReviewOutcome:
    import hashlib
    import time

    from app.pipeline import orchestrator as orch
    from app.pipeline.extract import extract_entities

    s = deps.settings
    t0 = time.monotonic()
    if embeddings.semantic_enabled(s):
        semantic.link_event(db, user, event_id, settings=s)
        db.commit()
    if payload.get("force") or not isinstance(payload.get("event"), dict):
        return _ReviewOutcome()  # a user's own check already ran the agent and got its answer
    rev = EventIn.model_validate(payload["event"])
    entities = extract_entities(rev.text, rev.sender, rev.call)
    skey = orch.sender_key_for(rev.sender, rev.call)
    prior = db.execute(select(Assessment.tier, Assessment.payload).where(
        Assessment.user_id == user.id, Assessment.event_id == event_id)).all()
    if not prior:
        return _ReviewOutcome()
    best = max((t for t, _ in prior), key=_rank)
    stage1_agent = any(isinstance(p, dict) and p.get("mode") not in (None, "rules") for _, p in prior)

    mode = (s.agent_review or "escalated").strip().lower()
    agent_ok = (s.agent_mode or "hybrid") != "rules" and llm is not None and (
        agent_runs_today(db, user.id, datetime.now(timezone.utc)) < max(0, s.agent_review_daily_cap))
    # rules-only re-score first: with AGENT_REVIEW=escalated the agent runs only for an event that
    # stage 2 newly escalates (stage 1 did not investigate it)
    ev_ = orch.evaluate(db, user, rev, rev, entities, event_id, skey, False, llm, deps, semantic=True,
                        run_agent=False)
    want_agent = agent_ok and (mode == "all" or (not stage1_agent and _rank(ev_.tier) >= _rank("caution")
                                                 and _rank(ev_.tier) > _rank(best)))
    ran = False
    if want_agent:
        ev_ = orch.evaluate(db, user, rev, rev, entities, event_id, skey, False, llm, deps, semantic=True,
                            run_agent=True)
        ran = True
    if _rank(ev_.tier) <= _rank(best):
        db.commit()
        return _ReviewOutcome(agent_ran=ran)
    # upgrade: a new assessment + alert for the same event; tier increase passes dedupe, flood / ceiling /
    # daily-cap guards still apply
    asm = orch.persist_assessment(db, user, ev_, event_id, llm, deps, t0,
                                  extra={"review": True, "upgraded_from": best})
    key = policy.dedupe_key(entities, hashlib.sha256((rev.text or "").encode()).hexdigest())
    d = policy.decide(db, user, policy.AlertCandidate(event_id, ev_.tier, key, rev.ts))
    decision = policy.Decision(d.show, d.interrupt, REVIEW_UPGRADE if d.show else d.reason)
    now = datetime.now(timezone.utc)
    alert = Alert(user_id=user.id, assessment_id=asm.id, shown_at=now if d.show else None, dedupe_key=key,
                  interrupt=d.interrupt, reason=decision.reason, created_at=now)
    db.add(alert)
    db.flush()
    out = AlertOut(id=alert.id, event_id=event_id, tier=ev_.tier, headline=ev_.headline, why=ev_.why,
                   next_step=ev_.next_step, signals=ev_.names, created_at=now)
    log.info("review upgraded event %s %s -> %s (shown=%s)", event_id, best, ev_.tier, d.show)
    return _ReviewOutcome(agent_ran=ran, alert=out, decision=decision)


# ---------------------------------------------------------------- startup sweeper
def sweep(session_factory: Callable, *, older_than: timedelta, deps=None, llm=None) -> int:
    """Resubmit queued / running jobs not touched for `older_than` (a crash or restart lost them)."""
    cutoff = datetime.now(timezone.utc) - older_than
    with session_factory() as db:
        ids = db.scalars(select(EventJob.event_id).where(
            EventJob.state.in_(("queued", "running")), EventJob.attempts < MAX_ATTEMPTS,
            EventJob.updated_at < cutoff).order_by(EventJob.updated_at).limit(1000)).all()
    for eid in ids:
        submit(eid, session_factory=session_factory, llm=llm, deps=deps)
    if ids:
        log.info("review sweeper resubmitted %d jobs", len(ids))
    return len(ids)
