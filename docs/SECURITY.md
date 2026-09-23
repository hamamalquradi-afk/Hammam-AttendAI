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
- The backend reads provider credentials only from environment variables. In `APP_ENV=production`, pre-existing non-sync API authorization fails closed when `AUTH_TOKEN_HASH` is missing. In PHASE 5D, `AUTH_TOKEN_HASH` is also the controlled bootstrap/provisioning credential, but it is **not** a normal sync session and cannot bypass workspace membership enforcement.
- Server-side Account, Workspace, Membership and Session records use stable generated IDs. Account credentials and session tokens are generated with Node cryptographic randomness; only SHA-256 hashes are persisted. Session records have expiry and revocation state and survive backend restart. Raw credentials/tokens are returned only at issuance where required and are never written to request logs.
- Normal Cloud Sync requires a valid session plus ACTIVE membership in the requested workspace. `SYNC_WORKSPACE_ID` is retained only as an optional bootstrap workspace pin/legacy migration aid and is not an authorization shortcut. Sync idempotency is namespaced by workspace, with a narrowly-scoped compatibility lookup for the legacy 5C workspace only. Cross-workspace requests are rejected before sequence consumption, mutation persistence, idempotency success creation or pull delivery.
- Android stores deployment/account credentials and session tokens only in the Keystore-backed `SecureSecretStore`. Room and DataStore store no raw session/account credential. Backend Account authentication remains separate from local Role/Permission authorization; authenticating to the backend does not grant `SYSTEM_OWNER`, `Administrator` or local permissions.
- Request logs contain request id/path/status only and never Authorization headers, account credentials or session tokens.
- GitHub workflows use repository read-only permissions for Android jobs. Debug builds require no provider credentials or signing secrets. Release signing material is expected only through GitHub Secrets.

## PHASE 5E sync security boundary

Multi-entity sync does not weaken the PHASE 5D identity boundary. Push and Pull still require a valid account session and ACTIVE workspace membership before payload validation or persistence. Idempotency remains workspace-namespaced.

The only new Cloud Sync types are `Teacher` and `AttendancePolicy`; the latter is restricted to `scopeType = GLOBAL` and `scopeId = null`. This prevents a policy payload from smuggling a reference to an unsynchronized or cross-workspace scope. `Users`, `Roles`, `Permissions`, student-device data, PIN/biometric material, backend credentials, and session tokens are not synchronized.

New payload contracts are strict: required keys cannot be defaulted, envelope IDs/versions/timestamps must match payload values, unknown keys are rejected for the new types, and malformed stored mutations stop Pull with `SYNC_STORED_MUTATION_INVALID` rather than advancing the cursor. No delete/tombstone path is enabled in this phase.

## PHASE 5F academic hierarchy security boundary

PHASE 5F preserves the PHASE 5D Account → ACTIVE Membership → Workspace authorization boundary and the PHASE 5E encrypted outbox. The new hierarchy types do not create a weaker route around session authentication, and the workspace named by a payload is never treated as proof of access.

The backend performs strict payload validation and same-workspace parent validation before consuming a sequence or creating a successful idempotency record. A child cannot borrow a parent with the same ID from another workspace. Stored malformed hierarchy mutations remain poison-pill protected with `SYNC_STORED_MUTATION_INVALID`; they are not silently deleted, skipped, or cursor-advanced.

The v5 `sync_entity_metadata` sidecar stores only synchronization state: entity type/id, local version, first/last local sync timestamps, a SHA-256 semantic payload fingerprint, and the last confirmed server version. It stores no password hashes, PIN/biometric material, Account credentials, session tokens, Authorization headers, Keystore material, or device/presence secrets. Queue payloads continue to use the existing Keystore encryption path.

`User`, `Role`, `Permission`, `UserRole`, `RolePermission`, `UserScope`, `UserPermissionGrant`, student-device entities, and attendance presence secrets remain outside Cloud Sync. Backend Account membership remains distinct from Android local RBAC and does not grant local academic-management permissions.

## PHASE 5G academic graph security boundary

PHASE 5G retains the PHASE 5D Session → Account → ACTIVE Membership → Workspace authorization boundary for every new entity. Student, Subject, Lecture, AttendanceRecord, and AttendanceAppeal parent references are resolved against the same workspace's persistent entity state before sequence allocation or successful idempotency persistence. A same-ID parent in another workspace cannot satisfy the reference.

Student Cloud Sync is data-minimized: `registeredDeviceId`, `StudentDevice`, device public identifiers/keys, encrypted presence secrets, PIN/biometric material, account/session credentials, Authorization headers, and Keystore material are excluded. AttendanceRecord synchronization contains business attendance results only; `AttendanceSession`, `PresenceInterval`, `PresenceEvent`, and raw presence telemetry remain local-only.

Subject policy closure remains deliberately narrow. Only the already-supported GLOBAL AttendancePolicy (`scopeId = null`) is eligible. PHASE 5G does not open SUBJECT/GROUP/LEVEL or arbitrary policy scopes, preventing a Subject payload from smuggling a scope reference that the backend cannot prove belongs to the same workspace.

The backend enforces workspace-local uniqueness for Student university identifiers when present and for the `(lectureId, studentId)` AttendanceRecord pair. Strict payload validation runs before persistence, and malformed stored Student/Subject/Lecture/AttendanceRecord mutations continue to stop Pull with `SYNC_STORED_MUTATION_INVALID`. Same-version semantic divergence becomes a conflict; a newer remote row does not overwrite a local unresolved mutation.

Legacy AttendanceAppeal compatibility does not create an authorization bypass. A legacy Appeal is replayed only after all four required parents exist in the same workspace and the AttendanceRecord/Lecture chain is logically consistent. Raw pending mutations are still poison-validated before any closure-history filtering, so malformed data is never hidden merely to advance the cursor.
