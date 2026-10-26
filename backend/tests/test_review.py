"""Two-stage review (app/pipeline/review.py): background re-assessment, upgrade alerts, job safety."""
import json
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select, text, update
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.llm import ChatResult, FakeLLM, get_llm
from app.main import app
from app.models import Alert, Assessment, Edge, EventJob
from app.pipeline import review
from app.routes.events import get_pipeline_deps

from tests.conftest import offline_deps

T0 = datetime(2026, 3, 2, 9, 0, tzinfo=timezone.utc)
# three harmless-looking messages from three different numbers, no shared entity
STORY = [
    ("+919800000101", "Hi, I got your number from the Alpha traders group. We share free stock tips with members.", 0),
    ("+919800000102", "Alpha traders group update: members booked guaranteed profit this week with our stock tips.", 30),
    ("+919800000103", "Alpha traders group: the registration fee of Rs 4,999 is required to secure your VIP slot.", 60),
]


@pytest.fixture
def two_stage(monkeypatch, client):
    for k, v in {"embedding_provider": "fake", "semantic_memory": True, "semantic_threshold": 0.2,
                 "agent_mode": "rules", "agent_review": "escalated"}.items():
        monkeypatch.setattr(settings, k, v)
    r = review.configure(workers=1, queue_size=100, deferred=True)
    calls = []
    deps = offline_deps()
    deps.hooks = [lambda db, user, alert, decision=None, **kw: calls.append((alert, decision))]
    app.dependency_overrides[get_pipeline_deps] = lambda: deps
    yield calls
    r.flush()
    review.configure(workers=2, queue_size=256)


def post(client, headers, i, sender, txt, hours, path="/v1/events", **extra):
    body = {"device_event_id": f"r-{i}", "source": "whatsapp", "ts": (T0 + timedelta(hours=hours)).isoformat(),
            "sender": sender, "text": txt, "triage_score": 0.1, **extra}
    r = client.post(path, json=body, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def play_story(client, headers):
    out = []
    for i, (snd, txt, h) in enumerate(STORY):
        out.append(post(client, headers, i, snd, txt, h))
        review.flush_background()
    return out


def test_disabled_by_default_creates_no_jobs(client, auth_headers, db, monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "rules")
    post(client, auth_headers, 1, *STORY[0][:2], 0)
    assert db.query(EventJob).count() == 0


def test_distributed_story_is_upgraded_in_stage_two(client, auth_headers, db, two_stage):
    res = play_story(client, auth_headers)
    assert all(r["alert"] is None or r["alert"]["tier"] in ("info", "caution") for r in res)  # stage 1 quiet
    alerts = client.get("/v1/alerts", headers=auth_headers).json()
    top = alerts[0]
    assert top["event_id"] == res[2]["event_id"] and top["tier"] in ("high", "critical")
    assert "story_progression" in top["signals"]
    row = db.scalar(select(Alert).where(Alert.id == top["id"]))
    assert row.reason == "review_upgrade" and row.interrupt is True
    alert, decision = two_stage[-1]
    assert alert.event_id == res[2]["event_id"] and decision.interrupt and decision.reason == "review_upgrade"
    assert db.query(Edge).filter(Edge.relation == "similar").count() >= 1
    # the why list can cite the semantically linked earlier events by their real ids
    ids = {r["event_id"] for r in res[:2]}
    assert {w["event_id"] for w in top["why"]} & ids
    jobs = db.scalars(select(EventJob)).all()
    assert len(jobs) == 3 and all(j.state == "done" and j.payload is None and j.attempts == 1 for j in jobs)


def test_never_downgrades_or_duplicates(client, auth_headers, db, two_stage):
    r = post(client, auth_headers, 1, "+919800000201",
             "This is Inspector Sharma, cyber crime. Pay Rs 50,000 fine immediately or face arrest.", 0)
    assert r["alert"]["tier"] in ("high", "critical")
    review.flush_background()
    assert db.query(Alert).count() == 1
    # re-running the finished job is a no-op
    sf = sessionmaker(bind=db.get_bind())
    assert review.run_job(sf, r["event_id"]) == "done"
    assert db.query(Alert).count() == 1


def test_locked_job_is_skipped(client, auth_headers, db, two_stage):
    r = post(client, auth_headers, 1, *STORY[0][:2], 0)
    sf = sessionmaker(bind=db.get_bind())
    with sf() as other:
        other.execute(text("SELECT pg_advisory_lock(hashtext(:k))"), {"k": f"review:{r['event_id']}"})
        assert review.run_job(sf, r["event_id"]) == "locked"
        other.execute(text("SELECT pg_advisory_unlock(hashtext(:k))"), {"k": f"review:{r['event_id']}"})
    review.flush_background()
    assert db.scalar(select(EventJob.state)) == "done"


def test_failure_retries_once_then_fails(client, auth_headers, db, two_stage, monkeypatch):
    monkeypatch.setattr(review, "_review", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom")))
    r = post(client, auth_headers, 1, *STORY[0][:2], 0)
    review.flush_background()
    db.expire_all()
    job = db.scalar(select(EventJob))
    assert job.state == "queued" and job.attempts == 1 and job.error == "RuntimeError"
    sf = sessionmaker(bind=db.get_bind())
    db.commit()  # end this session's snapshot
    assert review.run_job(sf, r["event_id"]) == "retry"
    db.expire_all()
    job = db.scalar(select(EventJob))
    assert job.state == "failed" and job.attempts == 2 and job.payload is None
    assert review.run_job(sf, r["event_id"]) == "failed"


def test_llm_down_finishes_job_without_upgrade(client, auth_headers, db, two_stage, monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "hybrid")
    monkeypatch.setattr(settings, "agent_review", "all")
    r = post(client, auth_headers, 1, *STORY[0][:2], 0)  # conftest DownLLM: every call fails
    assert r["event_id"]
    review.flush_background()
    job = db.scalar(select(EventJob))
    assert job.state == "done" and db.query(Alert).filter(Alert.reason == "review_upgrade").count() == 0


def test_queue_full_leaves_job_queued_and_sweeper_processes_it(client, auth_headers, db, two_stage):
    review.configure(workers=1, queue_size=0, deferred=True)
    r = client.post("/v1/events", headers=auth_headers, json={
        "device_event_id": "q1", "source": "sms", "ts": T0.isoformat(), "sender": "+919800000301",
        "text": "hello", "triage_score": 0.1})
    assert r.status_code == 200
    job = db.scalar(select(EventJob))
    assert job.state == "queued" and job.error is None and job.payload is not None
    sf = sessionmaker(bind=db.get_bind())
    assert review.sweep(sf, older_than=timedelta(minutes=5)) == 0  # still full
    review.configure(workers=1, queue_size=10, deferred=True)
    assert review.sweep(sf, older_than=timedelta(minutes=5)) == 1
    review.flush_background()
    db.expire_all()
    assert db.scalar(select(EventJob.state)) == "done"


def _flood(client, headers, n, tag):
    for i in range(n):
        client.post("/v1/events", headers=headers, json={
            "device_event_id": f"{tag}-{i}", "source": "sms", "ts": (T0 + timedelta(minutes=i)).isoformat(),
            "sender": f"+9198000{i:05d}", "text": "see you at lunch", "triage_score": 0.1})


def test_per_user_limit_and_flood_does_not_starve_others(client, register, db, two_stage):
    review.configure(workers=1, queue_size=1000, deferred=True, max_per_user=5)
    _, flooder = register()
    _, other = register()
    _flood(client, flooder, 100, "f")
    assert db.query(EventJob).filter(EventJob.state == "queued").count() == 100
    assert len(review.runner.inflight_ids()) == 5  # fair limit: the rest wait in the DB, not in memory
    _flood(client, other, 1, "o")  # the other user still gets in
    assert len(review.runner.inflight_ids()) == 6
    sf = sessionmaker(bind=db.get_bind())
    review.flush_background()
    db.expire_all()
    done_other = db.query(EventJob).filter(EventJob.state == "done").count()
    assert done_other == 6
    # each sweeper pass hands the flooder at most 5 more jobs; nothing is lost
    passes = 0
    while db.query(EventJob).filter(EventJob.state == "queued").count():
        passes += 1
        assert passes <= 20
        assert review.sweep(sf, older_than=timedelta(minutes=5)) <= 5
        review.flush_background()
        db.expire_all()
    assert passes == 19
    assert db.query(EventJob).filter(EventJob.state == "done").count() == 101


def test_sweeper_round_robins_across_users(client, register, db, two_stage):
    review.configure(workers=1, queue_size=0, deferred=True)  # every submit refused: all stay queued
    _, flooder = register()
    _, other = register()
    _flood(client, flooder, 30, "f")
    _flood(client, other, 1, "o")
    assert db.query(EventJob).filter(EventJob.state == "queued").count() == 31
    review.configure(workers=1, queue_size=3, deferred=True)  # room for 3 per pass
    sf = sessionmaker(bind=db.get_bind())
    assert review.sweep(sf, older_than=timedelta(minutes=5)) == 3
    review.flush_background()
    db.expire_all()
    states = {uid: n for uid, n in db.execute(select(EventJob.user_id, func.count(EventJob.id)).where(
        EventJob.state == "done").group_by(EventJob.user_id))}
    assert len(states) == 2 and sorted(states.values()) == [1, 2]  # the other user's single job went first round


def test_sweeps_do_not_overlap(db, monkeypatch):
    import threading
    started, release, results = threading.Event(), threading.Event(), []
    sf = sessionmaker(bind=db.get_bind())

    class Slow:
        def __call__(self):
            started.set()
            release.wait(5)
            return sf()

    t = threading.Thread(target=lambda: results.append(review.sweep(Slow(), older_than=timedelta(0))))
    t.start()
    assert started.wait(5)
    assert review.sweep(sf, older_than=timedelta(0)) == 0  # second concurrent sweep is a no-op
    release.set()
    t.join(5)
    assert results == [0]
    assert not review._sweep_lock.locked()


def test_deleted_user_job_exits_quietly(client, auth_headers, db, two_stage):
    post(client, auth_headers, 1, *STORY[0][:2], 0)
    assert client.delete("/v1/me", headers=auth_headers).status_code == 204
    review.flush_background()  # no exception
    assert db.query(EventJob).count() == 0


def test_user_check_is_not_upgraded(client, auth_headers, db, two_stage):
    for i, (snd, txt, h) in enumerate(STORY[:2]):
        post(client, auth_headers, i, snd, txt, h)
        review.flush_background()
    post(client, auth_headers, 9, *STORY[2][:2], 60, path="/v1/check")
    review.flush_background()
    assert db.query(Alert).filter(Alert.reason == "review_upgrade").count() == 0


def test_agent_review_all_runs_agent_with_daily_cap(client, auth_headers, db, two_stage, monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "hybrid")
    monkeypatch.setattr(settings, "agent_review", "all")
    monkeypatch.setattr(settings, "agent_review_daily_cap", 1)
    answer = ChatResult(content=json.dumps({"tier": "info", "headline": "ok", "next_step": "none",
                                            "why": [], "raises": []}))
    fake = FakeLLM([answer, answer, answer])
    app.dependency_overrides[get_llm] = lambda: fake
    post(client, auth_headers, 1, "+919800000401", "see you at lunch", 0)
    review.flush_background()
    post(client, auth_headers, 2, "+919800000402", "lunch moved to 2pm", 1)
    review.flush_background()
    ran = [j.agent_ran for j in db.scalars(select(EventJob).order_by(EventJob.created_at))]
    assert ran == [True, False]  # second run is beyond the per-user daily cap: embedding + rules only
    assert len(fake.calls) == 1


def test_sweeper_resumes_stale_jobs(client, auth_headers, db, two_stage):
    review.configure(workers=1, queue_size=0, deferred=True)  # the first submit is "lost"
    post(client, auth_headers, 1, *STORY[0][:2], 0)
    db.execute(update(EventJob).values(state="queued", error=None, payload={"event": None, "force": False},
                                       updated_at=datetime.now(timezone.utc) - timedelta(hours=1)))
    db.commit()
    review.configure(workers=1, queue_size=10, deferred=True)
    sf = sessionmaker(bind=db.get_bind())
    assert review.sweep(sf, older_than=timedelta(minutes=5)) == 1
    review.flush_background()
    db.expire_all()
    assert db.scalar(select(EventJob.state)) == "done"


def test_story_of_one_user_never_upgrades_another(client, register, db, two_stage):
    _, h1 = register()
    _, h2 = register()
    for i, (snd, txt, h) in enumerate(STORY[:2]):
        post(client, h1, i, snd, txt, h)
        review.flush_background()
    post(client, h2, 2, *STORY[2][:2], 60)
    review.flush_background()
    assert db.query(Alert).filter(Alert.reason == "review_upgrade").count() == 0
    assert db.query(Edge).filter(Edge.relation == "similar").count() == 1  # only user 1's own pair


def test_concurrent_jobs_cannot_exceed_agent_cap(client, register, db, two_stage, monkeypatch):
    import threading
    import time
    monkeypatch.setattr(settings, "agent_mode", "hybrid")
    monkeypatch.setattr(settings, "agent_review", "all")
    monkeypatch.setattr(settings, "agent_review_daily_cap", 3)
    _, h = register()
    ids = [post(client, h, i, f"+9198000099{i:02d}", "see you at lunch", i)["event_id"] for i in range(10)]
    answer = ChatResult(content=json.dumps({"tier": "info", "headline": "ok", "next_step": "none",
                                            "why": [], "raises": []}))
    lock, calls = threading.Lock(), []

    class Spy:
        def chat(self, *a, **k):
            time.sleep(0.2)  # widen the race window
            with lock:
                calls.append(1)
            return answer

        def embed(self, texts):
            return FakeLLM().embed(texts)

    spy, sf = Spy(), sessionmaker(bind=db.get_bind())
    db.commit()
    threads = [threading.Thread(target=review.run_job, args=(sf, e), kwargs={"llm": spy}) for e in ids]
    [t.start() for t in threads]
    [t.join(60) for t in threads]
    db.expire_all()
    assert len(calls) == 3
    assert db.query(EventJob).filter(EventJob.agent_ran.is_(True)).count() == 3
    assert db.query(EventJob).filter(EventJob.state == "done").count() == 10


def test_failed_agent_run_keeps_its_slot(client, register, db, two_stage, monkeypatch):
    monkeypatch.setattr(settings, "agent_mode", "hybrid")
    monkeypatch.setattr(settings, "agent_review", "all")
    monkeypatch.setattr(settings, "agent_review_daily_cap", 1)
    _, h = register()
    e1 = post(client, h, 1, "+919800009901", "see you at lunch", 0)["event_id"]
    e2 = post(client, h, 2, "+919800009902", "lunch moved", 1)["event_id"]
    boom = FakeLLM([RuntimeError("boom")])
    sf = sessionmaker(bind=db.get_bind())
    db.commit()
    review.run_job(sf, e1, llm=boom)
    db.expire_all()
    j1 = db.scalar(select(EventJob).where(EventJob.event_id == e1))
    assert j1.agent_ran is True  # reserved before the call; spent although it failed
    nxt = FakeLLM([])
    review.run_job(sf, e2, llm=nxt)  # cap reached: no agent call (an empty script would assert)
    assert nxt.calls == []
