"""Pluggable first-pass classifier: an extra ESCALATION trigger for the agent, never a verdict.

    text (already redacted) -> Classifier.classify -> Classification(scam_prob, ...)
    orchestrator: escalate (send the event to the agent) if scam_prob >= settings.classifier_threshold

Hard rules (enforced by the orchestrator and by `classify_safe`):
- It can only ADD an escalation. It never lowers or suppresses a rules tier, an alert or the existing
  triage gate; the rules floor stays authoritative.
- It sees only the redacted text (never the raw message, sender or entities).
- Missing / slow / failing / absent dependency -> None -> behaviour is exactly the rules-only behaviour.
  `classify_safe` never raises and never waits longer than the timeout.
- Default `CLASSIFIER=rules`: this module is not even consulted, nothing optional is imported.
- Self-hosted: the Laya backend runs the open weights in-process on CPU; text never leaves the host.
- Logs mention the classifier TYPE only, never message content.
"""

from __future__ import annotations

import logging
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

log = logging.getLogger(__name__)

SCAM_QUESTION = ("Is this message likely a scam, phishing, impersonation or payment/OTP fraud attempt?")
# Laya's English checkpoint reads 512 tokens, the multilingual one 1024. Keep a conservative character
# budget (Devanagari costs more tokens per character than English): first HEAD chars + last TAIL chars.
CLIP_HEAD = 700
CLIP_TAIL = 400


@dataclass(frozen=True)
class Classification:
    scam_prob: float  # 0..1
    label: str  # "scam" | "ok"
    source: str  # classifier type, e.g. "laya:multilingual"
    latency_ms: float


@runtime_checkable
class Classifier(Protocol):
    def classify(self, text: str, lang: str | None) -> Classification | None: ...


def clip_text(text: str, head: int = CLIP_HEAD, tail: int = CLIP_TAIL) -> str:
    """Keep the first `head` and last `tail` characters of an over-long message (the opening names the
    sender / pretext, the end carries the ask or the link)."""
    if len(text) <= head + tail:
        return text
    return text[:head].rstrip() + " ... " + text[-tail:].lstrip()


class RulesClassifier:
    """Default: the current gate's semantics behind the Classifier interface. The pipeline's gate is the
    client's `triage_score`, which is not a function of the text, so by itself this classifier has no
    opinion (None) unless given a scorer. The orchestrator never consults it when CLASSIFIER=rules."""

    source = "rules"

    def __init__(self, scorer=None):
        self._scorer = scorer  # optional callable(text, lang) -> float in 0..1 (e.g. a keyword triage)

    def classify(self, text: str, lang: str | None) -> Classification | None:
        if self._scorer is None:
            return None
        t0 = time.perf_counter()
        p = float(self._scorer(text, lang))
        return Classification(p, "scam" if p >= 0.5 else "ok", self.source, (time.perf_counter() - t0) * 1000)


class LayaClassifier:
    """Laya (Apache-2.0, open weights, non-autoregressive) zero-shot: one typed "noul" question
    (probability that the answer is yes) answered in a single forward pass on CPU.
    `laya` and `torch` are imported lazily (uv extra `laya`); the checkpoint is loaded once, on first
    use, under a lock. Failure to import or load raises from `classify` and is turned into None by
    `classify_safe`."""

    def __init__(self, model: str = "multilingual", device: str = "cpu"):
        self.model = model  # "multilingual" | "english" | "auto" (Laya's router picks per message)
        self.device = device
        self.source = f"laya:{model}"
        self._router: Any = None
        self._lock = threading.Lock()

    def _load(self):
        if self._router is None:
            from laya import Router  # lazy: optional dependency

            self._router = Router(device=self.device, max_loaded=2)
        return self._router

    def questions(self) -> dict:
        return {"scam": {"type": "noul", "instructions": SCAM_QUESTION},
                "kind": {"type": "choice", "instructions": "What kind of message is this?",
                         "criteria": {"scam": "scam, phishing, impersonation, OTP or payment fraud",
                                      "legit": "genuine bank, service or personal message"}}}

    def raw(self, text: str, lang: str | None = None, questions: dict | None = None) -> dict:
        with self._lock:  # the model is not shared across threads; also serialises first load
            router = self._load()
            kw = {} if self.model == "auto" else {"model": self.model}
            return router.predict(clip_text(text), questions or self.questions(), **kw)["answers"]

    def warmup(self) -> None:
        self.raw("hello", None)

    def classify(self, text: str, lang: str | None) -> Classification | None:
        if not text or not text.strip():
            return None
        t0 = time.perf_counter()
        ans = self.raw(text, lang, {"scam": self.questions()["scam"]})
        p = max(0.0, min(1.0, float(ans["scam"]["noul"])))
        return Classification(p, "scam" if p >= 0.5 else "ok", self.source, (time.perf_counter() - t0) * 1000)


# ---------------------------------------------------------------- never-raise, never-block wrapper
class _Guard:
    """One worker thread per classifier. If a previous call is still running (it overran its timeout,
    or the model is still loading) new calls are skipped immediately instead of queueing up."""

    def __init__(self, clf: Classifier):
        self.clf = clf
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="classifier")
        self.inflight: Future | None = None
        self.lock = threading.Lock()


_guards: dict[int, _Guard] = {}
_guards_lock = threading.Lock()


def _guard_for(clf: Classifier) -> _Guard:
    with _guards_lock:
        g = _guards.get(id(clf))
        if g is None or g.clf is not clf:
            g = _guards[id(clf)] = _Guard(clf)
        return g


def classify_safe(clf: Classifier | None, text: str | None, lang: str | None,
                  timeout_ms: float = 300) -> Classification | None:
    """clf.classify with a hard wall-clock timeout. None on any problem (timeout, busy, exception, bad
    result). Never raises."""
    if clf is None or not text:
        return None
    kind = type(clf).__name__
    try:
        g = _guard_for(clf)
        with g.lock:
            if g.inflight is not None and not g.inflight.done():
                log.warning("classifier %s busy, skipped", kind)
                return None
            fut = g.inflight = g.pool.submit(clf.classify, text, lang)
        res = fut.result(timeout=max(0.0, timeout_ms) / 1000)
    except FutureTimeout:
        log.warning("classifier %s timed out after %s ms, skipped", kind, timeout_ms)
        return None
    except Exception:
        log.warning("classifier %s failed, skipped", kind, exc_info=False)
        return None
    try:
        if res is None or not (0.0 <= float(res.scam_prob) <= 1.0):
            return None
    except Exception:
        return None
    return res


# ---------------------------------------------------------------- factory
_cache: dict[tuple, Classifier] = {}
_cache_lock = threading.Lock()


def get_classifier(settings) -> Classifier | None:
    """The classifier for `settings.classifier`: "rules" -> None (off: no extra trigger), "laya" -> a
    cached LayaClassifier (nothing is imported or loaded until its first use). Unknown value -> None."""
    kind = (getattr(settings, "classifier", "rules") or "rules").strip().lower()
    if kind == "rules":
        return None
    if kind == "laya":
        key = ("laya", settings.laya_model)
        with _cache_lock:
            if key not in _cache:
                _cache[key] = LayaClassifier(model=settings.laya_model)
            return _cache[key]
    log.warning("unknown CLASSIFIER type %r, classifier disabled", kind)
    return None


def classifier_enabled(settings) -> bool:
    return get_classifier(settings) is not None
