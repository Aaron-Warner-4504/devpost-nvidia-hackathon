"""Embedding providers (app/pipeline/embeddings.py): selection, safety wrapper, local lazy loading."""
import logging
import math
import sys
import threading
import time
import types

import pytest

from app.config import settings
from app.llm import FakeLLM
from app.pipeline import embeddings
from app.pipeline.embeddings import (FakeEmbeddings, LocalEmbeddings, TokenFactoryEmbeddings, embed_safe,
                                     fit_dim, get_provider, provider_name, semantic_enabled)


def cfg(**kw):
    base = {"embedding_provider": "", "semantic_memory": None, "token_factory_api_key": "",
            "embed_model": ""}
    return settings.model_copy(update={**base, **kw})


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)))


# ---------------------------------------------------------------- selection / safe defaults
def test_default_without_key_is_off():
    s = cfg()
    assert provider_name(s) == "none"
    assert get_provider(s) is None
    assert semantic_enabled(s) is False


def test_auto_token_factory_when_key_and_model():
    s = cfg(token_factory_api_key="k", embed_model="m")
    assert provider_name(s) == "token_factory"
    assert semantic_enabled(s) is True
    assert isinstance(get_provider(s), TokenFactoryEmbeddings)


def test_token_factory_without_key_is_none():
    assert provider_name(cfg(embedding_provider="token_factory")) == "none"


def test_explicit_off_and_unknown():
    assert semantic_enabled(cfg(embedding_provider="fake", semantic_memory=False)) is False
    assert semantic_enabled(cfg(embedding_provider="fake")) is True
    assert provider_name(cfg(embedding_provider="bogus")) == "none"
    assert provider_name(cfg(embedding_provider="none", token_factory_api_key="k", embed_model="m")) == "none"


def test_local_missing_extra_is_none(monkeypatch):
    import importlib.util
    real = importlib.util.find_spec
    monkeypatch.setattr(importlib.util, "find_spec",
                        lambda n, *a: None if n == "sentence_transformers" else real(n, *a))
    assert provider_name(cfg(embedding_provider="local")) == "none"
    assert semantic_enabled(cfg(embedding_provider="local")) is False


def test_provider_cached_once():
    s = cfg(embedding_provider="fake")
    assert get_provider(s) is get_provider(s)


# ---------------------------------------------------------------- fake provider
def test_fake_is_deterministic_unit_and_lexical():
    f = FakeEmbeddings(64)
    a, b, c = f.embed(["pay the registration fee today", "pay registration fee today please",
                       "did you reach office"])
    assert f.embed(["pay the registration fee today"])[0] == a
    assert abs(math.sqrt(sum(x * x for x in a)) - 1) < 1e-9
    assert cos(a, b) > cos(a, c)


def test_token_factory_uses_llm_embed_and_normalises():
    p = TokenFactoryEmbeddings(llm=FakeLLM(embed_dim=8), model="m")
    v = p.embed(["x"])[0]
    assert len(v) == 8 and abs(math.sqrt(sum(x * x for x in v)) - 1) < 1e-9
    assert p.name == "tf:m"


# ---------------------------------------------------------------- embed_safe
class Rec:
    name = "rec"

    def __init__(self, dim=4, fail=None, sleep=0.0):
        self.seen, self.dim, self.fail, self.sleep = [], dim, fail, sleep

    def embed(self, texts):
        self.seen.extend(texts)
        if self.sleep:
            time.sleep(self.sleep)
        if self.fail:
            raise self.fail
        return [[1.0] + [0.0] * (self.dim - 1) for _ in texts]


def test_embed_safe_redacts_before_sending():
    p = Rec()
    embed_safe(p, ["Your OTP is 482913, card 4111 1111 1111 1111"], dim=4)
    assert "482913" not in p.seen[0] and "4111" not in p.seen[0]


def test_embed_safe_pads_and_rejects_wider():
    assert embed_safe(Rec(dim=3), ["a"], dim=5) == [[1.0, 0.0, 0.0, 0.0, 0.0]]
    assert embed_safe(Rec(dim=6), ["a"], dim=5) is None
    assert fit_dim([1.0, 2.0], 2) == [1.0, 2.0]


def test_embed_safe_failure_and_timeout_never_raise(caplog):
    caplog.set_level(logging.INFO)
    secret = "send 5000 to fraudster@ybl now"
    assert embed_safe(Rec(fail=RuntimeError(secret)), [secret], dim=4) is None
    t = time.monotonic()
    assert embed_safe(Rec(sleep=1.0), [secret], dim=4, timeout=0.1) is None
    assert time.monotonic() - t < 0.8
    assert embed_safe(None, ["x"], dim=4) is None
    assert "fraudster" not in caplog.text and "5000" not in caplog.text


def test_embed_safe_rejects_bad_shapes():
    class Bad:
        name = "bad"

        def embed(self, texts):
            return [[float("nan")] * 4]

    assert embed_safe(Bad(), ["x"], dim=4) is None


# ---------------------------------------------------------------- local provider: lazy, loaded once
def test_local_lazy_import_and_single_load(monkeypatch):
    loads = []

    class FakeST:
        def __init__(self, name, device=None):
            loads.append(name)
            time.sleep(0.05)

        def encode(self, texts, normalize_embeddings=True, show_progress_bar=False):
            return [[0.6, 0.8] for _ in texts]

    monkeypatch.setitem(sys.modules, "sentence_transformers", types.SimpleNamespace(SentenceTransformer=FakeST))
    p = LocalEmbeddings("some/model")
    assert loads == []  # nothing loaded at construction
    out = []
    ts = [threading.Thread(target=lambda: out.append(p.embed(["a", "b"]))) for _ in range(5)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert loads == ["some/model"] and len(out) == 5 and out[0] == [[0.6, 0.8], [0.6, 0.8]]


@pytest.mark.skipif(not embeddings.available("local") or not __import__("os").environ.get("RUN_LOCAL_EMBED"),
                    reason="set RUN_LOCAL_EMBED=1 with the `semantic` extra and the model cached")
def test_local_model_multilingual_smoke():
    p = LocalEmbeddings(settings.local_embed_model)
    en, hi, other = p.embed(["I got your number from a friend, join our stock tips group",
                             "मुझे आपका नंबर एक दोस्त से मिला, हमारे शेयर टिप्स ग्रुप से जुड़ें",
                             "Your electricity bill has been paid successfully"])
    assert cos(en, hi) > cos(en, other)
