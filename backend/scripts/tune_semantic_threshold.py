"""Re-tune SEMANTIC_THRESHOLD for an embedding model on the eval TUNE split (docs/memory.md).

    TOKEN_FACTORY_API_KEY=... uv run python scripts/tune_semantic_threshold.py            # estimate only
    TOKEN_FACTORY_API_KEY=... uv run python scripts/tune_semantic_threshold.py --yes      # embed + sweep

What it does:
1. Loads the TUNE split scenarios only (there is deliberately no flag to read the holdout split).
2. Replays them once with a recording provider (no network) to learn exactly which redacted texts the
   pipeline embeds, prints the estimated token count, and refuses to call the API without `--yes`.
3. Embeds the missing texts and caches the vectors in eval/.embed_cache/ (gitignored), keyed by
   sha256(model + redacted text), so reruns are free.
4. Sweeps the threshold 0.10..0.60 (step 0.025) through the real pipeline (memory + story progression,
   rules only, two-stage) and reports, per threshold, distributed_slow_burn scam-timeline recall and the
   number of false upgrades on benign TUNE scenarios.
5. Recommends the highest-recall threshold with ZERO benign upgrades, centred in its plateau for margin,
   and writes it to stdout and eval/threshold_<model>.json.

The API key is read from the environment / backend/.env by app.config and is never printed or stored.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import settings as default_settings  # noqa: E402
from app.pipeline import embeddings  # noqa: E402
from app.pipeline.embeddings import FakeEmbeddings, LocalEmbeddings, TokenFactoryEmbeddings  # noqa: E402
from eval import run_eval as ev  # noqa: E402

EVAL_DIR = ROOT / "eval"
CACHE_DIR = EVAL_DIR / ".embed_cache"
THRESHOLDS = [round(0.10 + 0.025 * i, 3) for i in range(21)]  # 0.10 .. 0.60
DSB = ev.DSB
CHARS_PER_TOKEN = 3.0  # conservative for mixed English / Hindi / Hinglish


# ---------------------------------------------------------------- cache
def cache_key(model: str, text: str) -> str:
    return hashlib.sha256(f"{model}\x00{text}".encode("utf-8")).hexdigest()


def slug(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", model).strip("_") or "model"


class VectorCache:
    """Append-only JSON-lines file per model: {"k": sha256, "v": [floats]}."""

    def __init__(self, directory: Path, model: str):
        self.path = directory / f"{slug(model)}.jsonl"
        self.model = model
        self.vectors: dict[str, list[float]] = {}
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                try:
                    d = json.loads(line)
                    self.vectors[d["k"]] = d["v"]
                except Exception:
                    continue  # a torn last line

    def get(self, text: str) -> list[float] | None:
        return self.vectors.get(cache_key(self.model, text))

    def put_many(self, items: list[tuple[str, list[float]]]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as f:
            for text, v in items:
                k = cache_key(self.model, text)
                self.vectors[k] = v
                f.write(json.dumps({"k": k, "v": [round(x, 7) for x in v]}) + "\n")


class RecordingProvider:
    """Offline stand-in used to learn which texts the pipeline embeds."""

    def __init__(self, name: str):
        self.name = name
        self.texts: dict[str, None] = {}
        self._fake = FakeEmbeddings(384)

    def embed(self, texts: list[str]) -> list[list[float]]:
        for t in texts:
            self.texts[t] = None
        return self._fake.embed(texts)


class CachedProvider:
    """Serves vectors from the cache only; a miss raises (embed_safe turns it into 'no link')."""

    def __init__(self, name: str, cache: VectorCache):
        self.name = name
        self.cache = cache
        self.misses = 0

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            v = self.cache.get(t)
            if v is None:
                self.misses += 1
                raise LookupError("not cached")
            out.append(v)
        return out


# ---------------------------------------------------------------- sweep + selection
def tune_scenarios(path: Path) -> list[dict]:
    scs = ev.load_scenarios(path)
    errs = [f"{sc.get('id', '?')}: {e}" for sc in scs for e in ev.validate_scenario(sc)] + ev.validate_set(scs)
    if errs:
        raise SystemExit("INVALID SCENARIOS:\n  " + "\n  ".join(errs))
    out = [s for s in scs if s["split"] == "tune"]
    assert all(s["split"] == "tune" for s in out)  # the holdout split is never read past this point
    return out


def _overrides(provider_kind: str, model: str, threshold: float) -> dict[str, Any]:
    ov: dict[str, Any] = {"embedding_provider": provider_kind, "semantic_memory": True, "story_progression": True,
                          "agent_review": "escalated", "semantic_threshold": threshold}
    if provider_kind == "token_factory":
        # in-process placeholders only so the provider counts as usable; the injected cached provider
        # never talks to the network and the real key is not involved
        ov.update(token_factory_api_key="cache-only", embed_model=model)
    return ov


def replay(scs: list[dict], session_factory: Callable, provider_kind: str, model: str, provider,
           threshold: float) -> list[dict]:
    ov = _overrides(provider_kind, model, threshold)
    embeddings.register_provider(default_settings.model_copy(update=ov), provider)
    return ev.run_scenarios(scs, "rules", ev.ScriptedAgentLLM(), session_factory, review_mode="two-stage",
                            overrides=ov)


def measure(results: list[dict]) -> dict[str, Any]:
    dsb = [r for r in results if r["category"] == DSB and r["label"] == "scam"]
    m = ev.compute_metrics(dsb) if dsb else {"n_scam": 0, "n_detected": 0}
    benign = [r for r in results if r["label"] == "benign"]
    ups = sum(1 for r in benign for e in r["events"] if e.get("upgraded"))
    dsb_ups = sum(1 for r in benign if r["category"] == DSB for e in r["events"] if e.get("upgraded"))
    n, d = m["n_scam"], m["n_detected"]
    return {"dsb_scams": n, "dsb_detected": d, "dsb_recall": (d / n) if n else None,
            "benign_upgrades": ups, "benign_dsb_upgrades": dsb_ups}


def sweep(scs, session_factory, provider_kind, model, provider, thresholds=THRESHOLDS) -> list[dict]:
    rows = []
    for t in thresholds:
        row = {"threshold": t, **measure(replay(scs, session_factory, provider_kind, model, provider, t))}
        rows.append(row)
        print(f"  threshold {t:.3f}: dsb recall {row['dsb_detected']}/{row['dsb_scams']}, "
              f"benign upgrades {row['benign_upgrades']}", flush=True)
    return rows


def select_threshold(rows: list[dict]) -> dict[str, Any]:
    """Highest recall among thresholds with zero benign upgrades; among those, the middle of the longest
    contiguous plateau (ties: the higher one) so a small shift in the model does not break the choice."""
    rows = sorted(rows, key=lambda r: r["threshold"])
    feas = [r for r in rows if r["benign_upgrades"] == 0 and r["dsb_recall"] is not None]
    if not feas:
        return {"threshold": None, "reason": "no threshold gives zero benign upgrades; keep SEMANTIC_MEMORY=false"}
    best = max(r["dsb_recall"] for r in feas)
    cand = {r["threshold"] for r in feas if r["dsb_recall"] == best}
    runs, cur = [], []
    for r in rows:
        if r["threshold"] in cand:
            cur.append(r["threshold"])
        elif cur:
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)
    run = max(runs, key=lambda x: (len(x), x[-1]))
    pick = run[len(run) // 2]
    return {"threshold": pick, "recall": best, "plateau": [run[0], run[-1]], "margin_steps": len(run) // 2,
            "reason": "highest TUNE recall with zero benign upgrades, centred in its plateau"
                      + ("" if len(run) > 2 else " (narrow plateau: little margin)")}


def estimate_tokens(texts) -> int:
    return sum(math.ceil(len(t) / CHARS_PER_TOKEN) for t in texts)


# ---------------------------------------------------------------- CLI
def main(argv: list[str] | None = None, *, session_factory: Callable | None = None,
         real_provider: Callable[[], Any] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Re-tune SEMANTIC_THRESHOLD on the TUNE split (never the holdout)")
    ap.add_argument("--embedding-provider", choices=("token_factory", "local", "fake"), default="token_factory")
    ap.add_argument("--model", default="", help="embedding model id (default: EMBED_MODEL / LOCAL_EMBED_MODEL)")
    ap.add_argument("--yes", action="store_true", help="allow paid API calls for texts missing from the cache")
    ap.add_argument("--scenarios", type=Path, default=ev.SCENARIOS)
    ap.add_argument("--cache-dir", type=Path, default=CACHE_DIR)
    ap.add_argument("--out-dir", type=Path, default=EVAL_DIR)
    a = ap.parse_args(argv)

    kind = a.embedding_provider
    model = a.model or (default_settings.local_embed_model if kind == "local" else
                        default_settings.embed_model if kind == "token_factory" else "")
    if kind == "token_factory" and not model:
        print("set EMBED_MODEL (e.g. Qwen/Qwen3-Embedding-8B) or pass --model")
        return 2
    if kind == "local" and not embeddings.available("local"):
        print("sentence-transformers not installed (uv sync --extra semantic)")
        return 2
    scs = tune_scenarios(a.scenarios)
    print(f"{len(scs)} TUNE scenarios ({sum(1 for s in scs if s['category'] == DSB)} distributed_slow_burn)")

    def run(sf) -> int:
        if kind == "fake":
            provider = FakeEmbeddings(384)
        else:
            name = f"tf:{model}" if kind == "token_factory" else f"local:{model}"
            cache = VectorCache(a.cache_dir, name)
            rec = RecordingProvider(name)
            replay(scs, sf, kind, model, rec, 0.3)  # offline pass: which texts get embedded
            missing = [t for t in rec.texts if cache.get(t) is None]
            tokens = estimate_tokens(missing)
            print(f"{len(rec.texts)} distinct texts embedded by the pipeline, {len(missing)} not cached "
                  f"(~{tokens} tokens, model {model})")
            if missing:
                if kind == "token_factory" and not a.yes:
                    print("Nothing was sent. Re-run with --yes to embed the missing texts via Token Factory.")
                    return 3
                inner = (real_provider() if real_provider else
                         TokenFactoryEmbeddings(model=model, dim=default_settings.embed_dim)
                         if kind == "token_factory" else LocalEmbeddings(model))
                if kind == "token_factory" and not real_provider and not default_settings.token_factory_api_key:
                    print("TOKEN_FACTORY_API_KEY is not set (environment or backend/.env)")
                    return 2
                for i in range(0, len(missing), 16):
                    chunk = missing[i:i + 16]
                    try:
                        vecs = inner.embed(chunk)
                    except Exception as exc:
                        print(f"embedding failed after {i} texts ({type(exc).__name__}); cached work is kept, "
                              "re-run to continue")
                        return 1
                    cache.put_many(list(zip(chunk, vecs)))
                print(f"cached {len(missing)} vectors in {cache.path}")
            provider = CachedProvider(name, cache)
        print("sweeping thresholds on TUNE:")
        rows = sweep(scs, sf, kind, model, provider)
        if isinstance(provider, CachedProvider) and provider.misses:
            print(f"WARNING: {provider.misses} embedding lookups missed the cache; those events were not linked")
        rec_ = select_threshold(rows)
        pname = provider.name
        report = {"model": pname, "provider": kind, "split": "tune", "n_scenarios": len(scs),
                  "n_dsb_scams": rows[0]["dsb_scams"] if rows else 0, "embed_dim": default_settings.embed_dim,
                  "rows": rows, "recommendation": rec_}
        a.out_dir.mkdir(parents=True, exist_ok=True)
        out = a.out_dir / f"threshold_{slug(pname)}.json"
        out.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
        if rec_["threshold"] is None:
            print(f"RECOMMENDATION: none ({rec_['reason']})")
        else:
            print(f"RECOMMENDATION: SEMANTIC_THRESHOLD={rec_['threshold']}  (TUNE dsb recall {rec_['recall']:.3f}, "
                  f"plateau {rec_['plateau'][0]}..{rec_['plateau'][1]}, {rec_['reason']})")
        print(f"wrote {out}. Synthetic TUNE data: confirm with `run_eval.py --ablation --embedding-provider "
              f"{kind} --semantic-threshold <t>` before relying on it.")
        return 0

    if session_factory is not None:
        return run(session_factory)
    with ev.eval_database("_tune") as sf:
        return run(sf)


if __name__ == "__main__":
    os.environ.setdefault("ENVIRONMENT", "dev")
    sys.exit(main())
