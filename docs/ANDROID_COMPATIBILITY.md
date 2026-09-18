# Android Compatibility

## Supported baseline

- **Minimum Android:** Android 7.0 / API 24
- **compileSdk:** 35
- **targetSdk:** 35
- **Application ID:** `com.hammam.attendai`
- **Database version:** 4
- **Java/Kotlin bytecode target:** JVM 17
- **Core library desugaring:** enabled (`desugar_jdk_libs 2.1.4`) for `java.time` and related APIs on older Android versions.

This round changed compatibility behavior only. It did **not** change the application architecture, package name, routes, or Room schema.

## Dependency compatibility audit

The current dependency set was retained. No dependency was upgraded or downgraded merely for recency. The project uses AndroidX/Compose libraries whose declared baseline is compatible with API 24. The local environment could not resolve Maven/Gradle artifacts because outbound DNS is blocked, so final merged-manifest/minSdk verification remains part of the GitHub Actions build.

Key retained versions:

- Android Gradle Plugin 8.7.3
- Gradle 8.9
- Kotlin / Compose plugin 2.0.21
- Compose BOM 2025.01.01
- Activity Compose 1.10.0
- Lifecycle 2.8.7
- Navigation Compose 2.8.5
- Room 2.6.1
- WorkManager 2.10.0
- DataStore 1.1.1
- Biometric 1.1.0

## API guards

- Foreground services are launched through `ContextCompat.startForegroundService` rather than a direct API-26-only call.
- Notification channels are created only on API 26+.
- `POST_NOTIFICATIONS` is requested/checked only on API 33+.
- Bluetooth runtime permissions are split by platform generation.
- Modern date/time code is supported on API 24 through core-library desugaring.
- PBKDF2-HMAC-SHA256 keeps the same output format; API 24-25 use an HMAC-SHA256 fallback when the platform provider does not expose `PBKDF2WithHmacSHA256`.

## BLE behavior by Android version

| Android range | BLE runtime behavior |
|---|---|
| API 24-30 | Legacy Bluetooth manifest permissions plus runtime `ACCESS_FINE_LOCATION` for scanning. |
| API 31-32 | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; advertising also checks `BLUETOOTH_ADVERTISE`. |
| API 33+ | Same Nearby Devices model; notifications are independently gated by `POST_NOTIFICATIONS`. |
| Newer Android versions | The app uses the same permission-aware paths and does not assume continuous background scanning is guaranteed. |

BLE failures such as revoked permission, disabled Bluetooth, scanner errors, or foreground-service start failure are treated as technical detector issues. They are persisted on the active attendance session using the existing `activeDetector` field and force affected attendance to **Manual Review** rather than turning a technical outage directly into a final absence.

The app does not use MAC address, IMEI, or other prohibited hardware identifiers as student identity. The existing rotating token/device identity design remains unchanged.

## Foreground service

The app now has two attendance-related foreground services, each with a narrow responsibility:

- `AttendanceForegroundService`: representative-side attendance detection/scanning.
- `StudentPresenceService`: student-side BLE presence advertising during an eligible lecture window after explicit in-app start.

Both services are declared with `android:exported="false"` and the `connectedDevice` foreground-service type.

- API 24-25: compatible notification path without notification channels.
- API 26+: low-importance foreground-service notification channels are used.
- API 31+: foreground-service launch failure is caught and converted to a technical issue/fallback state rather than an attendance decision.
- API 34+: the manifest includes the required `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission for the declared type.

A foreground-service failure does not finalize students as absent automatically. QR/manual fallback and review remain available. Physical-device foreground-service behavior has not yet been tested in this packaging environment.

## Notifications

- Notification channels are API-guarded to 26+.
- Runtime notification permission is API-guarded to 33+.
- Older Android versions do not receive requests for nonexistent notification permissions.
- Remote WhatsApp/email delivery remains optional and queue based; local attendance does not depend on it.

## Storage and attachments

- Attendance appeal attachments use the Storage Access Framework (`OpenDocument`) and persistable content URIs.
- Attachments are validated for readability, type, and size; large file blobs are not stored in Room.
- Diagnostic export uses the Storage Access Framework (`CreateDocument`).
- The manifest requests no broad external-storage permission and contains no hard-coded shared-storage path.
- Report and encrypted-backup staging files use app-private files unless explicitly exported by the user-facing flow.

## Date, time, and clock safety

Wall-clock timestamps remain the historical source for dates, reports, persistence, and post-reboot recovery. During an active process lifetime, `SessionClockGuard` compares wall time with monotonic `elapsedRealtime()` progression. A material clock anomaly becomes sticky for the session and forces Manual Review rather than accusing a student or silently accepting corrupted duration math.

`elapsedRealtime()` is deliberately **not** used as the sole persisted clock because it resets after reboot.

## Adaptive UI and accessibility hardening

The existing Compose UI architecture was retained. Compatibility patches use current layout primitives rather than a new adaptive framework:

- long lists use `LazyColumn`/`LazyRow`;
- dashboard metrics collapse to one column below a compact width threshold;
- forms/dialogs are scrollable where needed;
- IME padding is applied to editable screens;
- First Run uses safe-drawing padding under edge-to-edge mode;
- bottom navigation does not force all labels visible at once;
- identifiers such as university numbers, IDs, versions and diagnostic numeric values are rendered in local LTR context instead of reversing strings;
- Arabic text relies on Android/Compose BiDi and RTL handling, never manual reversal;
- no fixed screen-level width or fixed text height was introduced.

Tablet two-pane navigation was intentionally not added because that would require a navigation redesign outside this hardening round.

## State restoration and offline durability

- selected student, first-run input, appeal filter/context, AI query, tabs and detail route use `rememberSaveable` and/or `SavedStateHandle` as appropriate;
- active lecture/session state, attendance records, queues, reports and notifications remain persisted in Room;
- WorkManager uses unique work names and network constraints for internet-dependent queues;
- report generation remains runnable offline before remote delivery;
- process death or app restart does not rely on in-memory attendance state as the source of truth.

## Manifest and hardware availability

BLE is declared with `android:required="false"`, so a device without BLE is not blocked from installation. The application can fall back to QR/manual workflows. No broad storage feature is required. No new hardware requirement was added in this round.

## Validation boundary

This file records **static compatibility validation** only. No APK was installed on a physical device or emulator in the packaging environment. Local Gradle execution stopped before compilation because `services.gradle.org` could not be resolved. GitHub Actions is configured to perform the real dependency resolution, tests, lint, and APK compilation.
