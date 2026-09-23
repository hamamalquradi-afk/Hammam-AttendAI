# Offline Sync
Every important local mutation is committed to Room first. Remote operations are queued with payload, retry count, status and idempotency key. WorkManager with a network constraint retries when connectivity returns.

Conflict resolution uses version + updated time + device context. A conflict that cannot be resolved deterministically is marked NEEDS_MANUAL_REVIEW rather than overwriting silently.

## Authenticated workspace sync
Cloud sync has no implicit workspace fallback. PHASE 5D adds persistent server-side Account, Workspace, Membership and Session records. Normal `/api/v1/sync/push` and `/api/v1/sync/pull` requests require an unexpired, non-revoked session and an ACTIVE membership for the requested workspace. The workspace field in a request is therefore a requested scope, not proof of authorization.

`AUTH_TOKEN_HASH` is retained as a deployment/bootstrap credential for controlled account provisioning and for pre-existing non-sync backend service routes. It is not accepted as a normal sync session. `SYNC_WORKSPACE_ID` is retained only as an optional bootstrap workspace pin and legacy compatibility aid; normal authenticated sync derives authority from Account -> Membership -> Workspace.

Android stores the account credential and active session token only in `SecureSecretStore`. DataStore may contain only non-secret metadata such as account ID, selected workspace, session expiry time and authentication state. If the session is missing, expired or revoked, sync stops before claiming new queue items. If authentication fails after a queue item was claimed, that item returns to `PENDING` without increasing its retry count. Pull authentication failures occur before applying a batch or advancing the cursor. Local timetable, attendance, reports and the offline queue remain available independently of backend authentication.

## PHASE 5E — Multi-Entity Sync Expansion Foundation

PHASE 5E deliberately expands the unified workspace sync stream by only two root/reference entities: `Teacher` and `AttendancePolicy`. `AttendanceAppeal` remains supported unchanged. Only `GLOBAL` attendance policies with `scopeId = null` are eligible for Cloud Sync in this phase.

The selection is dependency-driven. Teacher is a root academic reference, while the currently-created attendance policies are global roots. The academic hierarchy (`University` → `Faculty` → `Department` → `Level` → `Batch` → `Section` → `Group`, plus `AcademicYear` → `Semester`) and dependent `Student`, `Subject`, `Lecture`, and `AttendanceRecord` are intentionally deferred until their required parent closure can be synchronized safely. Security/RBAC/device entities and runtime presence/session entities remain local-only.

All three supported types use the same authenticated workspace stream, sequence, pagination, cursor, workspace-aware idempotency, version-conflict rules, and poisoned-mutation protection. Teacher/policy outbox payloads are encrypted with the existing keystore cipher. An authentication failure preserves queued work. Pull validates every change before applying the batch; local changes and the final cursor update remain in one Room transaction.

PHASE 5E supports `UPSERT` only. No hard-delete or tombstone contract is introduced. Teacher archival is represented by the existing `archivedAt` field. Scoped attendance policies are rejected until their scope entities are part of a future synchronized parent graph.

## PHASE 5F — Academic hierarchy sync semantics, backfill and parent closure

PHASE 5F closes the synchronized parent graph required before `Student` and `Subject` can be considered in a later phase. The unified stream now supports `University`, `AcademicYear`, `Faculty`, `Department`, `Level`, `Semester`, `Batch`, `Section`, and `Group` in addition to the PHASE 5E types. `Student`, `Subject`, `Lecture`, and `AttendanceRecord` remain deliberately unsupported.

Most legacy hierarchy tables did not contain durable sync versions or timestamps. Rather than rewriting those tables, Room schema v5 adds one generic `sync_entity_metadata` sidecar keyed by `(entityType, entityId)`. Legacy rows receive a deterministic baseline (`localVersion = 1`; a fixed baseline timestamp when the original row has no trustworthy timestamp). The payload fingerprint excludes sync-only `version` and `updatedAt`, so restarting the application cannot manufacture a new version. A semantic payload change increments the sidecar version exactly once and clears its last-synced server version.

Initial backfill is local and idempotent. Existing Teacher/GLOBAL AttendancePolicy rows from PHASE 5E and all nine hierarchy entity types are scanned without requiring account authentication or network I/O. Backfill also runs from application startup, not only from the network-constrained sync worker. Deterministic queue IDs prevent an already-SENT version from being reopened or duplicated. If `CLOUD_SYNC` is disabled, no backfill queue work is created.

Hierarchy queue processing is dependency-aware rather than relying on creation time. Parents are prioritized before children: `University` and `AcademicYear`, then `Faculty` and `Semester`, then `Department`, `Level`, `Batch`, `Section`, and `Group`. Before a child is pushed, Android requires the required parent metadata to have a confirmed server version. A transient parent failure therefore returns the child to `PENDING` instead of turning it into a permanent orphan failure.

The backend enforces the same logical references in the persistent workspace store. Faculty requires University; Department requires Faculty; Level requires Department; Batch requires both Level and AcademicYear; Section requires Batch; Group requires Section; and Semester requires AcademicYear. Semester dates must also lie within its AcademicYear. Cross-workspace parent references are rejected before sequence consumption or successful idempotency persistence.

Pull remains one workspace sequence/cursor stream. Each hierarchy payload is strictly validated before apply. Parent and child mutations retain server sequence ordering, and local apply plus sync metadata plus final cursor update occur under the existing Room batch transaction. A batch failure leaves the cursor unchanged. Same-version different-payload data becomes an explicit conflict; a newer remote row cannot overwrite a local unresolved mutation. No hard-delete or tombstone sync is added: existing `archivedAt`, `isActive`, and Semester `status` fields carry lifecycle state.

## PHASE 5G — Student, Subject and attendance graph sync expansion

PHASE 5G extends the existing authenticated unified stream without changing the Room schema. `Student`, `Subject`, `Lecture`, and `AttendanceRecord` now reuse the PHASE 5F `sync_entity_metadata` sidecar for deterministic versions, semantic fingerprints, and last-confirmed server versions. Existing rows are backfilled locally and idempotently before network authentication is required; deterministic queue IDs prevent an already-SENT entity/version from being reopened.

The synchronized dependency graph is now `Academic hierarchy → Student`, `Teacher + Level + Semester + Group + GLOBAL AttendancePolicy → Subject`, `Subject + Teacher + Semester + Group → Lecture`, and `Student + Lecture → AttendanceRecord`. `AttendanceAppeal` is parent-closed against Student + AttendanceRecord + Lecture + Subject. `TeacherSubject` is not a separately synchronized entity because it is derived from `Subject.teacherId` and is rebuilt locally when a remote Subject is applied. `Timetable` and `WeeklyTimetableVersion` remain local planning inputs; a materialized Lecture already carries every server-side parent required for deterministic sync.

Student synchronization deliberately excludes `registeredDeviceId` and all `StudentDevice` data. Device enrollment can continue to increment the Student table's native row version without generating a semantic Cloud Sync mutation. Student academic updates use the generic sidecar fingerprint instead. Legacy imported students with no academic scope are supported; synchronized scoped students must carry a complete Level/Batch/Section/Group chain.

Subject synchronization currently requires a Teacher and a GLOBAL AttendancePolicy. PHASE 5G does not broaden AttendancePolicy scope types. A local Subject that references a scoped policy cannot bypass this boundary: its parent is never considered server-confirmed, so the Subject remains pending rather than being sent with an unverifiable scope.

Queue processing is dependency-aware: existing root/reference and hierarchy entities precede Student/Subject, then Lecture, AttendanceRecord, and finally AttendanceAppeal. A child remains `PENDING` when a required parent is not yet confirmed server-side. Pull remains a single monotonically sequenced workspace stream; the complete batch is validated before apply and the cursor is committed in the same Room transaction as the accepted changes.

Attendance synchronization carries only business attendance state. `AttendanceSession`, `PresenceInterval`, `PresenceEvent`, BLE/device secrets, and raw presence telemetry remain local/runtime data. No hard-delete or tombstone protocol is introduced; existing status/archive semantics remain authoritative.

Legacy AttendanceAppeal mutations created before their parents were cloud-synchronized are preserved. Once the full parent closure is present on the server, the current legacy Appeal state is re-anchored at a later sequence. Pull suppresses only the superseded historical Appeal copies associated with that closure replay, after poisoned-mutation validation, so a fresh device receives the parents before the Appeal without silently deleting legacy data.
