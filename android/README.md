# Sentinel Android

Native Android client (Kotlin, Jetpack Compose). Material 3 components sit under Sentinel's own design system:
see [`docs/design.md`](docs/design.md) and the **Design system** section below.

* Phase 1: toolchain, skeleton, design system, typed API client for `docs/api-contract.md`.
* Phase 2 (this): the real product screens: onboarding and consent (A2), alerts list and detail (A9), Check this with
  share target (A10), an alert timeline built from the alerts' "why" items (A11, see below), family pairing and
  settings and delete-my-data (A12), in-app demo mode (A14).
* Phase 3: always-on capture (A4, A6, A7, A8, A13): notification listener for allowlisted apps, on-phone redaction
  (golden parity with the backend), encrypted outbox + WorkManager sync, local heads-up alerts with late-upgrade
  follow-ups, call-log capture after each call and opt-in call screening. See **Capture** below and
  [`../docs/capture.md`](../docs/capture.md). (No encrypted local alert database: alerts are read from the server.)

Plan deviations: hand-written `AppContainer` instead of Hilt, OkHttp instead of Retrofit, minSdk 26 instead of 29.

## Toolchain setup (Linux, nothing system-wide)
```bash
android/scripts/setup-sdk.sh     # downloads cmdline-tools, accepts SDK licenses, installs platform 35, build-tools 35.0.0,
                                 # emulator, android-34 google_apis x86_64 image, creates AVD `sentinel_pixel` (pixel_6)
source android/scripts/env.sh    # ANDROID_HOME=~/Android/Sdk, PATH with adb/emulator/sdkmanager
```
Needs JDK 17+ (built with 21), `/dev/kvm` access (user in group `kvm`), and ~10 GB disk. Gradle comes from the wrapper (8.14.3).

## Run
```bash
cd android && source scripts/env.sh
scripts/emulator.sh headless          # or no argument for a window; waits for sys.boot_completed
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.enthral.sentinel.debug/ai.enthral.sentinel.MainActivity
```
Debug builds install as `ai.enthral.sentinel.debug` and log HTTP *headers* under tag `SentinelHttp` (Authorization redacted; bodies never logged).

### Pointing the debug build at a backend
* Emulator: default `http://10.0.2.2:8000` (host loopback). Start the backend on `0.0.0.0` or `127.0.0.1:8000`.
* Physical phone over USB: `adb reverse tcp:8000 tcp:8000`, then build with `./gradlew assembleDebug -PsentinelApiBaseUrl=http://localhost:8000`.
* Debug-only `network_security_config` allows cleartext to `10.0.2.2` and `localhost` only; release forbids cleartext.
* Release URL is set per build with `-PsentinelApiBaseUrl=...` (see **Release build**); without it the placeholder `https://api.example.invalid` is baked in and the app refuses to start.

## Release build
Signed, minified (R8), https-only, no demo mode (`DEMO_ENABLED=false`, the demo code is stripped).

```bash
cd android && source scripts/env.sh
./gradlew assembleRelease -PsentinelApiBaseUrl=https://interns.enthral.ai/sentinel
# -> app/build/outputs/apk/release/app-release.apk  (package ai.enthral.sentinel)
```
* `sentinelApiBaseUrl` must be `https://...` with no spaces or quotes; trailing slashes are stripped (the client appends
  `/v1/...` to it, so the live URL is `https://interns.enthral.ai/sentinel`). Anything else fails the build.
* Without the property the placeholder `https://api.example.invalid` is baked in and the app throws at startup with
  "Sentinel release build has no API URL" instead of silently calling a dummy host.
* To point at a new server, rebuild with the new URL and reinstall (`adb install -r`); same signing key, data is kept.
  The registered device token belongs to the old server, so users should use Settings -> Delete my data first (or expect a re-register).

### Signing
Create `android/keystore.properties` (gitignored, mode 600). If it is missing or incomplete the release APK is built unsigned.
```properties
storeFile=/home/YOU/.sentinel-keystore/sentinel-release.jks
storePassword=<password>
keyAlias=sentinel
keyPassword=<password>
```
Key creation (once; keep it OUTSIDE the repo; `*.jks`, `*.keystore` and `keystore.properties` are gitignored):
```bash
mkdir -m 700 ~/.sentinel-keystore
PW=$(openssl rand -hex 24)
keytool -genkeypair -keystore ~/.sentinel-keystore/sentinel-release.jks -alias sentinel -keyalg EC -groupname secp256r1 \
  -validity 10000 -dname "CN=Sentinel" -storepass "$PW" -keypass "$PW"
```
**Back up `~/.sentinel-keystore/` and the password.** Android only accepts an update signed with the same key: if the key
is lost, the installed app can never be updated (users would have to uninstall). Sign every future release with this key.

Verify and install:
```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk      # add -s <serial> with several devices
adb shell am start -n ai.enthral.sentinel/.MainActivity
```

## Screens (phase 2)
| Screen | What it does |
|---|---|
| Onboarding (4 steps, pager + page dots) | welcome, three privacy promises, language (segmented control, applied immediately), sources and consent as a grouped list. Per-source switch opens a "what we read / never read" sheet; Allow deep-links to Notification access (state re-read on resume) or asks for `READ_CALL_LOG`. **The device is registered only when the user presses Finish**, then consents are posted (`/v1/me/consents`). Failure shows a retry. |
| Home | protection hero (setup incomplete / protected / limited / paused / offline), "what Sentinel did today" tier counts, latest alert, quick actions, pull-to-refresh, shimmer skeleton, error + retry (today's summary is only shown with real data) |
| Alerts | tier filter chips, Today / Yesterday / date groups as inset grouped rows, pull-to-refresh, calm empty states |
| Alert detail | verdict header (tier glyph + label + headline as the title), "why" timeline with the current event highlighted, next step with share/copy summary (never the raw text), signals as plain-language chips, privacy note, feedback with optimistic UI and Undo (the POST is sent after the undo window, or immediately if you leave the screen) |
| Check | paste or photo-picker screenshot (no storage permission), optional sender, loading with Cancel, friendly errors that never clear what you typed, share target for text and images; result reuses the alert detail ("Looks fine" variant for `info`) |
| Family | create invite (explicit consent switch, large code, 10-minute countdown, copy/share/regenerate, guardians list with Remove) and enter code (one text field drawn as 6 cells, paste works), name, links list with Stop; 404/409/429 mapped |
| Settings | sources and permission status with fix links, pause, language, notification channels (Critical, High-risk, Updates) with system links, POST_NOTIFICATIONS flow, true-black dark mode, demo (debug), data and privacy (sheet), type-to-confirm delete (sheet), about (base URL only in debug) |
| Demo (debug builds only) | replays the 3-day courier SMS -> call -> WhatsApp UPI story from `backend/sim/inject.py` (same payloads, stable `demo-d1/d2/d3-*` ids, 09:00 UTC on today-3..today-1) with a stepper, ends on the critical alert; re-running shows duplicates |

Heads-up notifications: after each alerts refresh, new `high`/`critical` alerts become a notification (channels above;
lock screen shows only "Sentinel alert"). The first refresh after setup is a silent baseline, and results of
"Check this" are marked seen so they never come back as a notification. Tapping one opens `sentinel://alert/{id}`
(works on cold start and when the app is already open).

Timeline (A11): no endpoint exposes event history to the app (`/v1/dashboard/timeline` is demo-only and
`DEMO_ENABLED`), so the timeline is built from each alert's `why` items plus the current event. `WhyItem` carries no
`source`, so no source icon is shown.

## Package map (`app/src/main/java/ai/enthral/sentinel`)
| Package | Role |
|---|---|
| `SentinelApp`, `AppGraph`, `AppContainer` | Application; `AppGraph` is the interface screens depend on, `AppContainer` the production wiring (lazy), tests use `FakeGraph` |
| `domain` | `Tier`, `Source`, `ConsentSource`, `Alert`, `WhyItem`, `Signals` (signal -> string map), `computeProtection` |
| `data/api` | `SentinelApi`, `OkHttpSentinelApi` (all I/O on `Dispatchers.IO`), DTOs, `ApiResult`, mappers |
| `data/repo` | interfaces + network implementations: `DeviceSession` (register, `authed` with one re-register on 401), `AlertsRepository` (cache + notifications), `CheckRepository`, `ConsentRepository`, `FamilyRepository`, `AccountRepository` |
| `data/store` | `SecureTokenStore` (Keystore AES-GCM), `AppSettings`/`SettingsStore` (DataStore) |
| `data/system` | `SystemAccess` (live OS permission state, battery exemption, call-screening role), `LocaleController`, `ShareIntake`, `FcmTokenProvider` (placeholder) |
| `capture`, `sync` | always-on capture and delivery (see **Capture**) |
| `data/notify`, `data/image`, `data/demo` | notifier + channels, image downscaling (< 4.5 MB), demo timeline + runner |
| `ui/theme`, `ui/components` | design tokens (palette, Inter type scale, spacing/radii/motion) and the component library (large-title scaffolds, tab bar, grouped lists, buttons, sheets, tier glyphs, timeline, illustrations, `SentinelIcons`); previews in `Previews.kt` (light/dark, font scales, Hindi) |
| `ui/nav` | typed routes, root scaffold (bottom bar keeps each tab's state, hidden while the keyboard is up), deep link, share hand-off |
| `ui/onboarding`, `home`, `alerts`, `check`, `family`, `settings`, `demo` | screen + `ViewModel` (StateFlow state, one-shot `Events`) per feature |

Architecture rules: unidirectional data flow, no network in composables, ViewModels get interfaces, state restored
with `SavedStateHandle` (Check) and `rememberSaveable` (inputs, sheets, pager), `collectAsStateWithLifecycle`,
stable lazy keys. Content width is capped at 640 dp (`AdaptiveBox`) for tablets and landscape.

## Capture
Full description, privacy rules, limits and adb test recipe: [`../docs/capture.md`](../docs/capture.md).

| Piece | Where |
|---|---|
| Allowlist (+ debug-only `com.android.shell` in `src/debug`) | `capture/CaptureAllowlist.kt`, manifest `<queries>` |
| Listener → pipeline (gate, filter, extract, redact, sender policy, dedupe/rate limit) | `capture/SentinelNotificationListener.kt`, `CapturePipeline.kt`, `NotificationFilter.kt`, `SenderPolicy.kt`, `CaptureGuard.kt` |
| Redactor (port of `backend/app/pipeline/redact.py`) | `capture/Redactor.kt`; golden file from `scripts/gen-redact-golden.py` |
| Encrypted outbox, sender, alert dispatch, follow-ups | `sync/Outbox.kt`, `sync/EventSender.kt`, `sync/Workers.kt` |
| Calls: call log after each call, opt-in screening | `capture/CallCapture.kt`, `AndroidCallLog.kt`, `SentinelCallScreeningService.kt` |
| Status UI | Home capture card, Settings → Notification capture / Check callers while ringing / Battery |

Regenerate the redaction golden file after any change to `redact.py`:
`cd backend && uv run python ../android/scripts/gen-redact-golden.py`.

Quick phone test (debug build, live server): `./gradlew assembleDebug -PsentinelApiBaseUrl=https://interns.enthral.ai/sentinel`,
finish onboarding with Notifications on, then
`adb shell cmd notification post -S bigtext -t 'VM-HDFCBK' t1 'Your OTP is 482913. -HDFC Bank'` and watch
Settings → Notification capture (captured / sent / queued).

## Design system
The full spec is [`docs/design.md`](docs/design.md) (principles, colour, type, spacing, motion, components, do/don't,
accessibility). Everything is central in `ui/theme` + `ui/components`; screens only compose those parts.
* Colour: layered near-white / near-black neutrals (grouped background, background, elevated), one accent ("harbour
  ink" `#0C5A73` / `#71C4DA`), desaturated tiers (sage, amber, clay, crimson). No Material dynamic colour; a
  **true-black** dark option for OLED (Settings > Appearance). Every text/background and UI pair is contrast-tested in
  light, dark and true black (`PaletteContrastTest`, plus tier pairs in `TierTest`). Tiers are never colour-only: each has
  its own glyph shape (circle, diamond, triangle, octagon) and a label.
* Type: Inter (variable, subset to Latin, 86 KB; OFL in `third_party/inter/`) with a 10-step scale (`SentinelType`,
  34-sp large title to 12-sp caption), tabular figures for times, counts and codes. Hindi falls back to the system Noto
  Sans Devanagari with ~12% taller lines. Everything scales to 2.0x; the tab bar caps labels at 1.3x and the invite code
  (already 40 sp) at 1.3x.
* Shape and layout: 8-dp grid (`Spacing`), radii 10/14/20/28 (`Radii`), inset grouped lists with hairlines and chevrons,
  one soft shadow (light mode only), 48-dp targets, content capped at 640 dp.
* Chrome: large titles that scroll away under a compact bar with a hairline; tab bar with hairline and heavier
  selected icon (no pill); sheets for secondary flows (consent, privacy, delete, unlink); predictive back.
* Icons: `SentinelIcons`, ~40 hand-drawn 1.6-dp line icons (Material icons dependency removed); redrawn tier glyphs,
  shield mark, launcher and splash.
* Motion (all off with "remove animations"): shared-axis push/pop and fade-through tabs, breathing protected shield and
  state morph, one-time critical pulse, shimmer skeletons, press scale, list item animations; haptics on selection,
  feedback, copy and a critical alert opening.
* Localisation: `values/` (en) and `values-hi/`; no hard-coded text. Per-app language via `AppCompatDelegate` (works
  on API 26+; stored through `AppLocalesMetadataHolderService`). Server-written text (headlines, next steps, why
  items) is English as the backend sends it.

## Adding a screen
1. Add strings to both `values/strings.xml` and `values-hi/strings.xml` (`scripts/check-strings.py` and `StringsParityTest` enforce parity).
2. Add a route in `ui/nav/Routes.kt` and a `composable<...>` in `SentinelNavHost` (tabs also go in the `tabs` list).
3. Create `ui/<feature>/XxxViewModel` (a state class, `StateFlow`, `Events` for one-shots, dependencies as interfaces from
   `AppGraph`; add a `create(g: AppGraph)` factory) and `XxxScreen`/`XxxContent` (stateless content is easy to test).
4. Add a fake to `app/src/sharedTest/.../Fakes.kt` if you introduced a new interface, then unit tests with `MainDispatcherRule`.

## Tests and checks
```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug   # JVM, no device
./gradlew connectedDebugAndroidTest                                    # needs a running emulator/device
python3 scripts/check-strings.py                                       # en/hi key + placeholder parity
```
* 118 unit tests: ViewModels with fake repositories (onboarding consent order and registration gating, alert
  filtering/grouping, feedback optimistic/undo/commit/flush, check error mapping and input preservation, family
  countdown and code entry, delete flow, demo stepper), repositories against MockWebServer (re-register on 401,
  notification baseline, demo idempotency and payloads, delete semantics), API client, secure store, tiers and contrast
  (`PaletteContrastTest`: every text/background and UI pair of the palette in light, dark and true black).
* `SignalsTest` keeps three things in sync: `Signals.labels`, the checked-in `app/src/test/resources/backend_signals.txt`
  and (when the checkout contains it) the real `SIGNAL_WEIGHTS` keys parsed from `backend/app/pipeline/signals.py`.
  If the backend adds or renames a signal the test fails until the map, both languages and the list are updated.
  `StringsParityTest` fails if `values-hi` lacks a key, has an extra one, or changes placeholders.
* 3 Compose UI tests (`app/src/androidTest`): onboarding happy path to Home, alert detail renders the why timeline
  and feedback, Check error keeps the typed text. Fakes live in `app/src/sharedTest` (used by both source sets).
  Note: `connectedDebugAndroidTest` uninstalls the app afterwards, so app data on the emulator is cleared.

## Demo mode and release builds
`BuildConfig.DEMO_ENABLED` is true in debug and false in release (the Home action, Settings entry and runner are not
offered). Demo events are posted to the signed-in account with past dates; they are idempotent but they are real
rows in that account (delete-my-data removes them).

## Verified vs not
Verified on the `sentinel_pixel` emulator (API 34) against the live backend on `10.0.2.2:8000`:
onboarding -> consent (Notification access granted via the real system screen, `READ_CALL_LOG` via the real dialog) ->
registration + 5 consent POSTs -> Home "Protected"; demo mode -> critical alert detail; feedback (POST after undo window);
family invite creation with live countdown; Check text (live `/v1/check`); Check screenshot via the system photo picker
(preview and multipart upload reach the server; the backend's vision model is not configured here so the real answer
was `502`, shown as "paste the text instead"); share-target text; critical heads-up notification (channel, localised
title, private lock-screen version, tap -> alert detail, also while the app is open); `sentinel://alert/unknown` ->
"Alert not found"; Delete my data (type-to-confirm -> `DELETE /v1/me` 204 -> back to onboarding); light, dark and
Hindi screenshots of every screen (`docs/screenshots/`, refreshed for the redesign; `before/` keeps four pre-redesign
screens); font scale 2.0 on Home/Family/Check; landscape.
Found and fixed while doing this: response bodies were read on the main thread (crash), nav labels were truncated at
2.0x font, Check results triggered their own heads-up, notification taps while the app was open were ignored.

UI redesign (`feat/ui`), verified on the same emulator against the live backend: every screen in light, dark, true
black and Hindi, font scale 1.3 and 2.0 (no clipping; alert headlines wrap instead of truncating), landscape, live demo
-> critical alert, live Check (caution and "Looks fine" results), family invite with countdown ring, all sheets, offline
and error states with the backend stopped, a run with animations off. Found and fixed during the review: Settings kept
the old language checked after switching (stale state), Home said "Nothing suspicious was flagged today" when nothing
could be loaded, two retry buttons when offline, invisible code-entry cells. Release APK: 2.19 MB -> 2.25 MB (+52 KB,
mostly the font). Not verified for the redesign: spoken TalkBack output (semantics checked via UI dumps only), haptics
on real hardware (the emulator has no vibrator), predictive-back and transition smoothness on a physical device.

Not verified:
* Check with a screenshot that the server can read (vision model offline), image downscaling above 5 MB on a device,
  image share from another app (adb cannot grant the URI; the picker and the share intent filter are verified separately).
* Family accept path against the live backend (one install = one user; covered with unit tests and MockWebServer),
  guardian push (needs Firebase; the push token is a placeholder), the contact-sync `READ_CONTACTS` signal.
* TalkBack walkthrough, tablet layouts (width cap only), RTL, devices below API 34, the release APK at runtime (its
  base URL is a placeholder), `KeystoreTokenCipher` beyond the emulator run, OEM battery behaviour.
* Heads-up for alerts that arrive while the app is closed (needs sync/push, not part of this phase); consent changes
  made while offline are retried when Settings is reopened, not in the background.

Lint: 0 errors. Warnings left: dependency/AGP/Kotlin "newer version" (versions pinned for compileSdk 35), `OldTargetApi`,
`UnusedAttribute` (API 33+ manifest attributes), `DataExtractionRules`, three `UseKtx`, one `PluralsCandidate`,
one `ObsoleteSdkInt`.
