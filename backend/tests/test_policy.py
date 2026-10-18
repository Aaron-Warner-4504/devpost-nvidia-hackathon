"""Unit tests: alert policy (dedupe / cap / tiers) and HistoryCtx building from memory."""
from datetime import datetime, timedelta, timezone

import pytest

from app.models import Alert, Assessment, Device, Event, Override, User
from app.pipeline import policy
from app.pipeline.extract import Entity
from app.pipeline.memory import link, store_event
from app.pipeline.orchestrator import build_history_ctx, load_overrides, sender_key_for
from app.pipeline.policy import AlertCandidate, decide, dedupe_key
from app.schemas import CallMeta, EventIn

T0 = datetime(2026, 5, 1, 12, 0, tzinfo=timezone.utc)
P = Entity("phone", "+919876543210")
CLAIM = Entity("claim", "parcel_held")


@pytest.fixture
def user(db, register):
    dev, _ = register()
    return db.get(User, db.get(Device, dev).user_id)


_n = iter(range(10**6))


def prior(db, user, tier, key, ts, shown=True, interrupt=None, reason=None):
    """A previously assessed event with an alert row (shown or suppressed)."""
    e = Event(user_id=user.id, device_event_id=f"x{next(_n)}", source="sms", ts=ts, redacted_text="t", hash="h")
    db.add(e)
    db.flush()
    a = Assessment(user_id=user.id, event_id=e.id, tier=tier, signals=[], explanation="", model="",
                   latency=0.0, degraded=False)
    db.add(a)
    db.flush()
    db.add(Alert(user_id=user.id, assessment_id=a.id, shown_at=ts if shown else None, dedupe_key=key,
                 interrupt=(tier in ("high", "critical")) if interrupt is None else interrupt, reason=reason))
    db.flush()
    return e.id


def cand(tier, key="k", ts=T0, event_id="evt_new"):
    return AlertCandidate(event_id=event_id, tier=tier, dedupe_key=key, ts=ts)


# ---------------------------------------------------------------- tiers
@pytest.mark.parametrize("tier,show,interrupt", [
    ("info", False, False), ("caution", True, False), ("high", True, True), ("critical", True, True)])
def test_tier_policy(db, user, tier, show, interrupt):
    d = decide(db, user, cand(tier), always=False)
    assert (d.show, d.interrupt) == (show, interrupt)


@pytest.mark.parametrize("tier", ["info", "caution", "high", "critical"])
def test_always_shows_any_tier_never_suppressed(db, user, tier):
    for i in range(6):
        prior(db, user, "high", "k", T0 - timedelta(minutes=i + 1))
    prior(db, user, tier, "k", T0 - timedelta(minutes=1))
    d = decide(db, user, cand(tier), always=True)
    assert d.show and not d.interrupt


# ---------------------------------------------------------------- dedupe
def test_same_key_same_tier_within_6h_suppressed(db, user):
    prior(db, user, "high", "k", T0 - timedelta(hours=5, minutes=59))
    d = decide(db, user, cand("high"), always=False)
    assert not d.show and not d.interrupt and d.reason == "duplicate"
    assert decide(db, user, cand("caution"), always=False).show is False  # lower tier also suppressed


def test_dedupe_window_and_key(db, user):
    prior(db, user, "high", "k", T0 - timedelta(hours=6, minutes=1))
    assert decide(db, user, cand("high"), always=False).show
    prior(db, user, "high", "other", T0 - timedelta(hours=1))
    assert decide(db, user, cand("high"), always=False).show


def test_suppressed_prior_does_not_count(db, user):
    prior(db, user, "high", "k", T0 - timedelta(hours=1), shown=False)
    assert decide(db, user, cand("high"), always=False).show


def test_tier_increase_passes_dedupe(db, user):
    prior(db, user, "caution", "k", T0 - timedelta(hours=1))
    d = decide(db, user, cand("high"), always=False)
    assert d.show and d.interrupt


def test_dedupe_window_is_one_sided_and_strict(db, user):
    prior(db, user, "high", "k", T0 - timedelta(hours=6))  # exactly 6h earlier: outside (ts - 6h < prior)
    assert decide(db, user, cand("high"), always=False).show
    prior(db, user, "high", "k", T0 + timedelta(hours=1))  # a LATER alert never dedupes an earlier one
    assert decide(db, user, cand("high"), always=False).show
    prior(db, user, "high", "k", T0)  # same instant: inside (prior_ts <= ts)
    assert not decide(db, user, cand("high"), always=False).show


# ---------------------------------------------------------------- critical flood guard / ceiling
def test_critical_flood_guard_boundary(db, user):
    prior(db, user, "critical", "k", T0 - timedelta(minutes=9, seconds=59))
    d = decide(db, user, cand("critical"), always=False)
    assert (d.show, d.interrupt, d.reason) == (False, False, "critical_flood")
    assert decide(db, user, cand("critical", key="other"), always=False).show  # new key passes


def test_critical_flood_exactly_10_minutes_passes(db, user):
    prior(db, user, "critical", "k", T0 - timedelta(minutes=10))  # ts - 10min < prior_ts is strict
    d = decide(db, user, cand("critical"), always=False)
    assert d.show and d.interrupt


def test_critical_flood_ignores_non_critical_and_suppressed(db, user):
    prior(db, user, "high", "k", T0 - timedelta(minutes=1))
    prior(db, user, "critical", "k", T0 - timedelta(minutes=2), shown=False)
    assert decide(db, user, cand("critical"), always=False).show


def test_critical_daily_ceiling(db, user):
    for i in range(policy.CRITICAL_DAILY_CEILING):
        prior(db, user, "critical", f"k{i}", T0 - timedelta(hours=23, minutes=59) + timedelta(minutes=i))
    d = decide(db, user, cand("critical", key="new"), always=False)
    assert (d.show, d.reason) == (False, "critical_ceiling")
    # once the oldest one is 24h old the window has room again
    assert decide(db, user, cand("critical", key="new", ts=T0 + timedelta(minutes=1)), always=False).show


# ---------------------------------------------------------------- /v1/check alerts are not organic
def test_user_check_alerts_ignored_by_dedupe_cap_and_flood(db, user):
    prior(db, user, "high", "k", T0 - timedelta(minutes=5), interrupt=False, reason="user_check")
    prior(db, user, "critical", "c", T0 - timedelta(minutes=5), interrupt=False, reason="user_check")
    for i in range(5):
        prior(db, user, "high", f"x{i}", T0 - timedelta(minutes=10 + i), interrupt=True, reason="user_check")
    assert decide(db, user, cand("high"), always=False).show
    assert decide(db, user, cand("critical", key="c"), always=False).show
    assert decide(db, user, cand("high", key="fresh"), always=False).show


# ---------------------------------------------------------------- bad device clock
def test_future_clock_clamped_for_dedupe(db, user):
    prior(db, user, "high", "k", T0 + timedelta(days=10))
    d = decide(db, user, cand("high", ts=T0 + timedelta(days=20)), always=False, now=T0)
    assert d.reason == "duplicate"  # both clamp to now + 1 day
    assert decide(db, user, cand("high", ts=T0 + timedelta(days=20)), always=False,
                  now=T0 + timedelta(days=30)).show  # with a real clock they are 10 days apart


def test_future_clock_clamped_for_cap(db, user):
    for i in range(5):
        prior(db, user, "high", f"k{i}", T0 + timedelta(days=100 * (i + 1)))
    d = decide(db, user, cand("high", key="new", ts=T0 + timedelta(days=900)), always=False, now=T0)
    assert d.reason == "daily_cap"


def test_other_users_alerts_do_not_dedupe(db, user, register):
    dev, _ = register()
    other = db.get(User, db.get(Device, dev).user_id)
    prior(db, other, "high", "k", T0 - timedelta(hours=1))
    assert decide(db, user, cand("high"), always=False).show


# ---------------------------------------------------------------- daily cap
def test_daily_cap_applies_to_high_only(db, user):
    for i in range(5):
        prior(db, user, "high", f"k{i}", T0 - timedelta(hours=i + 1))
    d = decide(db, user, cand("high", key="new"), always=False)
    assert not d.show and d.reason == "daily_cap"
    assert decide(db, user, cand("critical", key="new"), always=False).show
    assert decide(db, user, cand("caution", key="new"), always=False).show  # non-interrupting


def test_daily_cap_is_rolling_24h(db, user):
    for i in range(5):
        prior(db, user, "high", f"k{i}", T0 - timedelta(hours=19 + i))  # 19..23h ago
    assert not decide(db, user, cand("high", key="new"), always=False).show
    assert decide(db, user, cand("high", key="new", ts=T0 + timedelta(hours=1)), always=False).show


def test_dedupe_key_ignores_amounts_and_order():
    a = [P, CLAIM, Entity("amount", "INR:500")]
    b = [CLAIM, P, Entity("amount", "INR:900")]
    assert dedupe_key(a, "h1") == dedupe_key(b, "h2")
    assert dedupe_key([], "h1") != dedupe_key([], "h2")  # entity-less: falls back to the text hash
    assert dedupe_key([P], "h") != dedupe_key([P, CLAIM], "h")


def test_constants():
    assert policy.DEDUPE_WINDOW == timedelta(hours=6)
    assert policy.DAILY_INTERRUPT_CAP == 5


# ---------------------------------------------------------------- HistoryCtx building
def ev(i, source, ts, sender=None, call=None):
    return EventIn(device_event_id=i, source=source, ts=ts, sender=sender, call=call, triage_score=0.1)


def ingest(db, user, e, ents, text="t"):
    s = store_event(db, user, e, text, ents, sender_key=sender_key_for(e.sender, e.call))
    link(db, user, s, ents)
    return s


def test_build_history_ctx(db, user, register):
    other_dev, _ = register()
    other = db.get(User, db.get(Device, other_dev).user_id)
    s1 = ingest(db, user, ev("1", "sms", T0, sender="+917700112233"), [P, CLAIM, Entity("phone", "+917700112233")])
    ingest(db, user, ev("future", "sms", T0 + timedelta(days=5)), [P])
    ingest(db, other, ev("o", "sms", T0), [P, Entity("domain", "evil.xyz")])
    call = CallMeta(number="+919876543210", duration_s=240, is_contact=False, direction="in")
    cur = ev("2", "call", T0 + timedelta(days=1), call=call)
    s2 = ingest(db, user, cur, [P])
    ctx = build_history_ctx(db, user, event_id=s2.id, now=cur.ts, entities=[P],
                            sender_key=sender_key_for(cur.sender, cur.call), origin=[P])
    assert ctx.now == cur.ts
    assert [h.event_id for h in ctx.events_by_entity[P]] == [s1.id]  # not current, not future, not other user
    assert CLAIM in ctx.related[P] and Entity("domain", "evil.xyz") not in ctx.related[P]
    assert ctx.sender_seen_before is True  # the caller's number appeared before
    assert ctx.domain_info is None and ctx.link_reports == {}


def test_sender_seen_before_by_header(db, user):
    org = Entity("org", "hdfc")
    ingest(db, user, ev("1", "sms", T0, sender="VM-HDFCBK"), [org])
    cur = ev("2", "sms", T0 + timedelta(hours=1), sender="AD-HDFCBK")
    s = ingest(db, user, cur, [org])
    ctx = build_history_ctx(db, user, event_id=s.id, now=cur.ts, entities=[org],
                            sender_key=sender_key_for(cur.sender, None))
    assert ctx.sender_seen_before is True
    first = ev("3", "sms", T0 - timedelta(hours=1), sender="JM-NEWSND")
    s = ingest(db, user, first, [])
    assert build_history_ctx(db, user, event_id=s.id, now=first.ts, entities=[],
                             sender_key=sender_key_for(first.sender, None)).sender_seen_before is False


def test_load_overrides(db, user):
    db.add(Override(user_id=user.id, kind="safe", type="phone", canonical="+919811122233"))
    db.add(Override(user_id=user.id, kind="known_bad", type="domain", canonical="evil.xyz"))
    db.flush()
    ov = load_overrides(db, user)
    assert ov.safe == frozenset({Entity("phone", "+919811122233")})
    assert ov.known_bad == frozenset({Entity("domain", "evil.xyz")})
