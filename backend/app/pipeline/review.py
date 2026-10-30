"""Stage-2 background review: re-assess an event after its stage-1 response, possibly UPGRADING its alert.

    stage 1 (request): process_event -> assessment + alert -> commit (one row in event_jobs, same tx)
                       -> hooks -> response;  then submit(job) to a bounded in-process worker pool
    stage 2 (worker):  advisory lock -> embed + `similar` links -> re-score with the richer history
                       -> agent (AGENT_REVIEW=all, or newly escalated; daily cap per user) -> if the tier is
                       HIGHER than any tier already assessed for the event: new assessment + alert via
                       policy.decide (reason review_upgrade) -> commit -> alert hooks

Guarantees: never downgrades or retracts an alert; one job per event (unique event_id), per-event
advisory lock, at most MAX_ATTEMPTS attempts; a full queue (or a user over REVIEW_MAX_QUEUED_PER_USER)
leaves the job `queued` for the periodic sweeper (round-robin across users) and never blocks the request;
the agent slot is RESERVED (agent_ran=True, committed under a per-user advisory lock) before the LLM call,
so concurrent jobs cannot exceed the daily cap, and a failed agent run keeps its slot; LLM / embedding failures finish the job with no upgrade; a job whose user was deleted
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

from sqlalchemy import func, select, text
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


def sweeper_needed(settings) -> bool:
    """Jobs can exist: stage 2 is enabled, or fast-first alerts schedule their agent refinement."""
    return enabled(settings) or (bool(getattr(settings, "fast_first", False))
                                 and (settings.agent_mode or "hybrid") == "hybrid")


def enqueue(db: Session, user, event_id: str, rev: EventIn, *, force: bool, settings,
            stage1_assessment: str | None = None) -> str | None:
    """Add the job row inside the caller's transaction (lands with the event). Returns the event id.
    `stage1_assessment` (fast-first): the rules-based assessment the agent will refine; it also makes the
    job exist when stage 2 is otherwise disabled."""
    if not enabled(settings) and stage1_assessment is None:
        return None
    payload = {"event": rev.model_dump(mode="json"), "force": bool(force)}
    if stage1_assessment is not None:
        payload["fast"] = {"assessment": stage1_assessment}
    db.add(EventJob(user_id=user.id, event_id=event_id, state="queued", payload=payload))
    db.flush()
    return event_id


# ---------------------------------------------------------------- worker pool
@dataclass
class _Task:
    event_id: str
    session_factory: Callable
    llm: Any = None
    deps: Any = None
    user_id: str | None = None


@dataclass
class Runner:
    workers: int = 2
    queue_size: int = 256
    deferred: bool = False  # tests / eval: jobs wait for flush_background()
    max_per_user: int = 20  # in-queue + running jobs per user; 0 = unlimited
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _pending: list = field(default_factory=list)
    _futures: set = field(default_factory=set)
    _inflight: dict = field(default_factory=dict)  # event_id -> user_id, queued in memory or running
    _pool: ThreadPoolExecutor | None = None

    def inflight_ids(self) -> set:
        with self._lock:
            return set(self._inflight)

    def submit(self, task: _Task) -> bool:
        """Accept the task, or refuse it (False) when the queue is full or its user is over the fair limit.
        A refused job is NOT failed: its row stays `queued` and the sweeper submits it later."""
        with self._lock:
            if task.event_id in self._inflight:
                return True  # already queued / running here
            if len(self._inflight) >= self.queue_size:
                refused = "queue_full"
            elif (task.user_id is not None and self.max_per_user > 0
                  and sum(1 for u in self._inflight.values() if u == task.user_id) >= self.max_per_user):
                refused = "user_limit"
            else:
                refused = None
                self._inflight[task.event_id] = task.user_id
                if self.deferred:
                    self._pending.append(task)
                else:
                    if self._pool is None:
                        self._pool = ThreadPoolExecutor(max_workers=max(1, self.workers),
                                                        thread_name_prefix="review")
                    fut: Future = self._pool.submit(_run_safe, task)
                    self._futures.add(fut)
                    fut.add_done_callback(lambda f, eid=task.event_id: self._done(f, eid))
        if refused:
            log.info("review job for event %s stays queued (%s)", task.event_id, refused)
            return False
        return True

    def _done(self, fut, event_id: str) -> None:
        with self._lock:
            self._futures.discard(fut)
            self._inflight.pop(event_id, None)

    def flush(self) -> None:
        """Run / wait for every pending job (deferred jobs run in the calling thread, in order)."""
        while True:
            with self._lock:
                batch, self._pending = self._pending, []
                futs = list(self._futures)
            if not batch and not futs:
                return
            for t in batch:
                try:
                    _run_safe(t)
                finally:
                    with self._lock:
                        self._inflight.pop(t.event_id, None)
            if futs:
                wait(futs)


runner = Runner()


def configure(*, workers: int, queue_size: int, deferred: bool = False, max_per_user: int = 20) -> Runner:
    global runner
    runner = Runner(workers=workers, queue_size=queue_size, deferred=deferred, max_per_user=max_per_user)
    return runner


def submit(event_id: str, *, session_factory: Callable, llm=None, deps=None, user_id: str | None = None) -> bool:
    return runner.submit(_Task(event_id, session_factory, llm, deps, user_id))


def flush_background() -> None:
    """Tests / eval: run all pending stage-2 jobs now, synchronously."""
    runner.flush()


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
        job.agent_ran, job.upgraded = job.agent_ran or outcome.agent_ran, outcome.alert is not None
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


def reserve_agent_slot(db: Session, user_id: str, event_id: str, cap: int) -> bool:
    """Atomically take one of the user's daily agent slots BEFORE the LLM call.

    A transaction-scoped advisory lock per user serialises the count + mark, so concurrent jobs cannot all
    pass the check. The mark (job.agent_ran=True) is committed before returning. A job that already holds
    a reservation (a retry after a failed agent run) does not get a second one: its slot was spent."""
    db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:k))"), {"k": f"agentcap:{user_id}"})
    job = db.scalar(select(EventJob).where(EventJob.event_id == event_id))
    if job is None or job.agent_ran or agent_runs_today(db, user_id, datetime.now(timezone.utc)) >= cap:
        db.commit()
        return False
    job.agent_ran = True
    job.updated_at = datetime.now(timezone.utc)
    db.commit()
    return True


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

    fast = payload.get("fast") if isinstance(payload.get("fast"), dict) else None
    mode = (s.agent_review or "escalated").strip().lower()
    # cheap pre-check only; the authoritative cap check is the atomic reservation below
    agent_ok = (s.agent_mode or "hybrid") != "rules" and llm is not None and (
        agent_runs_today(db, user.id, datetime.now(timezone.utc)) < max(0, s.agent_review_daily_cap))
    # rules-only re-score first: with AGENT_REVIEW=escalated the agent runs only for an event that
    # stage 2 newly escalates (stage 1 did not investigate it)
    ev_ = orch.evaluate(db, user, rev, rev, entities, event_id, skey, False, llm, deps, semantic=True,
                        run_agent=False)
    want_agent = agent_ok and (fast is not None or mode == "all" or (not stage1_agent and _rank(ev_.tier) >= _rank("caution")
                                                 and _rank(ev_.tier) > _rank(best)))
    ran = False
    if want_agent and reserve_agent_slot(db, user.id, event_id, max(0, s.agent_review_daily_cap)):
        ran = True  # the slot is spent from here on, even if the agent call fails
        ev_ = orch.evaluate(db, user, rev, rev, entities, event_id, skey, False, llm, deps, semantic=True,
                            run_agent=True)
    if _rank(ev_.tier) <= _rank(best):
        db.commit()
        return _ReviewOutcome(agent_ran=ran)
    # upgrade: a new assessment + alert for the same event; tier increase passes dedupe, flood / ceiling /
    # daily-cap guards still apply
    asm = orch.persist_assessment(db, user, ev_, event_id, llm, deps, t0,
                                  extra={"review": True, "upgraded_from": best,
                                       **({"stage": "agent"} if ran and not ev_.verdict.degraded else {})})
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


# ---------------------------------------------------------------- sweeper
_sweep_lock = threading.Lock()
SWEEP_BATCH = 1000


def sweep(session_factory: Callable, *, older_than: timedelta, deps=None, llm=None) -> int:
    """Submit jobs that are not in the in-memory queue: `queued` ones (refused earlier because the queue or
    the user's limit was full, or lost in a restart) and `running` ones not touched for `older_than` (a
    crash). Round-robin across users, oldest first within a user, so a flooding user cannot starve others.
    Never runs two sweeps at once in a process. Returns the number of jobs accepted."""
    if not _sweep_lock.acquire(blocking=False):
        return 0
    try:
        cutoff = datetime.now(timezone.utc) - older_than
        rn = func.row_number().over(partition_by=EventJob.user_id,
                                    order_by=(EventJob.created_at, EventJob.id)).label("rn")
        inflight = runner.inflight_ids()
        with session_factory() as db:
            sub = select(EventJob.event_id, EventJob.user_id, EventJob.created_at, rn).where(
                EventJob.attempts < MAX_ATTEMPTS,
                ((EventJob.state == "queued") | ((EventJob.state == "running") & (EventJob.updated_at < cutoff))),
                EventJob.event_id.not_in(inflight) if inflight else True).subquery()
            rows = db.execute(select(sub.c.event_id, sub.c.user_id).order_by(
                sub.c.rn, sub.c.created_at).limit(SWEEP_BATCH)).all()
        accepted = 0
        for eid, uid in rows:
            if runner.submit(_Task(eid, session_factory, llm, deps, uid)):
                accepted += 1
            elif len(runner.inflight_ids()) >= runner.queue_size:
                break  # queue full: the rest wait for the next pass
        if accepted:
            log.info("review sweeper submitted %d jobs", accepted)
        return accepted
    finally:
        _sweep_lock.release()


def start_sweeper(session_factory: Callable, *, interval: timedelta, older_than: timedelta,
                  deps=None) -> threading.Event:
    """Daemon thread: sweep now, then every `interval` until the returned event is set."""
    stop = threading.Event()

    def loop():
        while True:
            try:
                sweep(session_factory, older_than=older_than, deps=deps)
            except Exception as exc:
                log.warning("review sweeper failed (%s)", type(exc).__name__)
            if stop.wait(max(1.0, interval.total_seconds())):
                return

    threading.Thread(target=loop, name="review-sweeper", daemon=True).start()
    return stop
