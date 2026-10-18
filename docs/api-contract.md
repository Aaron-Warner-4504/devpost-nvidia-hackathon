# Sentinel API Contract (v1)

Base URL: `http://<host>:8000`. All bodies are JSON. Timestamps are ISO-8601 UTC.

## Auth
Every endpoint except `POST /v1/devices/register` and `GET /health` requires:

```
Authorization: Bearer <token>
```
Missing or invalid token returns `401 {"detail": "..."}`. The token comes from `/v1/devices/register`.

## Tiers
| tier | meaning | interrupts the user? |
|---|---|---|
| info | looks benign, logged only | no |
| caution | mildly suspicious, shown in the feed | no |
| high | likely scam, notify the user | yes |
| critical | active, imminent scam (e.g. OTP/payment demanded) | yes (urgent) |

Only `high` and `critical` should interrupt the user.

## Enums
- `source`: sms | whatsapp | email | call | share | notification
- `tier`: info | caution | high | critical
- `call.direction`: in | out | missed
- `feedback.label`: scam | safe

## GET /health  (no auth)
Response 200: `{"status": "ok"}`

## POST /v1/devices/register  (no auth)
Request: empty body. Response 200:
```json
{"device_id": "dev_123", "token": "example-token"}
```

## POST /v1/events
Ingest a device event; the server triages and may return an alert. `duplicate: true` if `device_event_id` was already seen.
Request:
```json
{
  "device_event_id": "dev-1",
  "source": "sms",
  "ts": "2026-10-17T10:00:00Z",
  "sender": "+911234567890",
  "text": "Your account is blocked, click http://x.co",
  "call": null,
  "triage_score": 0.8,
  "lang": "en"
}
```
For `source: "call"`, `call` is `{"number": "+911234567890", "duration_s": 42, "is_contact": false, "direction": "in"}`.

Response 200 (`EventResult`):
```json
{
  "event_id": "evt_123",
  "duplicate": false,
  "alert": {
    "id": "alr_123",
    "event_id": "evt_123",
    "tier": "high",
    "headline": "Likely bank-impersonation scam",
    "why": [
      {"event_id": "evt_120", "ts": "2026-10-17T10:00:00Z", "text": "Same sender asked for an OTP yesterday"}
    ],
    "next_step": "Do not click the link or share any OTP. Call your bank on the number printed on your card.",
    "signals": ["urgency", "lookalike_domain", "credential_or_otp_ask"],
    "created_at": "2026-10-17T10:00:00Z"
  }
}
```
`alert` is `null` when nothing is worth surfacing. Precisely:
- `info` is never returned from `/v1/events` (it is logged server-side only).
- `caution` is returned but is **non-interrupting** (show it in the feed, no notification).
- `high` / `critical` are returned and should interrupt the user.
- Alert fatigue control (timing uses the event `ts`, capped at server time + 1 day; "within X" means
  `ts - X < earlier_ts <= ts`, so exactly X earlier no longer counts):
  - an alert for the same set of entities (sender, links, orgs, claims) at the same or a lower tier is not
    returned again within 6 h of an earlier shown one (a tier increase is returned);
  - at most 5 interrupting alerts per rolling 24 h: further `high` alerts are not returned;
  - `critical`: the same critical scam (same entities) is not returned again within 10 min of the last
    shown one; otherwise critical is always returned. After 20 criticals in a rolling 24 h, further
    criticals are still returned and listed but the server no longer sends interrupting notifications
    (push / family) for them. The response has no interrupt flag; clients interrupt on `high`/`critical`.
  - Results of `/v1/check` never count toward any of these.
- Duplicate delivery (a `device_event_id` already seen for this device's user) returns
  `{"event_id": "<the original event_id>", "duplicate": true, "alert": null}` and is not re-processed. Retrying
  after a timeout is therefore safe.
- Naive `ts` values (no offset) are treated as UTC.
- Only auth (401) and validation (422, e.g. `text` > 10000 chars, `sender` > 200 chars) errors are expected:
  if the AI service is down or slow the server still answers with the rules-only verdict (within ~2 s of the
  AI budget running out).

## POST /v1/check
Same request body and response as `/v1/events`, but always runs the full agent (user-initiated "is this a scam?")
and **always returns an alert**, of any tier including `info`. If the same `device_event_id` was already
processed, the response has `duplicate: true` and the alert stored for it. A check never suppresses a later
alert from `/v1/events` for the same message.

## GET /v1/alerts?since=<ISO-8601>
Response 200: list of `AlertOut` (shape as above) that were shown to this user (returned by `/v1/events` or
`/v1/check`), newest first, at most 100. With `since`, only alerts shown after that instant. `info` results
of `/v1/check` are not listed (they are a direct answer, not alert history).

## Errors
- `401` missing/invalid token, `422` invalid body (FastAPI validation detail).
- `500 {"detail": "internal error"}` on an unexpected server failure (e.g. database unavailable); no
  internals are returned. AI/LLM failures never cause a 500.
```json
[{"id": "alr_123", "event_id": "evt_123", "tier": "high", "headline": "Likely bank-impersonation scam", "why": [], "next_step": "Call your bank.", "signals": ["credential_or_otp_ask"], "created_at": "2026-10-17T10:00:00Z"}]
```

## POST /v1/alerts/{id}/feedback
Request: `{"label": "scam"}` (or `"safe"`). Response 200: `{"ok": true}`

## POST /v1/family/contacts
Request: `{"name": "Asha", "phone": "+919876543210"}`. Response 200: `{"ok": true, "id": "fam_123"}`

## DELETE /v1/me
Deletes the device and all its data. Response 204, no body.

_Note: `/v1/events`, `/v1/check` and `/v1/alerts` are live. `/v1/alerts/{id}/feedback` and `/v1/family/contacts` are still stubs returning fixed payloads (real behaviour lands in a later task with the same shapes)._
