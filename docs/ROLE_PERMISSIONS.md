# Roles, Permissions, and Academic Scope

Authorization is enforced in repositories/use cases as well as navigation. Hiding a tab is not treated as a security boundary.

| Role | Default data scope | Core access |
|---|---|---|
| SYSTEM_OWNER | Global | Full administration, users/roles/permissions, academic structure, timetable, attendance, reports, audit, providers, backup/security/system health |
| ADMINISTRATOR | Global operational scope | Operational administration, subject to protected System Owner controls |
| ACADEMIC_SUPERVISOR | Assigned academic scope | Read/review/manage functions explicitly granted inside assigned scope |
| REPRESENTATIVE | Assigned batch/section/group | Students, attendance, reports, appeals; timetable only with `MANAGE_TIMETABLE` |
| ASSISTANT_REPRESENTATIVE | Assigned academic scope | Reduced representative access; takeover only with `TAKE_OVER_ATTENDANCE`; no timetable management by default |
| TEACHER | Own subjects and their students | Own attendance/report context and permitted appeal/review actions |
| STUDENT | Self only | My attendance/history/current lecture/device/appeals/presence status |

## System Owner protection

The initial owner is created only on an uninitialized database. Security is based on role/permission, not the display name. Administrator-level actions cannot disable, demote, delete, or reassign the protected System Owner.

## Scoped and temporary permissions

Permission checks combine role permissions with academic/student scope. Version-4 schema supports scoped temporary grants with optional start/expiry timestamps, grantor, reason, and active state. Expired grants stop authorizing actions without deleting the audit history. Grant/revoke activity is recorded in the audit log.

Examples include `MANAGE_TIMETABLE`, `TAKE_OVER_ATTENDANCE`, `APPROVE_REPORT`, and `EDIT_ATTENDANCE`.

## Attendance takeover

Only one active attendance session is used for a lecture. An authorized assistant can take over the existing host session within the same scope; a second session is not created. Handover attribution is retained through audit records.
