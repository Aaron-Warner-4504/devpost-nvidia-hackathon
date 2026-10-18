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
    "signals": ["urgency", "lookalike_domain", "otp_request"],
    "created_at": "2026-10-17T10:00:00Z"
  }
}
```
`alert` is `null` when nothing is worth surfacing.

## POST /v1/check
Same request body and response as `/v1/events`, but always runs the full agent (user-initiated "is this a scam?").

## GET /v1/alerts?since=<ISO-8601>
Response 200: list of `AlertOut` (shape as above), newest first.
```json
[{"id": "alr_123", "event_id": "evt_123", "tier": "high", "headline": "Likely bank-impersonation scam", "why": [], "next_step": "Call your bank.", "signals": ["otp_request"], "created_at": "2026-10-17T10:00:00Z"}]
```

## POST /v1/alerts/{id}/feedback
Request: `{"label": "scam"}` (or `"safe"`). Response 200: `{"ok": true}`

## POST /v1/family/contacts
Request: `{"name": "Asha", "phone": "+919876543210"}`. Response 200: `{"ok": true, "id": "fam_123"}`

## DELETE /v1/me
Deletes the device and all its data. Response 204, no body.

_Note: stub phase: handlers return fixed example payloads and accept any non-empty bearer token; real behavior lands in later tasks with the same shapes._
