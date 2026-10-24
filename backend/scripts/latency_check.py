"""Latency check for POST /v1/events against a running server (fake-LLM dev server or live deployment).

    uv run python scripts/latency_check.py --base-url https://sentinel.example.com            # both modes
    uv run python scripts/latency_check.py --base-url http://localhost:8000 --mode rules -n 50
    uv run python scripts/latency_check.py --base-url ... --token $TOKEN                        # reuse an account

Modes (each posts N unique events and reports p50 / p95 / max wall time per request):
  rules  low-triage benign text with no links: never reaches the agent  -> p95 must be < 300 ms
  agent  high-triage scam text with a link: escalates to the agent       -> p95 must be < 6 s
Exit code 0 if every measured mode meets its threshold, 1 otherwise, 2 on setup errors.
Without --token a throwaway account is registered (rate-limited per IP) and deleted at the end.
The agent mode only means something when the server has a working LLM (or a fake one) configured and
AGENT_MODE is not `rules`: a server without an LLM answers fast via the degraded path.
"""
from __future__ import annotations

import argparse
import math
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

import httpx

THRESHOLDS_MS = {"rules": 300.0, "agent": 6000.0}


def percentile(values: list[float], p: float) -> float:
    """Nearest-rank percentile (p in 0..100) of a non-empty list."""
    s = sorted(values)
    return s[max(0, math.ceil(p / 100 * len(s)) - 1)]


def make_event(mode: str, i: int, base: datetime) -> dict:
    ts = (base + timedelta(seconds=i)).isoformat()
    eid = f"lat-{mode}-{uuid.uuid4().hex}"
    if mode == "rules":
        return {"device_event_id": eid, "source": "sms", "ts": ts, "sender": "+9177001122%02d" % (i % 100),
                "text": f"Hi, are we still meeting at 6 for dinner? Bring the books, ref {i}.",
                "triage_score": 0.05, "lang": "en"}
    return {"device_event_id": eid, "source": "whatsapp", "ts": ts, "sender": "+9198765%05d" % i,
            "text": (f"Customs officer here: your parcel {i} is seized. Pay Rs 25,000 penalty to "
                     f"customs.clear{i}@ybl immediately or face arrest. Details http://bluedart-redelivery.in/pay"),
            "triage_score": 0.95, "lang": "en"}


def measure(client: httpx.Client, headers: dict, mode: str, n: int, warmup: int) -> list[float]:
    base = datetime.now(timezone.utc) - timedelta(hours=1)
    out: list[float] = []
    for i in range(n + warmup):
        ev = make_event(mode, i, base)
        t0 = time.perf_counter()
        r = client.post("/v1/events", json=ev, headers=headers)
        dt = (time.perf_counter() - t0) * 1000
        r.raise_for_status()
        if i >= warmup:
            out.append(dt)
    return out


def report(mode: str, samples: list[float]) -> bool:
    p50, p95, mx = percentile(samples, 50), percentile(samples, 95), max(samples)
    limit = THRESHOLDS_MS[mode]
    ok = p95 < limit
    print(f"{mode:6s} n={len(samples):3d} p50={p50:8.1f} ms  p95={p95:8.1f} ms  max={mx:8.1f} ms  "
          f"threshold p95 < {limit:.0f} ms  {'PASS' if ok else 'FAIL'}")
    return ok


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base-url", default="http://localhost:8000")
    p.add_argument("--mode", choices=["rules", "agent", "both"], default="both")
    p.add_argument("-n", "--requests", type=int, default=30, help="measured requests per mode (default 30)")
    p.add_argument("--warmup", type=int, default=3)
    p.add_argument("--token", help="existing bearer token (default: register a throwaway account)")
    args = p.parse_args(argv)
    if args.requests < 1:
        print("-n must be >= 1", file=sys.stderr)
        return 2
    modes = ["rules", "agent"] if args.mode == "both" else [args.mode]
    with httpx.Client(base_url=args.base_url, timeout=60) as client:
        registered = False
        try:
            token = args.token
            if not token:
                r = client.post("/v1/devices/register")
                r.raise_for_status()
                token, registered = r.json()["token"], True
            headers = {"Authorization": f"Bearer {token}"}
            ok = True
            for mode in modes:
                ok &= report(mode, measure(client, headers, mode, args.requests, args.warmup))
            return 0 if ok else 1
        except httpx.HTTPError as exc:
            print(f"error: {type(exc).__name__}: {exc}", file=sys.stderr)
            return 2
        finally:
            if registered:
                try:
                    client.delete("/v1/me", headers=headers)
                except Exception:
                    pass


if __name__ == "__main__":
    sys.exit(main())
