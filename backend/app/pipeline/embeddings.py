"""Sentence-embedding providers for semantic memory (docs/memory.md).

    embed(texts) -> list[list[float]]     (one unit-length vector per text)

Providers:
- `token_factory`: the hosted embedding model behind `llm.embed` (Nebius Token Factory, EMBED_MODEL).
- `local`: a small multilingual sentence-transformers model run in-process on CPU (uv extra `semantic`;
  default sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2, Apache-2.0, 384-d). Imported
  lazily, loaded once under a lock; text never leaves the host.
- `fake`: deterministic hashed bag of words/char-trigrams (unit tests and offline eval fallback). It is
  lexical, NOT semantic.

Rules every caller relies on:
- Only REDACTED text is ever embedded (`embed_safe` redacts again, defence in depth).
- `embed_safe` never raises and never waits longer than the timeout: any failure -> None, and the
  caller simply skips semantic linking. Logs carry the provider name and exception type only.
- `get_provider` returns None when no provider is usable (no key / extra missing / `none`), so the
  safe default with nothing configured is "semantic memory off".
- Vectors are fitted to the pgvector column width (`settings.embed_dim`): a shorter vector is
  zero-padded (cosine similarity is unchanged). The token_factory provider truncates a longer vector to
  its first `dim` values (Matryoshka models) and re-normalises it; any other longer vector is rejected.
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from typing import Protocol, runtime_checkable

from app.pipeline.redact import redact

log = logging.getLogger(__name__)
MAX_CHARS = 1000  # per text sent to a provider (the local model reads ~128 tokens anyway)


@runtime_checkable
class EmbeddingProvider(Protocol):
    name: str  # stored with each vector: vectors of different models are never compared

    def embed(self, texts: list[str]) -> list[list[float]]: ...


def _unit(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v] if n > 0 else v


class FakeEmbeddings:
    """Deterministic, offline: signed feature hashing of lower-cased words and character trigrams.
    Similar wording -> similar vectors; meaning across languages is NOT captured."""

    def __init__(self, dim: int = 384):
        self.dim = dim
        self.name = f"fake-{dim}"

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            v = [0.0] * self.dim
            words = re.findall(r"\w+", (t or "").lower())
            feats = words + [w[i:i + 3] for w in words if len(w) > 3 for i in range(len(w) - 2)]
            for f in feats:
                h = hashlib.blake2b(f.encode(), digest_size=8).digest()
                v[int.from_bytes(h[:4], "big") % self.dim] += 1.0 if h[4] & 1 else -1.0
            out.append(_unit(v))
        return out


EMBED_BATCH = 16  # max texts per request
_dims_rejected: set[str] = set()  # models whose server refused `dimensions` (per process)


def reset_dimension_memory() -> None:
    _dims_rejected.clear()


def shape_vector(v, dim: int) -> list[float]:
    """Finite, non-zero float vector fitted to `dim`: longer -> first `dim` values, L2-normalised (cosine
    stays meaningful for Matryoshka models); shorter -> unit-normalised (embed_safe zero-pads). Raises ValueError."""
    f = [float(x) for x in v]
    if not f or not all(math.isfinite(x) for x in f):
        raise ValueError("non-finite")
    if len(f) > dim:
        f = f[:dim]
    n = math.sqrt(sum(x * x for x in f))
    if not (n > 1e-12) or not math.isfinite(n):
        raise ValueError("zero")
    return [x / n for x in f]


class TokenFactoryEmbeddings:
    """Hosted embeddings via the OpenAI-compatible Token Factory client (`llm.embed`). Sends
    `dimensions=dim` when allowed (falls back once if rejected), batches, and always shapes the result."""

    def __init__(self, llm=None, model: str = "", dim: int | None = None, send_dimensions: bool | None = None,
                 timeout: float | None = None):
        self._llm = llm
        self._model = model
        self._dim = dim
        self._send = send_dimensions
        self._timeout = timeout
        self.name = f"tf:{model}" if model else "tf"

    def _client(self):
        if self._llm is None:
            from app.llm import get_llm
            self._llm = get_llm()
        return self._llm

    def _cfg(self):
        from app.config import settings
        dim = self._dim or settings.embed_dim
        send = settings.embed_send_dimensions if self._send is None else self._send
        timeout = settings.embed_timeout_s if self._timeout is None else self._timeout
        return dim, send, timeout

    def _request(self, batch: list[str], dim: int, send: bool, timeout: float) -> list[list[float]]:
        from app.llm import DimensionsRejected
        llm, key = self._client(), self._model or self.name
        if send and key not in _dims_rejected:
            try:
                return llm.embed(batch, dimensions=dim, timeout=timeout)
            except DimensionsRejected:
                _dims_rejected.add(key)
                log.warning("embedding server rejected `dimensions`; not sending it again (model %s)", key)
        return llm.embed(batch, timeout=timeout)

    def embed(self, texts: list[str]) -> list[list[float]]:
        dim, send, timeout = self._cfg()
        clean = [redact((t or "")[:MAX_CHARS]) for t in texts]  # defence in depth
        out: list[list[float]] = []
        for i in range(0, len(clean), EMBED_BATCH):
            batch = clean[i:i + EMBED_BATCH]
            vecs = self._request(batch, dim, send, timeout)
            if len(vecs) != len(batch):
                raise ValueError("shape")
            out.extend(shape_vector(v, dim) for v in vecs)
        return out


class LocalEmbeddings:
    """sentence-transformers model on CPU, loaded once (thread-safe) on first use."""

    def __init__(self, model: str):
        self.model_name = model
        self.name = f"local:{model}"
        self._model = None
        self._lock = threading.Lock()

    def _load(self):
        with self._lock:
            if self._model is None:
                from sentence_transformers import SentenceTransformer  # lazy: optional extra `semantic`
                self._model = SentenceTransformer(self.model_name, device="cpu")
        return self._model

    def embed(self, texts: list[str]) -> list[list[float]]:
        m = self._load()
        with self._lock:  # one encode at a time: torch on CPU already uses all cores
            vecs = m.encode(list(texts), normalize_embeddings=True, show_progress_bar=False)
        return [[float(x) for x in v] for v in vecs]


def available(name: str) -> bool:
    if name == "local":
        try:
            import importlib.util
            return importlib.util.find_spec("sentence_transformers") is not None
        except Exception:
            return False
    return name in ("fake", "token_factory")


def provider_name(settings) -> str:
    """Effective provider: explicit setting, else token_factory when a key + model exist, else none."""
    name = (settings.embedding_provider or "").strip().lower()
    if not name:
        name = "token_factory" if (settings.token_factory_api_key and settings.embed_model) else "none"
    if name == "token_factory" and not (settings.token_factory_api_key and settings.embed_model):
        return "none"
    return name if name in ("token_factory", "local", "fake") and available(name) else "none"


_cache: dict[tuple, EmbeddingProvider] = {}
_cache_lock = threading.Lock()


def _key(settings, name: str) -> tuple:
    return (name, settings.local_embed_model if name == "local" else settings.embed_model,
            settings.embed_dim, settings.embed_send_dimensions, settings.embed_timeout_s)


def register_provider(settings, provider: EmbeddingProvider) -> None:
    """Make `get_provider(settings)` return `provider` (eval tooling: cached / recording providers)."""
    with _cache_lock:
        _cache[_key(settings, provider_name(settings))] = provider


def get_provider(settings) -> EmbeddingProvider | None:
    """The configured provider (one instance per configuration, so the local model loads once)."""
    name = provider_name(settings)
    if name == "none":
        return None
    key = _key(settings, name)
    with _cache_lock:
        if key not in _cache:
            if name == "fake":
                _cache[key] = FakeEmbeddings(384)
            elif name == "local":
                _cache[key] = LocalEmbeddings(settings.local_embed_model)
            else:
                _cache[key] = TokenFactoryEmbeddings(
                    model=settings.embed_model, dim=settings.embed_dim,
                    send_dimensions=settings.embed_send_dimensions, timeout=settings.embed_timeout_s)
        return _cache[key]


def semantic_enabled(settings) -> bool:
    """SEMANTIC_MEMORY: explicit false -> off; otherwise on only when a provider is usable."""
    if settings.semantic_memory is False:
        return False
    return provider_name(settings) != "none"


def fit_dim(v: list[float], dim: int) -> list[float] | None:
    if len(v) == dim:
        return v
    if len(v) < dim:
        return v + [0.0] * (dim - len(v))
    return None


_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="embed")


def embed_safe(provider: EmbeddingProvider | None, texts: list[str], *, dim: int,
               timeout: float = 5.0) -> list[list[float]] | None:
    """Redact, embed and fit to `dim`. Never raises; None on any failure, timeout or dim mismatch."""
    if provider is None or not texts:
        return None
    clean = [redact((t or "")[:MAX_CHARS]) for t in texts]
    fut = _pool.submit(provider.embed, clean)
    try:
        vecs = fut.result(timeout=timeout)
    except FutureTimeout:
        log.warning("embedding provider %s timed out", getattr(provider, "name", "?"))
        return None
    except Exception as exc:
        log.warning("embedding provider %s failed (%s)", getattr(provider, "name", "?"), type(exc).__name__)
        return None
    try:
        if not isinstance(vecs, list) or len(vecs) != len(texts):
            raise ValueError("shape")
        out = []
        for v in vecs:
            f = fit_dim([float(x) for x in v], dim)
            if f is None or not all(math.isfinite(x) for x in f):
                raise ValueError("dim")
            out.append(f)
        return out
    except Exception as exc:
        log.warning("embedding provider %s returned unusable vectors (%s)", getattr(provider, "name", "?"),
                    type(exc).__name__)
        return None
