# Hammam AttendAI — Global Defect Ledger

Scope checkpoint: PHASE 5H-RUNTIME-REPAIR-2

| ID | AREA | SCREEN/FILE | DESCRIPTION | SEVERITY | SOURCE_CONFIRMED | RUNTIME_CONFIRMED | CURRENT_ROUND | FUTURE_ROUND | STATUS |
|---|---|---|---|---|---|---|---|---|---|
| D001 | Local Data / CSV | LocalDataToolsScreen / AdminOperationsViewModel | Student CSV picker could crash when SAF launch failed. Repair-1 added guarded launcher/fallback. | HIGH | YES | YES | NO | Device retest | FIXED_ALREADY |
| D002 | Configuration export | LocalDataToolsScreen / AdminOperationsViewModel | Configuration CreateDocument path could crash and output stream could be null. Repair-1 hardened both. | HIGH | YES | YES | NO | Device retest | FIXED_ALREADY |
| D003 | Localization | values/strings.xml | Arabic assistant title still contains English product wording instead of natural Arabic presentation. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D004 | Localization | SettingsScreen | Backend account state is rendered as raw machine value. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D005 | Localization | SettingsScreen | Feature flag codes are displayed raw instead of localized presentation labels. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D006 | Localization | ReportsScreen | Report type is rendered as raw persisted value. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D007 | Localization | AcademicStructurePane | Semester status is embedded raw in dropdown presentation. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D008 | RTL / technical text | AiProviderCard | Provider/model identifiers are not consistently isolated LTR inside Arabic layout. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D009 | RTL / technical text | SettingsScreen | Backend URL, workspace/token and other technical fields are not consistently forced LTR. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D010 | Localization | UiText.kt | Central internal-state mapping is incomplete for auth/provider/theme/model states. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D011 | RTL navigation | SettingsScreen | Fixed left chevrons are semantically wrong in English LTR mode. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D012 | AI provider health | AiProviderManager | BACKEND_MANAGED is considered configured even when backend endpoint is absent. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D013 | AI provider health | AiProviderManager | Refreshing model catalog incorrectly marks provider CONNECTED although selected model was never tested. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D014 | AI Test Connection | AiProviderManager | Test Connection validates model listing only, not the currently selected provider/model request path. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D015 | AI state | AiProviderManager | Key/mode/model changes do not invalidate prior test status, allowing stale CONNECTED state. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D016 | AI state | AiProviderManager / UI | Not configured, configured-not-tested, auth failure, unsupported model, network failure and provider failure are not distinctly represented. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D017 | AI errors | AiProviderManager / AdminOperationsViewModel | Provider failures can collapse to generic ERROR and incomplete user-facing mapping. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D018 | AI Assistant UX | AiAssistantViewModel / Screens.kt | Assistant is single query/answer form, not a conversation. | HIGH | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D019 | AI Assistant state | AiAssistantViewModel | Duplicate request prevention exists only through button disabling; ViewModel does not independently reject repeated sends. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D020 | AI Assistant errors | AiAssistantViewModel | Raw exception messages can be copied into UI error state. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D021 | AI Assistant localization | AiAssistant.kt | Offline assistant response text is hard-coded Arabic and leaks into English mode. | HIGH | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D022 | AI Assistant language | OfflineIntentParser | Local intent recognition is Arabic-only, so English UI has reduced real capability. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D023 | AI Assistant UX | AiAssistantScreen | Current provider/model indicator is absent. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D024 | AI Assistant UX | AiAssistantScreen | New/clear conversation action is absent. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D025 | AI Assistant UX | AiAssistantScreen | Composer is part of scrolling form instead of stable bottom chat composer. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D026 | AI Assistant UX | AiAssistantViewModel | No message-level retry path after failed request. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D027 | AI Assistant persistence | AiAssistantViewModel | No durable chat-history storage exists. Schema changes are forbidden; session-only history required. | INFO | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D028 | AI authorization | AdminOperationsViewModel | Provider configuration is protected by MANAGE_AI_PROVIDER permission. | INFO | YES | NO | NO | — | FIXED_ALREADY |
| D029 | AI privacy | RoomAttendanceQueryTools | Assistant local facts are permission-scoped through AuthorizationRepository. | INFO | YES | NO | NO | — | FIXED_ALREADY |
| D030 | AI secrets | SecureSecretStore / ConfigurationBackupManager | Provider secrets are Keystore-encrypted and excluded from configuration export. | INFO | YES | NO | NO | — | FIXED_ALREADY |
| D031 | Reports UX | Screens.kt | CUSTOM report frequency control is disabled with an empty callback and no explanatory affordance. | LOW | YES | NO | NO | REPAIR-5 | DEFERRED_TO_NAMED_ROUND |
| D032 | Reports null-safety | PdfReportGenerator.kt | Internal page/canvas flow uses non-null assertions. | MEDIUM | YES | NO | NO | REPAIR-5 | DEFERRED_TO_NAMED_ROUND |
| D033 | Attendance null-safety | MainViewModel.kt | Several actor IDs are force-unwrapped after authorization checks. | MEDIUM | YES | NO | NO | REPAIR-3 / REPAIR-5 | DEFERRED_TO_NAMED_ROUND |
| D034 | Student device null-safety | StudentModeViewModel.kt | Active lecture is force-unwrapped in a runtime path. | MEDIUM | YES | NO | NO | REPAIR-4 | DEFERRED_TO_NAMED_ROUND |
| D035 | Appeal null-safety | AttendanceAppealRepository.kt | Accepted appeal update contains a force-unwrapped new status. | MEDIUM | YES | NO | NO | REPAIR-5 | DEFERRED_TO_NAMED_ROUND |
| D036 | Sync null-safety | Processors.kt | Workspace is force-unwrapped inside sync processing. | MEDIUM | YES | NO | NO | REPAIR-7 | DEFERRED_TO_NAMED_ROUND |
| D037 | Diagnostics export | SettingsScreen | Diagnostics CreateDocument output failure is silent and has no shared FileIoSafety UX. | MEDIUM | YES | NO | NO | REPAIR-3 | DEFERRED_TO_NAMED_ROUND |
| D038 | First run/session | MainActivity / MainViewModel | First-run/login/session/app-lock require dedicated end-to-end repair sweep. | HIGH | YES | YES | NO | REPAIR-3 | DEFERRED_TO_NAMED_ROUND |
| D039 | BLE/runtime permissions | BLE files / device flows | Device enrollment/BLE permissions/foreground service require physical runtime acceptance. | HIGH | YES | NO | NO | REPAIR-4 | DEFERRED_TO_NAMED_ROUND |
| D040 | Attendance/report privacy | Attendance / reports / appeals | Full runtime attendance, appeal, report, notification and scoped privacy acceptance remains pending. | HIGH | YES | NO | NO | REPAIR-5 | DEFERRED_TO_NAMED_ROUND |
| D041 | Local data breadth | Local data / backup / import/export / CRUD | Remaining local-data flows need complete CRUD/validation/error runtime sweep beyond Repair-1 paths. | HIGH | YES | NO | NO | REPAIR-6 | DEFERRED_TO_NAMED_ROUND |
| D042 | Runtime sync | sync/* | Push/pull/cursor/conflict/auth-expiry/workspace isolation/multi-device acceptance remains untested. | CRITICAL | YES | NO | NO | REPAIR-7 | DEFERRED_TO_NAMED_ROUND |
| D043 | Room upgrade | HammamDatabase / migrations | Runtime 1→5,2→5,3→5,4→5 upgrade acceptance remains untested on real old databases. | HIGH | YES | NO | NO | REPAIR-8 | DEFERRED_TO_NAMED_ROUND |
| D044 | Final sweep | Entire app | Accessibility/performance/permissions/logging/security/full-screen final defect sweep remains required. | HIGH | YES | NO | NO | FINAL DEFECT SWEEP | DEFERRED_TO_NAMED_ROUND |
| D045 | Localization / permissions | UserAccessPane / UiText | Permission machine codes were presented directly in the temporary-permission selector. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D046 | Localization / administration | UserAccessPane / TeachersManagementPane / SubjectsManagementPane | Default audit/action reasons were hard-coded English and leaked into Arabic forms. | LOW | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D047 | AI-related report settings UX | ReportSettingsPane | AI summary toggle was disabled with no explanation even though report generation explicitly rejects AI summary. | LOW | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D048 | Arabic terminology | values/strings.xml | Several ordinary Arabic labels still used English product/framework words such as Backend, AI and SYSTEM_OWNER where a natural Arabic presentation was available. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
| D049 | AI security/logging | AdminOperationsViewModel | AI provider operation failures logged exception objects even though provider errors should remain controlled and secret-safe. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D050 | Localization / appeals | AttendanceAppealFormScreen | Stored reason value OTHER was shown directly in an editable Arabic/English UI field instead of a localized presentation label. | LOW | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D051 | AI request cancellation | `AiProviderManager.ProviderHttpClient` | Provider HTTP layer caught cancellation as a generic exception, which could misclassify a cancelled request as network failure. Cancellation is now rethrown and remains a first-class request state. | MEDIUM | YES | NO | YES | — | REPAIRED_THIS_ROUND |
| D052 | Navigation / authorization UX | `AdminOperationsScreen` | If the computed authorized management sections are empty, the UI falls back to the Local Data tab even when the user has no matching management permission. Repository actions remain permission-guarded, but the navigation affordance is confusing and belongs in the dedicated roles/navigation sweep. | MEDIUM | YES | NO | NO | REPAIR-3 | DEFERRED_TO_NAMED_ROUND |
| D053 | RTL / technical text | Student identity, teacher contacts, timetable/settings, Local Data | Several technical identifiers and inputs (university number, QR payload, scope ID, phone/email/WhatsApp, subject code, dates/timezone and device identifiers) relied on ambient RTL. They are now explicitly rendered/input LTR while labels remain localized. | MEDIUM | YES | NO | YES | — | RUNTIME_VERIFICATION_REQUIRED |
