# Hammam AttendAI | همّام للحضور الذكي

Android-first, offline-first university attendance platform. The repository contains the native Android app, an optional React/Vite administration dashboard, a lightweight TypeScript backend for cloud-only adapters, documentation and CI workflows.

## Core principles
- Room is the local source of truth.
- Students, timetable, lectures, attendance, manual review, local reports, audit and backups work without Internet.
- WhatsApp, email, cloud AI, remote backup and optional cloud sync are queued and retried when online.
- BLE is treated as probabilistic presence evidence, never precise distance.
- The app does not use facial recognition, IMEI, GPS history or BLE MAC addresses as student identity.

## Repository
- `android/` Native Kotlin + Jetpack Compose + Room + WorkManager + DataStore.
- `backend/` TypeScript Node service under `/api/v1/` with auth hook, rate limiting, idempotency and provider relays.
- `web/` React + TypeScript + Vite optional admin UI.
- `docs/` architecture and operations documentation.
- `scripts/` local validation helpers.
- `.github/workflows/` Android and service CI.

## Android architecture
Clean boundaries: presentation/UI, domain attendance logic, data/Room, BLE, sync/queues, notifications, reports, AI, backup and security. A small `AppContainer` provides dependency injection without requiring a cloud runtime.

## BLE attendance
Scanning starts only around active lecture windows. A short loss enters a configurable grace state. Student advertisement uses rotating non-personal identifiers. Android background BLE is not guaranteed due to OS/vendor restrictions, therefore active-session persistence, foreground operation and QR/manual fallbacks are part of the design.

## Offline queues
Notification, report and sync records are stored locally before network work. WorkManager uses network constraints and exponential backoff. Idempotency and unique deduplication keys prevent duplicate sends.

## Security
No API keys, WhatsApp tokens, SMTP passwords or keystores are committed. Android Keystore protects local ciphertext keys. PIN hashes use PBKDF2. Backups use passphrase-derived AES-GCM encryption and integrity verification. `.env.example` lists backend configuration names only.


## Current functional wiring
- Database version 4 with preserved migration path `1→2→3→4`.
- Protected `SYSTEM_OWNER`, scoped roles, and temporary scoped permissions.
- Student device enrollment/replacement approval, rotating BLE presence, QR/manual fallback, and controlled representative takeover.
- Weekly versioned timetable import from camera/image/PDF/document/manual entry with Draft → Review → Approval before future lecture materialization.
- Teacher report settings/approval, automatic post-finalization absence jobs, persisted offline queues, and bounded retries.
- OpenAI/Gemini/Claude provider management with Local BYOK or Backend Managed mode, dynamic model discovery, and offline assistant fallback.
- Encrypted backup/restore, non-secret configuration backup, CSV import/export, App Lock, data-integrity checks, System Health, and sanitized diagnostics.

See `docs/FUNCTIONAL_WIRING.md`, `docs/ROLE_PERMISSIONS.md`, `docs/AI_PROVIDERS.md`, and `docs/TIMETABLE_IMPORT.md`.

## Build Android
With Android SDK and network available:
```bash
./gradlew :android:app:testDebugUnitTest
./gradlew :android:app:lintDebug
./gradlew :android:app:assembleDebug
```
The APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Termux
See `TERMUX_SETUP.md`. Termux is intended for unzip + Git + push, not as a required Android build environment.

## Backend
```bash
cd backend
npm install
npm run typecheck
npm run build
npm test
```
The backend intentionally remains useful with every external provider disabled.

## Web
```bash
cd web
npm install
npm run build
```

## External credentials
Automatic WhatsApp requires provider credentials. Cloud AI requires API/provider credentials. Email requires provider credentials. These are backend environment variables and are not needed for a debug APK.

## Backup
Encrypted `.hammamattend` style backup logic validates magic/version/integrity before restore. Backups exclude provider secrets.

## Known Android BLE limitations
Background scanning can be throttled or interrupted by Doze, OEM battery management, Bluetooth state, runtime permission changes and process termination. RSSI is noisy and is not a distance measurement. Critical attendance conclusions therefore use grace periods, confidence scoring, fallback verification and manual review.

## Owner
Developer / Owner: **Hammam**
