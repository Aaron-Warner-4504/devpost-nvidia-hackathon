# Sentinel Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Sentinel Android app (Kotlin): capture authorized signals, redact and triage them on-device, keep an encrypted local memory, sync with the cloud backend, and show clear tiered alerts with a "why" timeline.

**Architecture:** Single-module Compose app, MVVM, Hilt DI. Capture services (notification listener, call screening, share target) write to a local encrypted Room DB through a pipeline `normalize → redact → triage → store → outbox`. A WorkManager sync worker posts events to the backend (`docs/api-contract.md`) and stores returned alerts. If the backend is unreachable, the on-device triage alone can raise a local alert (degraded mode).

**Tech Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Navigation Compose, Hilt, Room + SQLCipher, Retrofit/OkHttp + kotlinx.serialization, WorkManager, DataStore, Firebase Cloud Messaging, Android Keystore, ML Kit (optional on-device OCR). minSdk 29, targetSdk 35.

**Spec:** `docs/PLAN.md` (sections 4-5), contract in `docs/api-contract.md` (produced by backend Task 1).

## Global Constraints
- Deadline: APK ready by **27 Oct 2026**; submission by 29 Oct end of day.
- Tier names exactly: `info`, `caution`, `high`, `critical`. Only `high` and `critical` show a heads-up notification.
- No SMS or call-log permissions requested for SMS content. SMS/WhatsApp text comes only through the notification listener. Call data is number, time, duration, direction, is-contact only: no audio.
- Per-source consent: nothing is captured before the user turns that source on; turning it off stops capture immediately.
- Raw content stays on the phone. Only redacted text and `triage_score` are sent, except "Check this", where the user explicitly sends the content.
- All local data encrypted (SQLCipher, key in Android Keystore). "Delete all my data" wipes local DB and calls `DELETE /v1/me`.
- UI strings in English and Hindi (`values/` and `values-hi/`).
- Redaction and triage logic must pass the shared test vectors in `shared/vectors/*.json` (same file the backend tests use).
- No secrets in the repo; backend base URL via `local.properties` / BuildConfig.

## Review Focus
1. OEM battery killers (Xiaomi, Oppo, Vivo, Samsung): the notification listener stops after hours; the app must detect that and guide the user to fix it.
2. Same notification posted multiple times (updates, grouped, WhatsApp summary): one event only.
3. Phone offline for days, then back online: outbox drains in order, no duplicates, alerts appear with their original timestamps.
4. User denies or later revokes a permission or consent: the app keeps working for other sources and shows a clear "off" state, never crashes.
5. Notification text containing OTPs: never stored or logged unredacted anywhere (DB, logs, crash reports, outbox).

---

## File Structure
```
android/app/src/main/java/ai/enthral/sentinel/
  SentinelApp.kt, MainActivity.kt
  di/            Hilt modules
  data/api/      ApiService, DTOs (mirror docs/api-contract.md), AuthInterceptor
  data/db/       Room entities, DAOs, SQLCipher factory
  data/repo/     EventRepo, AlertRepo, ConsentRepo, EntityRepo
  pipeline/      Normalizer, Redactor, TriageEngine, EntityExtractor, Pipeline
  capture/       SentinelNotificationListener, CallScreeningImpl, ShareReceiverActivity, SourceFilter
  sync/          SyncWorker, Outbox
  alerts/        AlertNotifier, AlertPolicyLocal
  push/          SentinelFcmService
  ui/            onboarding/, home/, alert/, timeline/, check/, family/, settings/, theme/
shared/vectors/  redact.json, triage.json, extract.json   (repo root, shared with backend)
```

---

### Task A1: Project skeleton, theme, API client against the contract
**Files:** Create Gradle project, `SentinelApp.kt`, `MainActivity.kt`, `data/api/*`, `ui/theme/*`; Test `ApiServiceTest.kt`.
**Interfaces:** Produces `ApiService` (Retrofit) with `register()`, `postEvent(EventIn): EventResult`, `check(EventIn|multipart): EventResult`, `getAlerts(since): List<AlertOut>`, `postFeedback(id, Feedback)`, `createFamilyInvite()`, `acceptFamilyInvite()`, `deleteMe()`; DTOs `EventIn`, `AlertOut`, `WhyItem`, `EventResult`, `CallMeta`, `Tier` enum matching `docs/api-contract.md` field-for-field.
- [ ] Step 1: Create project, Hilt, Compose, navigation shell with 4 empty tabs (Home, Timeline, Check, Settings).
- [ ] Step 2: MockWebServer test: each contract example JSON in `docs/api-contract.md` deserializes into the DTOs and re-serializes unchanged; 401 maps to `NotRegistered`.
- [ ] Step 3: Implement, run `./gradlew test` (PASS). Step 4: Commit.

### Task A2: Onboarding, consent, permissions, device registration
**Files:** Create `ui/onboarding/*`, `data/repo/ConsentRepo.kt`; Test `ConsentRepoTest.kt`, Compose UI test `OnboardingTest.kt`.
**Interfaces:** `ConsentRepo.enabled(source): Flow<Boolean>`, `set(source, on)`; sources `NOTIFICATIONS`, `CALLS`, `EMAIL`(placeholder). Onboarding: explains in plain words what is read and what leaves the phone, one toggle per source, deep links to Notification Access settings, call-screening role request, `POST_NOTIFICATIONS` permission. Registers the device and stores the token in encrypted storage.
- [ ] Tests: toggling a source off emits false and capture code (A6/A7) checks it; registration failure shows retry, not a crash; Hindi strings exist for every onboarding string key.
- [ ] Implement, run, commit.

### Task A3: Encrypted local store
**Files:** Create `data/db/*`; Test `DbEncryptionTest.kt`.
**Interfaces:** Tables `events(id, deviceEventId UNIQUE, source, ts, sender, redactedText, triageScore, synced)`, `entities(type, canonical)`, `eventEntities`, `alerts(id, eventId, tier, headline, whyJson, nextStep, signalsJson, createdAt, userLabel)`, `outbox(eventId, attempts, nextAttemptAt)`. DB key generated once, wrapped by Keystore.
- [ ] Tests: DB file bytes do not contain a known plaintext after insert; inserting the same `deviceEventId` twice leaves one row; wipe() removes all rows and the key.
- [ ] Implement, run, commit.

### Task A4: On-device redaction (shared vectors)
**Files:** Create `pipeline/Redactor.kt`, `shared/vectors/redact.json` (coordinate with backend Task 4); Test `RedactorVectorsTest.kt`.
**Interfaces:** `Redactor.redact(text: String): String` masking `[OTP] [CARD] [ACCT] [AADHAAR] [PAN]`; keeps phone numbers, URLs, UPI IDs, amounts.
- [ ] Test: loads `redact.json` vectors: `"Your OTP is 482913"`→`"Your OTP is [OTP]"`-style masking, 16-digit cards, PAN `ABCDE1234F`, Aadhaar, Hindi `"आपका ओटीपी 482913 है"`, UPI `"ramesh@okhdfcbank"` untouched, idempotent.
- [ ] Implement, run, commit.

### Task A5: Triage engine
**Files:** Create `pipeline/TriageEngine.kt`, `pipeline/EntityExtractor.kt`, `shared/vectors/triage.json`; Test `TriageVectorsTest.kt`.
**Interfaces:** `TriageEngine.score(ev: RawEvent): Triage(score: Float, signals: List<String>, localTier: Tier)` using regex/keyword rules in English and Hindi (urgency, OTP/UPI-collect ask, remote-access apps, shorteners, lookalike brand domains, "don't tell anyone"); known bank sender headers lower the score. `EntityExtractor.extract(...)` normalizes phones to E.164 (+91).
- [ ] Test: vectors: lone bank OTP → `info`; UPI collect request with urgency → `high`; Hindi digital-arrest threat → ≥ `high`; `+91 98765-43210`, `0091 9876543210` → same canonical; empty text → score 0, no crash.
- [ ] Implement, run, commit.

### Task A6: Notification capture
**Files:** Create `capture/SentinelNotificationListener.kt`, `capture/SourceFilter.kt`, `pipeline/Pipeline.kt`; Test `SourceFilterTest.kt`, `PipelineTest.kt`.
**Interfaces:** `SourceFilter.accept(sbn): Source?` using an allowlist (default SMS app(s), WhatsApp, Telegram, major bank/UPI apps); ignores own app, ongoing/group-summary notifications, and anything while the source consent is off. `Pipeline.process(raw: RawEvent)`: normalize → redact → triage → store → enqueue outbox. `deviceEventId` = hash(package, sender, text, postTime bucket).
- [ ] Tests: repeated update of the same notification yields one event; consent off → no event; OTP text → stored text contains `[OTP]` and not the digits; logs contain no digits from the OTP (log capture check).
- [ ] Implement, add listener rebind (`requestRebind`) on boot and on app open; commit.

### Task A7: Call metadata capture
**Files:** Create `capture/CallScreeningImpl.kt`; Test `CallEventMapperTest.kt`.
**Interfaces:** Uses `CallScreeningService` with `ROLE_CALL_SCREENING` for incoming calls (number, is-contact) and `CallLog` observer for duration/direction after the call if `READ_CALL_LOG` is granted. Produces `EventIn(source="call", call=CallMeta(...))`. Never blocks or rejects a call.
- [ ] Tests: mapper turns (number, duration 240, contact=false, in) into `CallMeta`; unknown/hidden number handled (`number=""`); consent off → nothing.
- [ ] Implement, test on a real device, commit.

### Task A8: Sync, outbox, offline mode
**Files:** Create `sync/SyncWorker.kt`, `sync/Outbox.kt`, `alerts/AlertPolicyLocal.kt`; Test `SyncWorkerTest.kt`.
**Interfaces:** `SyncWorker` posts outbox events in timestamp order, exponential backoff, marks synced, stores returned `AlertOut`; on 401 re-registers once. If offline and `localTier >= high`, `AlertPolicyLocal` raises a local alert (flagged `degraded`).
- [ ] Tests (MockWebServer + fake clock): 3 events queued offline, then online → 3 posts in order, no duplicates on retry after a dropped response; server `duplicate=true` marks synced; offline `high` triage creates one local alert only.
- [ ] Implement, run, commit.

### Task A9: Alerts UI and notifications
**Files:** Create `alerts/AlertNotifier.kt`, `ui/alert/*`, `ui/home/*`; Test `AlertScreenTest.kt`.
**Interfaces:** Heads-up notification channel for `high`/`critical` (critical uses a distinct channel and colour); Alert detail: headline, "Why now" timeline built from `why` items (time + text), one next step, buttons "This is a scam" / "This is fine" calling feedback; Home shows today's status and alert history.
- [ ] Tests: `info`/`caution` produce no heads-up; `critical` shows next step text verbatim and the 3 why items in time order; feedback tap calls API and updates local label; Hindi strings render.
- [ ] Implement, run, commit.

### Task A10: "Check this" share target and screenshot checks
**Files:** Create `capture/ShareReceiverActivity.kt`, `ui/check/*`; Test `ShareReceiverTest.kt`.
**Interfaces:** Accepts `ACTION_SEND` text/plain and image/*; shows a confirmation ("this will be sent to Sentinel for analysis"), sends text via `check()` or image as multipart (downscaled to ≤ 5 MB), shows a result card identical to the alert detail. Offline: save and retry.
- [ ] Tests: shared text → `check` called with `source="share"`; image over 5 MB is downscaled below limit; user cancels → nothing sent.
- [ ] Implement, run, commit.

### Task A11: Memory timeline screen
**Files:** Create `ui/timeline/*`. Entity-centric view: tap a number/domain to see all events linked to it across SMS/call/WhatsApp, in time order, with tiers.
- [ ] Test: events for the same canonical phone across 3 sources appear in one timeline in order; unrelated entity not shown. Commit.

### Task A12: FCM, family pairing, settings, delete data
**Files:** Create `push/SentinelFcmService.kt`, `ui/family/*`, `ui/settings/*`.
- Registers FCM token with `POST /v1/devices/fcm`; shows pushed alerts via the same `AlertNotifier`.
- Family: protected user creates invite (with explicit consent toggle), shows the 6-char code; family member's app enters the code (`family/accept`) and then receives critical-alert pushes (redacted headline only); either side can unlink.
- Settings: per-source toggles, pause monitoring, language, "Delete all my data" (local wipe + `DELETE /v1/me`), listener health status.
- [ ] Tests: wipe empties DB and calls `deleteMe`; pairing code error states (expired/invalid) shown; pushed alert for the family role shows headline only.
- [ ] Implement, run, commit.

### Task A13: Reliability on real devices
**Files:** Create `ui/settings/ListenerHealth.kt`.
- Foreground-service-free approach first; detect "listener not connected" (no events or `isNotificationListenerAccessGranted` mismatch) and show a banner that deep-links to OEM autostart/battery settings (Xiaomi, Oppo, Vivo, Samsung); heartbeat check on app open.
- [ ] Manual test matrix on the team's real phones: capture after 1 h and 12 h idle, reboot, battery saver on. Record results in `android/TESTING.md`. Commit.

### Task A14: Demo mode and release build
**Files:** Create `ui/settings/DemoMode.kt`, release signing config.
- Demo mode injects the scripted 3-day timeline (courier SMS → unknown call → WhatsApp UPI request) locally with backdated timestamps so the demo works without a live scam; the same timeline is also posted by the backend `sim/inject.py`.
- [ ] Test: demo run twice does not duplicate events; releases an APK signed with a debug-safe keystore; install on 2 phones; run full demo end to end against the deployed backend. Tag `hackathon-v1`.

---
## Schedule (Android dev)
| Dates | Tasks |
|---|---|
| 17-18 Oct | A1 (use contract from backend Task 1), A2, A3 |
| 19-21 | A4, A5, A6, A7 (real-device test of listener on day 19) |
| 22-24 | A8, A9, A10 |
| 25-26 | A11, A12 |
| 27 | A13, A14, APK ready |
| 28-29 | Joint bug bash, demo video, submit |

Cut order if behind: A11 timeline, then family pairing in A12, then image sharing in A10, then Hindi UI strings.

## Dependencies on the backend
A1 needs backend Task 1 (contract) on day 1. A8 and A10 need backend Task 10 (real endpoints) around 24 Oct; until then use MockWebServer or the stub endpoints. Image check needs backend Task 14. Family needs backend Task 11. `shared/vectors/` is created jointly in A4/backend Task 4.

## Self-Review
- **Spec coverage:** capture (notifications, calls, share), on-device redaction/triage/encrypted store, cloud sync, offline degraded mode, tiered alerts with "why", family alert, consent, deletion, demo mode, Hindi UI all have tasks. Gmail is server-side and needs only a "Connect Gmail" button later (placeholder consent source `EMAIL`).
- **Type consistency:** DTOs mirror the backend contract names (`EventIn`, `AlertOut`, `WhyItem`, `Tier`).
