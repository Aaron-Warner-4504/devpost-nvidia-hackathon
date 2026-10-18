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
At most 20 registrations per client IP (IPv6: per /64 network) per rolling hour; beyond that `429 {"detail": "too many requests, try
again later"}`. Register once per install and keep the token.

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
  "lang": "en",
  "is_contact": false
}
```
For `source: "call"`, `call` is `{"number": "+911234567890", "duration_s": 42, "is_contact": false, "direction": "in"}`.

`is_contact` (optional, `true` | `false` | omitted/`null`): set it to `true` only when the app has checked that the
sender is a saved contact, e.g. via `ContactsContract` lookup of the sender number or the notification's `Person`
contact URI. Never infer it from the display name: anyone can call themselves "Mom" on WhatsApp. Group-chat
notifications send `false` or omit it. Omitted means "not known to be a contact", the same as before this
field existed. It only suppresses the "unknown sender" signal and never lowers a tier on its own. For calls,
`call.is_contact` keeps working as before.

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
```json
[{"id": "alr_123", "event_id": "evt_123", "tier": "high", "headline": "Likely bank-impersonation scam", "why": [], "next_step": "Call your bank.", "signals": ["credential_or_otp_ask"], "created_at": "2026-10-17T10:00:00Z"}]
```

## POST /v1/alerts/{id}/feedback
The user's verdict on one of their alerts. Request: `{"label": "scam"}` or `{"label": "safe"}`.
Response 200: `{"ok": true}`. `404 {"detail": "alert not found"}` if the alert is not the caller's (or does
not exist). Idempotent; sending the other label flips it. Effects (per user only, never other users):
- `safe`: the alert's **sender** (SMS/WhatsApp sender phone, or the caller's number for a call) is
  remembered as safe, so its later messages score lower (e.g. a repeated `caution` message becomes `info`
  and is no longer returned). Risky links found by the link inspector are never discounted. Numbers, links or UPI ids that only appear *in the message body* are
  never made safe, and email senders are not (the From: address can be forged).
- `scam`: the message's phone numbers, link domains and UPI ids are pinned as known bad: the next event that
  contains any of them is rated `critical` (at least `high` if it also comes from a sender marked safe). Well-known brand / bank domains (e.g. `hdfcbank.com`,
  `google.com`), link shorteners and shared hosting domains are never pinned.

## Family (guardian) alerts
Two app installs, each registered as its own user via `/v1/devices/register`: the **protected user** (the
person Sentinel watches) and the **guardian** (family member). Pairing:
1. The protected user's app shows an explicit consent toggle and calls `POST /v1/family/invites`
   `{"consent": true}`; it displays the returned code.
2. The guardian types the code into their app, which calls `POST /v1/family/accept` with its own FCM token.

When the protected user gets a `critical` alert that interrupts (see `/v1/events`), each guardian receives
one FCM push: `notification.title` = `"Sentinel alert"`, `notification.body` = the alert headline only
(numbers, links and ids masked, at most 140 chars), `data` = `{"kind": "family_critical",
"alert_tier": "critical"}`. Never the message text, sender, or the protected user's name. No push for
`caution`/`high`/`info`, for `/v1/check` results, or for alerts the server did not interrupt for. At most
10 family pushes per guardian per rolling hour (further ones are dropped). If FCM reports the token
unregistered, pushes to that guardian stop until they pair again (re-accepting a new code with a fresh token
updates the existing link).

### POST /v1/family/invites  (protected user)
Request: `{"consent": true}` (anything else, or missing, is `422`). Response 200:
```json
{"code": "K7M2QX", "expires_at": "2026-10-17T10:10:00Z"}
```
The code is 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no 0/O/1/I/L), valid for 10 minutes, single
use. Creating a new invite cancels the user's earlier unused codes. `429` after 5 invites in an hour.

### POST /v1/family/accept  (guardian)
Request (the guardian's own bearer token; `name` is the guardian's display name shown to the protected user,
1-80 chars; `fcm_token` is the guardian app's FCM registration token):
```json
{"code": "K7M2QX", "name": "Ravi", "fcm_token": "<guardian FCM registration token>"}
```
Response 200: `{"id": "fam_123", "protected_name": null}` (`id` is the link id; nothing else about the
protected user is returned). Codes are case-insensitive. Errors:
- `404 {"detail": "invalid or expired code"}` for a wrong, expired, already used, or your own code (all
  identical).
- `429 {"detail": "too many attempts, try again later"}` after 10 failed attempts by this user in an hour,
  or while the server is seeing too many failed attempts overall (pairing pauses for everyone; retry later,
  the code is not used up).
- `409 {"detail": "guardian limit reached"}` when the protected user already has 5 guardians. The code is
  not used up: once a guardian is removed, the same code can be redeemed while it is still valid.

`POST /v1/family/contacts` is the same operation (same request, response and errors).

### GET /v1/family/contacts
The caller's links in both roles. Response 200:
```json
{
  "guardians": [{"id": "fam_123", "name": "Ravi", "linked_at": "2026-10-17T10:05:00Z"}],
  "protecting": [{"id": "fam_456", "linked_at": "2026-10-17T11:00:00Z"}]
}
```
`guardians`: people who get my critical alerts. `protecting`: people whose critical alerts I get.

### DELETE /v1/family/contacts/{id}
Either the protected user or the guardian removes the link; pushes stop immediately. Response 204.
`404 {"detail": "not found"}` if the caller is not part of that link (or it does not exist).

## DELETE /v1/me
Deletes the device and all its data, including family links in both roles and pending invites. Response 204,
no body.

## Errors
- `401` missing/invalid token, `422` invalid body (FastAPI validation detail), `404`/`429` as listed per
  endpoint.
- `500 {"detail": "internal error"}` on an unexpected server failure (e.g. database unavailable); no
  internals are returned. AI/LLM failures never cause a 500.
