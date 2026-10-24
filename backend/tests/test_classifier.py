"""Pluggable classifier gate: only ever ADDS an escalation; failures degrade to the rules-only behaviour.
No network, no Laya/torch needed (FakeClassifier)."""
import sys
import time
from datetime import datetime, timezone

import pytest

from app.config import Settings, settings
from app.llm import FakeLLM
from app.models import Assessment, User
from app.pipeline import classifier as C
from app.pipeline.orchestrator import PipelineDeps, process_event
from app.schemas import EventIn
from sqlalchemy import select

from tests.conftest import offline_deps

BENIGN = "Your order has shipped and will arrive on Friday. Thanks for shopping with us."
SCAM_RULES = ("Your parcel is held. Pay Rs 25,000 penalty immediately to customs.clear@ybl or face arrest. "
              "Do not tell anyone.")


class FakeClassifier:
    def __init__(self, p=0.9, exc=None, delay=0.0, result="auto"):
        self.p, self.exc, self.delay, self.result, self.calls = p, exc, delay, result, []

    def classify(self, text, lang):
        self.calls.append(text)
        if self.delay:
            time.sleep(self.delay)
        if self.exc:
            raise self.exc
        if self.result is None:
            return None
        return C.Classification(self.p, "scam" if self.p >= .5 else "ok", "fake", 1.0)


def deps(clf=None, mode="laya", threshold=0.5, timeout=300, agent_mode="hybrid"):
    d = offline_deps()
    d.settings = settings.model_copy(update={"classifier": mode, "classifier_threshold": threshold,
                                             "classifier_timeout_ms": timeout, "agent_mode": agent_mode})
    d.classifier = clf
    return d


def run(db, text, d, triage=0.1, n=[0]):
    n[0] += 1
    user = User()
    db.add(user)
    db.commit()
    ev = EventIn(device_event_id=f"c{n[0]}", source="sms", ts=datetime(2026, 3, 1, 9, tzinfo=timezone.utc),
                 sender="+917700112233", text=text, triage_score=triage, lang="en")
    res = process_event(db, user, ev, llm=FakeLLM(), deps=d)
    asm = db.scalar(select(Assessment).where(Assessment.event_id == res.event_id))
    return asm, res


def test_default_is_rules_and_off():
    assert Settings(database_url="x").classifier == "rules"
    assert C.get_classifier(Settings(database_url="x")) is None
    assert C.get_classifier(Settings(database_url="x", classifier="bogus")) is None


def test_rules_mode_never_consults_classifier(db):
    fake = FakeClassifier(0.99)
    asm, _ = run(db, BENIGN, deps(fake, mode="rules"))
    assert fake.calls == []  # even when injected: settings.classifier decides
    assert asm.payload["mode"] == "rules" and "classifier" not in asm.payload



def test_threshold_boundary_escalates_and_is_recorded(db):
    asm, _ = run(db, BENIGN, deps(FakeClassifier(0.5), threshold=0.5))
    assert asm.payload["mode"] == "hybrid" and asm.payload["classifier"]["escalated"] is True
    assert asm.payload["classifier"]["scam_prob"] == 0.5
    below, _ = run(db, BENIGN, deps(FakeClassifier(0.4999), threshold=0.5))
    assert below.payload["mode"] == "rules"
    assert below.payload["classifier"]["escalated"] is False
    assert below.tier == "info"


@pytest.mark.parametrize("clf", [
    FakeClassifier(exc=RuntimeError("boom")),
    FakeClassifier(result=None),
    FakeClassifier(0.99, delay=0.6),
    None,  # settings say laya but the library is missing -> get_classifier -> lazy import fails -> None
])
def test_error_none_timeout_identical_to_rules(db, clf, monkeypatch):
    if clf is None:
        monkeypatch.setitem(sys.modules, "laya", None)  # import laya -> ImportError
    off = run(db, BENIGN, deps(None, mode="rules"))[0]
    on = run(db, BENIGN, deps(clf, timeout=100))[0]
    assert (on.tier, on.signals, on.explanation, on.degraded) == (off.tier, off.signals, off.explanation,
                                                                  off.degraded)
    assert on.payload["mode"] == "rules" and "classifier" not in on.payload


def test_never_lowers_a_rules_tier(db):
    off = run(db, SCAM_RULES, deps(None, mode="rules"))[0]
    assert off.tier in ("high", "critical")
    for p in (0.0, 0.1, 0.99):
        on = run(db, SCAM_RULES, deps(FakeClassifier(p)))[0]
        assert on.tier == off.tier
        assert "classifier" not in on.payload  # already escalated by rules: classifier not even needed


def test_does_not_override_triage_gate(db):
    clf = FakeClassifier(0.0)  # classifier says "benign" but triage >= 0.5 escalates anyway
    asm, _ = run(db, BENIGN, deps(clf), triage=0.9)
    assert asm.payload["mode"] == "hybrid"


def test_classify_safe_never_raises_and_skips_when_busy():
    slow = FakeClassifier(0.9, delay=0.4)
    t0 = time.monotonic()
    assert C.classify_safe(slow, "x", None, timeout_ms=50) is None
    assert time.monotonic() - t0 < 0.3
    assert C.classify_safe(slow, "x", None, timeout_ms=50) is None  # still busy: skipped at once
    assert len(slow.calls) == 1
    time.sleep(0.5)
    assert C.classify_safe(slow, "x", None, timeout_ms=500).scam_prob == 0.9
    assert C.classify_safe(FakeClassifier(1.5), "x", None).__class__ is type(None)  # out of range
    assert C.classify_safe(None, "x", None) is None and C.classify_safe(FakeClassifier(), "", None) is None


def test_clip_text_keeps_head_and_tail():
    t = "A" * 3000 + "END"
    c = C.clip_text(t)
    assert len(c) < 1200 and c.startswith("AAAA") and c.endswith("END")
    assert C.clip_text("short") == "short"


def test_rules_classifier_default_has_no_opinion_without_scorer():
    assert C.RulesClassifier().classify("hello", None) is None
    assert C.RulesClassifier(lambda t, l: 0.7).classify("hello", None).label == "scam"
