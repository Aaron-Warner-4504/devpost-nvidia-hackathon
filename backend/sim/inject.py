"""Demo timeline simulator: posts the 3-day courier -> call -> WhatsApp/UPI scam timeline.

    uv run python -m sim.inject --user demo --base-url http://localhost:8000
    uv run python -m sim.inject --user demo --reset          # DELETE /v1/me, then register again

The token for each (base URL, user label) is kept in the gitignored `.demo_token` file so reruns reuse
the same account. Event ids are stable and timestamps are aligned to 09:00 UTC on three fixed past days
(today-3, today-2, today-1), so running twice reports duplicates instead of creating new events.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, time, timedelta, timezone
from pathlib import Path

import httpx

TOKEN_FILE = Path(__file__).resolve().parent.parent / ".demo_token"
SCAMMER = "+919876543210"


def timeline(now: datetime | None = None) -> list[dict]:
    """The three scenario events with backdated timestamps (same shape as the eval scenarios)."""
    now = now or datetime.now(timezone.utc)
    day1 = datetime.combine(now.date() - timedelta(days=3), time(9, 0), tzinfo=timezone.utc)
    return [
        {"device_event_id": "demo-d1-courier-sms", "source": "sms", "ts": day1.isoformat(),
         "sender": "+917700112233",
         "text": ("Your BlueDart parcel is held at the depot due to an incomplete address. Call 9876543210 "
                  "or visit http://bluedart-redelivery.in to reschedule."),
         "triage_score": 0.3, "lang": "en"},
        {"device_event_id": "demo-d2-call", "source": "call", "ts": (day1 + timedelta(days=1)).isoformat(),
         "sender": None, "text": None, "triage_score": 0.4,
         "call": {"number": "+91 98765 43210", "duration_s": 240, "is_contact": False, "direction": "in"}},
        {"device_event_id": "demo-d3-whatsapp", "source": "whatsapp",
         "ts": (day1 + timedelta(days=2)).isoformat(), "sender": SCAMMER,
         "text": ("This is a customs officer. Your parcel is seized with illegal items. Pay Rs 25,000 penalty "
                  "immediately to customs.clear@ybl or face arrest. Do not tell anyone."),
         "triage_score": 0.9, "lang": "en"},
    ]


def _load_tokens(path: Path) -> dict:
    try:
        data = json.loads(path.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_tokens(path: Path, tokens: dict) -> None:
    path.write_text(json.dumps(tokens, indent=2))
    try:
        path.chmod(0o600)
    except OSError:
        pass


def _register(client: httpx.Client) -> str:
    r = client.post("/v1/devices/register")
    r.raise_for_status()
    return r.json()["token"]


def get_token(client: httpx.Client, user: str, base_url: str, token_file: Path, reset: bool = False) -> str:
    """Stored token for (base_url, user) if it still works; otherwise register. `reset` deletes the account."""
    tokens = _load_tokens(token_file)
    key = f"{base_url.rstrip('/')}|{user}"
    token = tokens.get(key)
    if token:
        auth = {"Authorization": f"Bearer {token}"}
        if reset:
            client.delete("/v1/me", headers=auth)  # 401 (already gone) is fine
            token = None
        elif client.get("/v1/me", headers=auth).status_code == 401:
            token = None
    if not token:
        token = _register(client)
        tokens[key] = token
        _save_tokens(token_file, tokens)
    return token


def run(user: str = "demo", base_url: str = "http://localhost:8000", *, client: httpx.Client | None = None,
        reset: bool = False, token_file: Path = TOKEN_FILE, now: datetime | None = None,
        out=print) -> list[dict]:
    """Post the timeline; returns one {device_event_id, event_id, duplicate, alert} dict per event."""
    own = client is None
    client = client or httpx.Client(base_url=base_url, timeout=60)
    try:
        token = get_token(client, user, base_url, token_file, reset)
        auth = {"Authorization": f"Bearer {token}"}
        results = []
        for ev in timeline(now):
            r = client.post("/v1/events", json=ev, headers=auth)
            r.raise_for_status()
            body = r.json()
            alert = body.get("alert")
            results.append({"device_event_id": ev["device_event_id"], "event_id": body["event_id"],
                            "duplicate": body["duplicate"], "alert": alert})
            if body["duplicate"]:
                out(f"{ev['ts'][:10]} {ev['device_event_id']}: duplicate (already ingested)")
            elif alert:
                out(f"{ev['ts'][:10]} {ev['device_event_id']}: [{alert['tier'].upper()}] {alert['headline']}")
            else:
                out(f"{ev['ts'][:10]} {ev['device_event_id']}: no alert")
        return results
    finally:
        if own:
            client.close()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--user", default="demo", help="local label for the stored token (default: demo)")
    p.add_argument("--base-url", default="http://localhost:8000")
    p.add_argument("--reset", action="store_true", help="DELETE /v1/me for the stored account, re-register")
    args = p.parse_args(argv)
    try:
        run(args.user, args.base_url, reset=args.reset)
    except httpx.HTTPError as exc:
        print(f"error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
