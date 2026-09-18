# Security
- No cloud provider credentials are present in the Android source or repository.
- Queue/settings payloads can be encrypted with Android Keystore AES-GCM.
- Optional local PIN uses PBKDF2-HMAC-SHA256; biometric support is available through AndroidX Biometric.
- Backup archives use PBKDF2 + AES-GCM and contain an integrity digest.
- Backend tokens are environment variables and logs intentionally avoid passwords/tokens.
- Academic changes are auditable; hard delete is not used for historical attendance.
- The application does not collect facial templates, GPS history, IMEI or persistent BLE MAC identity.

## Final security review

- Local AI BYOK credentials are stored only by `SecureSecretStore` in `noBackupFilesDir`, encrypted with Android Keystore. They are not stored in Room, DataStore, diagnostics, configuration export, or backup files. The UI exposes only a masked suffix and supports replacement/deletion.
- App-lock PINs are never stored as plaintext. `PinHasher` stores PBKDF2-SHA256 salt/hash material, itself protected by the same no-backup encrypted secret store. Verification uses a constant-time accumulator and biometric remains optional with PIN fallback.
- Audit exports intentionally contain attribution/action metadata, not provider secrets. Provider-key changes are represented by configuration state rather than raw key values.
- Full database backup is encrypted with AES-GCM and a PBKDF2-SHA256-derived key. Configuration export deliberately excludes users, students, attendance history, provider secrets, tokens, passwords, and Keystore material.
- The backend reads provider credentials only from environment variables. In `APP_ENV=production`, API authorization fails closed when `AUTH_TOKEN_HASH` is missing; local development can remain unconfigured. Request logs contain request id/path/status only and never Authorization headers.
- GitHub workflows use repository read-only permissions for Android jobs. Debug builds require no provider credentials or signing secrets. Release signing material is expected only through GitHub Secrets.
