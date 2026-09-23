# Architecture
Hammam AttendAI is an Android-first offline-first monorepo. The Android Room database is the source of truth for local operation. Layers are split into UI/presentation, domain, local data, BLE, queues/sync, reports, AI, security and backup.

Critical writes use Room transactions. Remote work is represented by durable queue rows and retried with WorkManager. Cloud services are optional adapters, never prerequisites for students, lectures, attendance or local reports.

The same APK supports multiple roles. Authorization data is modeled as roles, permissions, role_permissions and user_roles so policy is data-driven rather than a collection of hard-coded screens.
