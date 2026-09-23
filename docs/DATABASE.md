# Database
Room/SQLite with WAL is configured in `AppContainer`. Current schema version is 4. Production code registers the explicit migration path `1→2→3→4` and never calls `fallbackToDestructiveMigration`.

The schema includes authorization, academic hierarchy, students/devices, teachers/subjects, timetable/lectures, sessions, attendance records, presence intervals/events, appeals/excuses, notifications, report settings/jobs/generated reports, audit logs, sync queue, settings, feature flags and backup history.

UUID strings are primary keys. University number is unique when non-null. Academic data uses archive fields instead of hard delete. Syncable records carry versions and timestamps.
