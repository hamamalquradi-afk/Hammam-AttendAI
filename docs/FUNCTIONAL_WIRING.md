# Functional Wiring Status

This document summarizes the production wiring completed on top of the existing architecture. It is not a replacement architecture specification.

## Local-first core

Room remains the source of truth for academic structure, students/teachers/subjects, weekly timetable drafts, lectures, attendance, appeals, audit, reports, settings, queue state, and integrity checks. Local operations commit before optional network delivery.

## Roles and daily flows

- System Owner: protected global administration, permissions, providers, backup/security, audit, health and academic management.
- Administrator: operational global administration without protected owner takeover.
- Supervisor/Representative/Assistant/Teacher/Student: repository-enforced academic or self scope.
- Representative/assistant attendance takeover reuses one active session and records attribution.
- First Run creates a single System Owner only when the database is uninitialized.

## Attendance and presence

Student device enrollment uses application identity/cryptographic material rather than IMEI or MAC address. Student BLE advertising and representative token resolution share the rotating-token contract. Advertising is active only in the relevant lecture window/session. QR/manual verification remains fallback. Technical BLE failures route uncertain outcomes to review rather than automatic absence.

## Timetable and reports

Weekly versions progress through draft/review/approval before future lecture materialization. Teacher report settings drive local daily/weekly/monthly/semester/custom report jobs, optional approval, deduplication, and queued delivery.

Finalized absence notifications are created only after attendance policy/confidence/review rules permit them. Temporary BLE loss alone does not create an absence message.

## AI and providers

OpenAI/Gemini/Claude support Local BYOK or Backend Managed mode with dynamic model discovery. Offline AI queries remain independent from cloud configuration. Provider keys are never stored in Room/DataStore.

## Local data/security tools

Encrypted full backup/validated restore, non-secret configuration export/import, student CSV preview/validation/confirm import, optional PIN/biometric app lock, language/theme selection, data-integrity checks, System Health and sanitized diagnostics are wired to the existing settings/admin surfaces.

## Queues

Sync, notification, and report state is persisted. WorkManager uses unique work names, network constraints where needed, and backoff. Notification/report deduplication and backend idempotency prevent duplicate sends. Automatic sync/notification retries stop after five failed attempts or immediately on permanent provider errors, moving the item to manual review.
