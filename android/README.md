# Sentinel Android

Native Android client (Kotlin, Jetpack Compose, Material 3). Phase 1 = toolchain, app skeleton, design system and a
typed API client for `docs/api-contract.md`. Capture, onboarding, storage and sync come in later phases
(see `docs/superpowers/plans/2026-10-17-sentinel-android.md`; note this app uses a hand-written `AppContainer`
instead of Hilt, OkHttp instead of Retrofit, and minSdk 26 instead of 29).

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
* Release URL is the placeholder `https://api.example.invalid`. **TODO: set the production API origin in `app/build.gradle.kts` (release buildType) and add signing.**

## Package map (`app/src/main/java/ai/enthral/sentinel`)
| Package | Role |
|---|---|
| `SentinelApp`, `AppContainer` | Application + hand-written DI (lazy singletons) |
| `domain` | `Tier`, `Source`, `CallDirection`, `FeedbackLabel`, `Alert`, `WhyItem` |
| `data/api` | `SentinelApi` interface, `OkHttpSentinelApi`, DTOs (exact contract names), `ApiResult` (Success / HttpError / NetworkError), mappers, `HttpClientFactory` |
| `data/store` | `SecureTokenStore` (AES-GCM via `TokenCipher`; `KeystoreTokenCipher` = Android Keystore), `SettingsStore` |
| `data/repo` | `DeviceSession` (register once, reset on 401), `AlertsRepository` |
| `ui/theme` | colours, type, shape + spacing tokens, `SentinelTheme` |
| `ui/components` | `TierBadge`, `AlertCard`, `EmptyState`, `ErrorState`, `LoadingSkeleton`, `PrimaryButton`, `SectionHeader` |
| `ui/nav` | `Destination` enum, `SentinelNavHost` (bottom bar: Home, Alerts, Check, Family, Settings) |
| `ui/home`, `ui/placeholder` | polished Home skeleton (live alerts via API), placeholders for the rest |
| `src/debug`, `src/release` | `addDebugLogging`: redacted header logging in debug, no-op in release (logging-interceptor is debug-only) |

Notes on the API client: it never throws; 422 `detail` lists are flattened to their messages; `NetworkError.reason` is only an exception class name; `EventIn` omits null optionals (omitted `is_contact` = unknown). `GET /v1/me` and `POST /v1/me/consents` are implemented from `backend/app/routes/me.py` because `docs/api-contract.md` does not document them yet.

## Design system
* Palette: calm teal/blue-grey ("trustworthy security tool"), light and dark schemes, **dynamic colour off** by default (`SettingsStore.useDynamicColor`, opt-in).
* Tier colours (`ui/theme/Color.kt`, `TierPalette`) are >= 4.5:1 content-on-container in both modes (enforced by `TierTest`). They are never the only signal: each tier has its own icon shape (circle / diamond / triangle / octagon) and a text label, and `TierBadge` is announced as "Risk level: X".
* Spacing: 8-dp grid tokens in `Spacing` (`x05`=4 ... `x6`=48, `minTouchTarget`=48 dp). Shapes: `SentinelShapes` (4/8/12/20/28 dp).
* Typography: M3 scale with line heights of 1.3-1.5x so Devanagari is not clipped; system font (Noto Sans Devanagari fallback), no font assets.
* All user-visible text lives in `res/values/strings.xml` and `res/values-hi/strings.xml`; per-app language via `android:localeConfig` (Android 13+ system settings, or `adb shell cmd locale set-app-locales ai.enthral.sentinel.debug --locales hi`).

## Adding a screen
1. Strings: add keys to both `values/strings.xml` and `values-hi/strings.xml`.
2. Add an entry to `ui/nav/Destination.kt` (top-level tab) or just a `composable(route)` in `SentinelNavHost` (pushed screen).
3. Create `ui/<feature>/XxxViewModel` (state as a sealed interface, repository injected through a `viewModelFactory`) and `XxxScreen`; get dependencies from `(application as SentinelApp).container`, and add new objects to `AppContainer`.
4. Reuse `ui/components`; use `Spacing`/theme tokens, no hard-coded text or dp literals.

## Verified vs not
Verified (this machine, Linux, Java 21):
* `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease` all pass; 33 unit tests (API client vs contract examples incl. 401/422/429/network/multipart/auth header, no token in debug logs, secure-store logic with a fake cipher, tier mapping and contrast).
* Emulator (`sentinel_pixel`, API 34, headless flags, KVM) boots; debug APK installs and runs; the app registered against the local backend on `10.0.2.2:8000` and fetched `/v1/alerts` (HTTP 200) with the Keystore-backed token store working on-device. Screenshots in `docs/screenshots/` (light, dark, Hindi); font scale 1.3 was checked visually on Home (no clipping).
* Release build is minified with R8 + resource shrinking; serializer classes are present in the release dex.

Not verified:
* Release APK was not installed/run (unsigned, placeholder URL); R8 keep rules are checked only by dex inspection, not by a runtime API call.
* `KeystoreTokenCipher` has no automated test (exercised only implicitly by the emulator run); no instrumented/Compose UI tests yet; no TalkBack pass; no physical device.
* Multipart image check and family/consent/feedback calls are tested against MockWebServer only, not the live backend.
* Predictive back and splash behave per platform defaults; only seen on API 34.

Lint warnings left (0 errors): `OldTargetApi`/`GradleDependency`/`NewerVersionAvailable` (libraries deliberately pinned to versions compatible with compileSdk 35; newer AGP 9.x/Kotlin 2.4/Compose BOM exist), `UnusedAttribute` for `enableOnBackInvokedCallback` and `localeConfig` (API 33+ attributes), `DataExtractionRules` (`allowBackup=false` is set).
