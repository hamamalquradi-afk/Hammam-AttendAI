# Real Device Test Matrix

## Status legend

- `NOT_TESTED`: not executed on emulator or physical hardware.
- `STATIC_VALIDATED`: source/configuration path reviewed, but not executed on that platform.
- `EMULATOR_TESTED`: executed on an emulator.
- `PHYSICAL_DEVICE_TESTED`: executed on a real device.

No emulator or physical-device execution was available during this packaging round. Do not interpret `STATIC_VALIDATED` as runtime certification.

## Android version matrix

| Platform | API | Static status | Emulator | Physical device | Required checks after APK |
|---|---:|---|---|---|---|
| Android 7.0/7.1 | 24/25 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | install, first run, Room open, PBKDF2 PIN/backup, BLE scan, legacy notification path, SAF attachment |
| Android 8.x | 26/27 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | foreground notification channel, background/service recovery, BLE |
| Android 9 | 28 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | BLE scan, battery optimization, backup/export |
| Android 10 | 29 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | BLE/location permission behavior, SAF, process restart |
| Android 11 | 30 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | BLE/location permission behavior, scoped-storage-safe flows |
| Android 12/12L | 31/32 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | Nearby Devices permissions, FGS restrictions, permission revoke/re-enable |
| Android 13 | 33 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | `POST_NOTIFICATIONS`, Nearby Devices, background/restart |
| Android 14 | 34 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | `connectedDevice` FGS requirements, notification denial, battery saver |
| Android 15 | 35 | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | targetSdk 35 behavior, FGS, edge-to-edge, split screen |
| Android 16 / newer available device | 36+ | STATIC_VALIDATED | NOT_TESTED | NOT_TESTED | forward-compatibility smoke test; project currently compiles/targets SDK 35 and does not claim API-36-specific validation |

## BLE recovery matrix

| Scenario | Expected behavior | Static status |
|---|---|---|
| Bluetooth off before/during lecture | detector reports technical error; attendance remains reviewable; QR/manual fallback available | STATIC_VALIDATED |
| Bluetooth enabled later | representative can resume detection; session persists in Room | STATIC_VALIDATED |
| Scan/connect permission revoked | SecurityException is contained; detector issue is persisted; no crash/final automatic absence | STATIC_VALIDATED |
| Advertising permission revoked | student advertiser returns failure instead of crashing | STATIC_VALIDATED |
| Battery saver / background restriction | service failure is treated as detector technical issue; session remains persisted | STATIC_VALIDATED |
| Scanner callback failure | detector enters error state and session is marked for review | STATIC_VALIDATED |
| Student disappears temporarily | existing grace period/state machine is retained | STATIC_VALIDATED |
| Student returns | existing interval/re-detection path continues the same attendance record | STATIC_VALIDATED |
| Device clock changes materially | clock anomaly forces Manual Review | STATIC_VALIDATED |
| Device reboot | persisted wall-clock/session data are used; monotonic timer is not assumed to survive reboot | STATIC_VALIDATED |

## Screen/form matrix

The following are source-level checks. Every cell still requires emulator/physical verification after the APK is generated.

| Layout profile | Arabic/RTL | Mixed Arabic-English | Numbers/IDs | Long names | Font 200% | Keyboard/IME | Filters/tabs | Dialogs | Bottom nav | Reports/forms |
|---|---|---|---|---|---|---|---|---|---|---|
| SMALL_PHONE | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| NORMAL_PHONE | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| TALL_PHONE | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| LARGE_PHONE | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| TABLET | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| PORTRAIT | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| LANDSCAPE | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |
| SPLIT_SCREEN | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED | STATIC_VALIDATED |

## Dataset/performance smoke matrix

Runtime profiling has not been performed. The current list screens use lazy containers where the UI may hold many rows. Before production rollout, populate non-real test data and record scroll/search latency at these sizes:

- 100 students: NOT_TESTED
- 300 students: NOT_TESTED
- 500 students: NOT_TESTED
- 1000 students: NOT_TESTED

Verify Live Attendance, Students, Search, Reports, and Presence-event maintenance. Do not use real student records for this test.

## Critical real-device acceptance checklist

- [ ] Fresh install on API 24 succeeds.
- [ ] Upgrade through representative DB paths `1→2→3→4`, `2→3→4`, and `3→4` succeeds without data loss.
- [ ] App opens without Bluetooth hardware.
- [ ] BLE permission denial/revocation does not crash the app.
- [ ] Foreground attendance notification behaves correctly for the tested Android version.
- [ ] Active lecture survives activity recreation and app restart.
- [ ] Temporary BLE loss and return preserve one attendance record with multiple presence intervals.
- [ ] Technical BLE outage results in Manual Review, not automatic final absence.
- [ ] Offline report generation survives process/app restart.
- [ ] Pending sync/notification jobs resume only after connectivity and do not duplicate sends.
- [ ] Attendance Appeal can be submitted offline with/without an attachment.
- [ ] Appeal attachment remains readable after restart through persisted SAF URI permission.
- [ ] Arabic RTL, mixed `BLE/AI/PDF/CSV`, university numbers, percentages, times, and version numbers display in correct order.
- [ ] 200% font scaling keeps primary actions reachable.
- [ ] Keyboard does not hide submit/save actions.
- [ ] Portrait, landscape and split-screen remain usable.
- [ ] Diagnostic export contains technical metadata only and no student PII or credentials.
