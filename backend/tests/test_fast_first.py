"""Fast-first alerts: POST /v1/events answers an escalated event from the rules floor at once; the agent
refines it (same alert id) or upgrades it (new alert) in the background (stage 2, review.py)."""
import threading
import time
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.feedback import apply_feedback
from app.llm import ChatResult, FakeLLM, LLMUnavailable, ToolCall, get_llm
from app.main import app
from app.models import Alert, Assessment, EventJob, User
from app.pipeline import link_client, review
from app.pipeline.link_client import LinkReport
from app.pipeline.signals import DomainInfo
from app.routes.events import get_pipeline_deps

from tests.conftest import DownLLM, offline_deps
from tests.test_pipeline_e2e import WHATSAPP, D1, at, final, post

AGENT_TEXT = "AGENT: fake customs officer demanding UPI payment"


class BlockingLLM(FakeLLM):
    """chat() blocks until released, like a 25 s model call."""

    def __init__(self, script):
        super().__init__(script)
        self.entered = threading.Event()
        self.release = threading.Event()

    def chat(self, *a, **kw):
        self.entered.set()
        assert self.release.wait(30)
        return super().chat(*a, **kw)


@pytest.fixture
def ff(client, fast_first):
    """Fast-first on, deferred stage 2 (flush_background runs it), hooks recorded."""
    hooks = []
    deps = offline_deps()
    deps.hooks = [lambda db, user, alert, decision=None, **kw: hooks.append((user.id, alert, decision))]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    review.configure(workers=1, queue_size=100, deferred=True)
    yield hooks
    review.flush_background()
    review.configure(workers=settings.review_workers, queue_size=settings.review_queue_size)


def use_llm(llm):
    app.dependency_overrides[get_llm] = lambda: llm
    return llm


def alerts(client, headers):
    return client.get("/v1/alerts", headers=headers).json()


def n_alerts(db):
    db.expire_all()
    return db.scalar(select(func.count()).select_from(Alert))


# (i) ------------------------------------------------------------------ the response does not wait for the agent
def test_critical_is_answered_before_the_blocked_agent_then_refined_in_place(client, auth_headers, db, fast_first):
    hooks = []
    deps = offline_deps()
    deps.hooks = [lambda db_, user, alert, decision=None, **kw: hooks.append(alert.id)]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    review.configure(workers=1, queue_size=100)  # real background pool
    llm = use_llm(BlockingLLM([final("critical", AGENT_TEXT, "Do not pay. Call 1930.")]))
    try:
        t = time.monotonic()
        r = post(client, auth_headers, WHATSAPP)
        elapsed = time.monotonic() - t
        assert elapsed < 2.0
        a = r["alert"]
        assert a["tier"] == "critical" and a["headline"] and a["headline"] != AGENT_TEXT
        assert llm.entered.wait(10)  # the agent is running in the background, still blocked
        assert [x["id"] for x in alerts(client, auth_headers)] == [a["id"]]
        assert alerts(client, auth_headers)[0]["headline"] == a["headline"]
        assert hooks == [a["id"]]
        llm.release.set()
        review.flush_background()
        after = alerts(client, auth_headers)
        assert len(after) == 1 and after[0]["id"] == a["id"] and after[0]["tier"] == "critical"
        assert after[0]["headline"] == AGENT_TEXT and after[0]["next_step"] == "Do not pay. Call 1930."
        assert after[0]["created_at"] == a["created_at"]
        assert n_alerts(db) == 1 and hooks == [a["id"]]  # no new alert row, no re-notification
        job = db.scalar(select(EventJob))
        assert job.state == "done" and job.agent_ran is True and job.upgraded is False
        assert db.scalar(select(Assessment.payload["stage"].astext)) == "agent"
    finally:
        llm.release.set()
        review.configure(workers=settings.review_workers, queue_size=settings.review_queue_size)


def test_stage1_payload_is_the_rules_alert_and_agent_not_called_inline(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    assert llm.calls == [] and r["alert"]["tier"] == "critical"
    db.expire_all()
    asm = db.scalar(select(Assessment))
    assert asm.payload["stage"] == "rules" and asm.model == "rules" and asm.degraded is False
    job = db.scalar(select(EventJob))
    assert job.state == "queued" and job.payload["fast"]["assessment"] == asm.id


# (ii) ------------------------------------------------------------------ agent raises the tier -> upgrade alert
def test_agent_upgrade_creates_one_new_alert_and_hooks_once(client, auth_headers, db, ff, monkeypatch):
    url = "https://hdfc-kyc-update.xyz/login"
    monkeypatch.setattr(link_client, "inspect_link", lambda u, s=None: LinkReport(
        final_url=url, fetched_ok=True, has_password_field=True))
    deps = offline_deps()
    deps.domain_info = lambda ctx, d: DomainInfo(age_days=2 if d == "hdfc-kyc-update.xyz" else 4000)
    deps.hooks = [lambda db_, user, alert, decision=None, **kw: ff.append((alert.id, decision))]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    use_llm(FakeLLM([ChatResult(content=None, tool_calls=[ToolCall("c1", "inspect_link", {"url": url})]),
                     final("high", "Fake HDFC login page", "Do not enter your password.",
                           raises=[{"tool": "inspect_link", "fact": "credential_form"}])]))
    body = {"device_event_id": "l1", "source": "sms", "ts": D1.isoformat(), "sender": "+919811100055",
            "text": f"Dear customer, your KYC is pending. Update here: {url}", "triage_score": 0.7}
    r = post(client, auth_headers, body)
    s1 = r["alert"]
    assert s1 is not None and s1["tier"] in ("caution", "high")
    n_hooks = len(ff)
    review.flush_background()
    got = alerts(client, auth_headers)
    top = [a for a in got if a["tier"] == "critical"]
    assert len(top) == 1 and top[0]["id"] != s1["id"] and top[0]["event_id"] == s1["event_id"]
    db.expire_all()
    up = db.scalar(select(Alert).where(Alert.id == top[0]["id"]))
    assert up.reason == review.REVIEW_UPGRADE and up.interrupt is True
    assert len(ff) == n_hooks + 1 and ff[-1][0] == up.id and ff[-1][1].interrupt
    assert n_alerts(db) == 2  # stage-1 alert (never retracted) + the upgrade


# (iii) ------------------------------------------------------------------ agent failures keep the rules alert
@pytest.mark.parametrize("make", [
    lambda: DownLLM(),
    lambda: FakeLLM([RuntimeError("boom")]),
    lambda: FakeLLM([LLMUnavailable("timeout")]),
    lambda: FakeLLM([ChatResult(content="not json at all")]),
])
def test_agent_failure_keeps_rules_alert(client, auth_headers, db, ff, make):
    use_llm(make())
    r = post(client, auth_headers, WHATSAPP)
    review.flush_background()
    got = alerts(client, auth_headers)
    assert len(got) == 1 and got[0]["id"] == r["alert"]["id"] and got[0]["headline"] == r["alert"]["headline"]
    db.expire_all()
    job = db.scalar(select(EventJob))
    assert job.state == "done" and n_alerts(db) == 1 and len(ff) == 1
    assert db.scalar(select(Assessment.payload["stage"].astext)) == "rules"


def test_agent_hanging_beyond_budget_keeps_rules_alert(client, auth_headers, db, ff, monkeypatch):
    class Slow(FakeLLM):
        def chat(self, *a, **kw):
            time.sleep(3)
            return final("critical", AGENT_TEXT)

    from app.pipeline import agent
    monkeypatch.setattr(agent, "BUDGET_S", 0.5)
    use_llm(Slow())
    r = post(client, auth_headers, WHATSAPP)
    review.flush_background()
    assert alerts(client, auth_headers)[0]["headline"] == r["alert"]["headline"]
    assert db.scalar(select(EventJob.state)) == "done"


# (iv) ------------------------------------------------------------------ FAST_FIRST=false is synchronous
def test_fast_first_off_runs_agent_inline(client, auth_headers, db):
    assert settings.fast_first is False  # suite default (conftest)
    use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    assert r["alert"]["headline"] == AGENT_TEXT
    assert db.query(EventJob).count() == 0


def test_rules_mode_and_info_events_create_no_job(client, auth_headers, db, ff, monkeypatch):
    use_llm(FakeLLM())
    post(client, auth_headers, {"device_event_id": "x", "source": "sms", "ts": D1.isoformat(),
                                "sender": "VM-HDFCBK", "text": "Hello, lunch tomorrow?", "triage_score": 0.1})
    assert db.query(EventJob).count() == 0
    monkeypatch.setattr(settings, "agent_mode", "rules")
    post(client, auth_headers, WHATSAPP)
    assert db.query(EventJob).count() == 0


# (v) ------------------------------------------------------------------ duplicates
def test_duplicate_delivery_enqueues_and_refines_once(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT), final("critical", "SECOND")]))
    r1 = post(client, auth_headers, WHATSAPP)
    r2 = post(client, auth_headers, WHATSAPP)
    assert r2["duplicate"] is True
    assert db.query(EventJob).count() == 1
    review.flush_background()
    sf = sessionmaker(bind=db.get_bind())
    assert review.run_job(sf, r1["event_id"], llm=llm) == "done"  # a second run is a no-op
    assert len(llm.calls) == 1 and n_alerts(db) == 1 and len(ff) == 1
    assert alerts(client, auth_headers)[0]["headline"] == AGENT_TEXT


def test_refinement_is_not_overwritten_by_a_later_run(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    review.flush_background()
    db.expire_all()
    asm = db.scalar(select(Assessment))
    sf = sessionmaker(bind=db.get_bind())
    with sf() as s2:
        user = s2.get(User, asm.user_id)
        ev_ = type("E", (), {})()
        ev_.tier, ev_.headline, ev_.next_step, ev_.why, ev_.names, ev_.stages = "critical", "OLD", "x", [], [], []
        ev_.mode, ev_.verdict = "hybrid", type("V", (), {"used_tools": [], "raised_by": []})()
        assert review.refine_alert(s2, user, r["event_id"], asm.id, ev_, llm, offline_deps()) is False
    assert alerts(client, auth_headers)[0]["headline"] == AGENT_TEXT


# (vi) ------------------------------------------------------------------ feedback label survives
def test_feedback_label_survives_refinement(client, auth_headers, db, ff):
    use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    assert client.post(f"/v1/alerts/{r['alert']['id']}/feedback", json={"label": "scam"},
                       headers=auth_headers).status_code in (200, 204)
    review.flush_background()
    db.expire_all()
    row = db.scalar(select(Alert))
    assert row.user_label == "scam" and alerts(client, auth_headers)[0]["headline"] == AGENT_TEXT


# (vii) ------------------------------------------------------------------ cap exhausted
def test_cap_exhausted_keeps_rules_alert_and_never_calls_agent(client, auth_headers, db, ff, monkeypatch):
    monkeypatch.setattr(settings, "agent_review_daily_cap", 0)
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    review.flush_background()
    assert llm.calls == []
    assert alerts(client, auth_headers)[0]["headline"] == r["alert"]["headline"]
    db.expire_all()
    job = db.scalar(select(EventJob))
    assert job.state == "done" and job.agent_ran is False


def test_cap_is_shared_across_events(client, auth_headers, db, ff, monkeypatch):
    monkeypatch.setattr(settings, "agent_review_daily_cap", 1)
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT), final("critical", "SECOND")]))
    post(client, auth_headers, WHATSAPP)
    post(client, auth_headers, at(WHATSAPP, device_event_id="d3b", ts=D1 + timedelta(days=3),
                                   sender="+919876500011"))
    review.flush_background()
    assert len(llm.calls) == 1


# (viii) ------------------------------------------------------------------ user isolation
def test_jobs_never_touch_another_users_alerts(client, register, db, ff):
    _, alice = register()
    _, bob = register()
    use_llm(FakeLLM([final("critical", "ALICE TEXT"), final("critical", "BOB TEXT")]))
    ra = post(client, alice, WHATSAPP)
    rb = post(client, bob, WHATSAPP)
    review.flush_background()
    ga, gb = alerts(client, alice), alerts(client, bob)
    assert [(a["id"], a["headline"]) for a in ga] == [(ra["alert"]["id"], "ALICE TEXT")]
    assert [(a["id"], a["headline"]) for a in gb] == [(rb["alert"]["id"], "BOB TEXT")]


# (ix) ------------------------------------------------------------------ /v1/check unchanged
def test_check_stays_synchronous(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP, path="/v1/check")
    assert r["alert"]["headline"] == AGENT_TEXT and len(llm.calls) == 1
    assert db.query(EventJob).count() == 0


# (x) ------------------------------------------------------------------ restart
def test_sweeper_resumes_queued_stage2_job_after_restart(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    review.configure(workers=1, queue_size=100, deferred=True)  # process restart: in-memory queue lost
    assert db.scalar(select(EventJob.state)) == "queued"
    sf = sessionmaker(bind=db.get_bind())
    assert review.sweep(sf, older_than=timedelta(minutes=5), llm=llm) == 1
    review.flush_background()
    got = alerts(client, auth_headers)
    assert len(got) == 1 and got[0]["id"] == r["alert"]["id"] and got[0]["headline"] == AGENT_TEXT


# (xi) ------------------------------------------------------------------ refine vs feedback race
def test_concurrent_refine_and_feedback(client, auth_headers, db, ff):
    llm = use_llm(FakeLLM([final("critical", AGENT_TEXT)]))
    r = post(client, auth_headers, WHATSAPP)
    sf = sessionmaker(bind=db.get_bind())
    uid = db.scalar(select(User.id))
    barrier = threading.Barrier(2)
    errs = []

    def do_job():
        try:
            barrier.wait()
            review.run_job(sf, r["event_id"], llm=llm, deps=offline_deps())
        except Exception as e:  # pragma: no cover
            errs.append(e)

    def do_feedback():
        try:
            with sf() as s:
                u = s.get(User, uid)
                barrier.wait()
                assert apply_feedback(s, u, r["alert"]["id"], "scam")
        except Exception as e:  # pragma: no cover
            errs.append(e)

    ts = [threading.Thread(target=f) for f in (do_job, do_feedback)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert not errs
    db.expire_all()
    row = db.scalar(select(Alert))
    assert row.user_label == "scam"
    assert alerts(client, auth_headers)[0]["headline"] == AGENT_TEXT and n_alerts(db) == 1


# ------------------------------------------------------------------ rules-only latency stays small
def test_stage1_latency_with_a_slow_agent_is_rules_only(client, auth_headers, ff):
    class Slow(FakeLLM):
        def chat(self, *a, **kw):
            time.sleep(5)
            return final("critical", AGENT_TEXT)

    use_llm(Slow())
    post(client, auth_headers, at(WHATSAPP, device_event_id="warm"))  # warm-up (jobs are deferred: no agent)
    ts = []
    for i in range(5):
        t = time.monotonic()
        post(client, auth_headers, at(WHATSAPP, device_event_id=f"w{i}", ts=D1 + timedelta(days=5 + i),
                                       sender=f"+91987650001{i}"))
        ts.append(time.monotonic() - t)
    assert max(ts) < 1.5
    review.configure(workers=1, queue_size=100, deferred=True)  # drop the pending slow jobs


# ------------------------------------------------------------------ stage 2 always redoes the rules WITH network data
NEW_DOMAIN_EVENT = {"device_event_id": "nd1", "source": "sms", "ts": D1.isoformat(), "sender": "+919811100077",
                    "text": "Urgent: see the new offers at https://fresh-offers-store.xyz/a today.",
                    "triage_score": 0.7}


def _rdap_deps(ff_hooks):
    deps = offline_deps()
    deps.domain_info = lambda ctx, d: DomainInfo(age_days=2 if d == "fresh-offers-store.xyz" else 4000)
    deps.hooks = [lambda db_, user, alert, decision=None, **kw: ff_hooks.append((alert.id, decision))]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    return deps


def _sync_floor(client, register, db, monkeypatch):
    """Tier synchronous mode (rules floor with RDAP) gives for the event, from a separate user."""
    _, headers = register()
    with monkeypatch.context() as m:
        m.setattr(settings, "fast_first", False)
        m.setattr(settings, "agent_mode", "rules")
        r = post(client, headers, NEW_DOMAIN_EVENT)
    return r["alert"]["tier"]


@pytest.mark.parametrize("state", ["cap_exhausted", "llm_down", "no_llm_result", "queue_full"])
def test_rdap_floor_is_recomputed_when_agent_cannot_run(client, register, auth_headers, db, ff, monkeypatch, state):
    deps = _rdap_deps(ff)
    floor = _sync_floor(client, register, db, monkeypatch)
    assert floor in ("high", "critical")
    db.query(Alert).delete()
    db.query(EventJob).delete()
    db.commit()
    ff.clear()
    llm = use_llm(DownLLM() if state == "llm_down" else FakeLLM([final("caution", "AGENT says caution")]))
    if state == "cap_exhausted":
        monkeypatch.setattr(settings, "agent_review_daily_cap", 0)
    if state == "queue_full":
        review.configure(workers=1, queue_size=0, deferred=True)
    ev = {**NEW_DOMAIN_EVENT, "device_event_id": "nd2"}
    r = post(client, auth_headers, ev)
    s1 = r["alert"]["tier"] if r["alert"] else "info"
    assert TIER_ORDER.index(s1) < TIER_ORDER.index(floor)  # stage 1 alone is below the synchronous floor
    if state == "queue_full":
        assert db.scalar(select(EventJob.state)) == "queued"
        review.configure(workers=1, queue_size=100, deferred=True)
        sf = sessionmaker(bind=db.get_bind())
        assert review.sweep(sf, older_than=timedelta(minutes=5), llm=llm, deps=deps) == 1
    review.flush_background()
    best = max((a["tier"] for a in alerts(client, auth_headers)), key=TIER_ORDER.index)
    assert best == floor
    up = db.scalars(select(Alert).where(Alert.reason == review.REVIEW_UPGRADE)).all()
    assert len(up) == 1 and up[0].interrupt is True
    assert [h for h in ff if h[0] == up[0].id].__len__() == 1  # hooks fire once, for the upgrade
    if state == "cap_exhausted":
        assert db.scalar(select(EventJob.agent_ran)) is False and llm.calls == []


from app.pipeline.signals import TIER_ORDER  # noqa: E402
