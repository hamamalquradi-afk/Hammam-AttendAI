#!/usr/bin/env python3
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'android/app/src/main/java'
RES=ROOT/'android/app/src/main/res'
errors=[]; notes=[]
def ok(cond,msg):
    if not cond: errors.append(msg)

def read(p): return p.read_text(errors='ignore')
# Build/package/version
build=read(ROOT/'android/app/build.gradle.kts')
ok('applicationId = "com.hammam.attendai"' in build,'applicationId mismatch')
ok('minSdk = 24' in build,'minSdk != 24')
ok('compileSdk = 35' in build,'compileSdk != 35')
ok('targetSdk = 35' in build,'targetSdk != 35')
ok('buildConfigField("int", "DATABASE_VERSION", "5")' in build,'BuildConfig DB version != 5')
# Resources XML/parity/references
str_files=[RES/'values/strings.xml',RES/'values-en/strings.xml']
sets=[]
for p in str_files:
    try: tree=ET.parse(p)
    except Exception as e: errors.append(f'XML invalid {p}: {e}'); continue
    sets.append({x.attrib['name'] for x in tree.getroot() if x.tag=='string'})
if len(sets)==2: ok(sets[0]==sets[1],f'string parity mismatch default-only={sets[0]-sets[1]} en-only={sets[1]-sets[0]}')
refs=set()
kt_files=list(SRC.rglob('*.kt'))
for p in kt_files: refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)',read(p)))
if sets: ok(not(refs-sets[0]),f'missing strings {sorted(refs-sets[0])}')
# PHASE 5H-RUNTIME-REPAIR-1 localization/file-I/O regressions
ui_files=list((SRC/'com/hammam/attendai/ui').rglob('*.kt'))
ui_text='\n'.join(read(p) for p in ui_files)
known_hardcoded=[
    'Text("Attendance policies")','Text("Policy name")','Text("Full")','Text("Partial")','Text("Absence")',
    'Text("Late min")','Text("Early min")','Text("Confidence")','Text("Grace sec")','Text("Verify sec")',
    'Text("Add attendance policy")','Text("Subjects requiring attendance policy")','Text("Assign attendance policy")',
    'Text("WhatsApp")','Text("Email")'
]
for literal in known_hardcoded: ok(literal not in ui_text,f'hard-coded UI literal remains: {literal}')
# Block ordinary direct English Text("...") literals. Dynamic/interpolated technical values are handled separately.
for pth in ui_files:
    txt=read(pth)
    for m in re.finditer(r'\bText\(\s*"([A-Za-z][^"\n]*)"',txt):
        errors.append(f'direct English Text literal in {pth.relative_to(ROOT)}: {m.group(1)}')
local_tools=read(SRC/'com/hammam/attendai/ui/LocalDataToolsScreen.kt')
ok('launchFileActionSafely' in local_tools,'safe file launcher wrapper missing')
ok('ActivityResultContracts.GetContent()' in local_tools,'ACTION_GET_CONTENT fallback missing')
ok('createShareableConfiguration' in local_tools,'configuration share/save fallback missing')
admin_vm=read(SRC/'com/hammam/attendai/ui/AdminOperationsViewModel.kt')
ok(not re.search(r'open(?:Input|Output)Stream\([^\n]*!!',admin_vm),'unsafe nullable ContentResolver stream assertion remains')
preview_body=admin_vm.split('fun previewStudentCsv',1)[1].split('fun confirmStudentCsvImport',1)[0] if 'fun previewStudentCsv' in admin_vm else ''
confirm_body=admin_vm.split('fun confirmStudentCsvImport',1)[1].split('fun exportStudentsCsv',1)[0] if 'fun confirmStudentCsvImport' in admin_vm else ''
ok('confirmImport' not in preview_body,'student CSV preview unexpectedly commits data')
ok('confirmImport' in confirm_body,'student CSV confirm path is not separate/present')
config_format=read(SRC/'com/hammam/attendai/backup/ConfigurationBackupFormat.kt')
ok('const val HEADER="HAMMAM_CONFIG_V1"' in config_format,'configuration format header changed')
# PHASE 5H-RUNTIME-REPAIR-2 Arabic/RTL + AI provider/model + chat regressions
screens_txt=read(SRC/'com/hammam/attendai/ui/Screens.kt')
ui_text_map=read(SRC/'com/hammam/attendai/ui/UiText.kt')
ai_manager=read(SRC/'com/hammam/attendai/ai/AiProviderManager.kt')
ai_state=read(SRC/'com/hammam/attendai/ai/AiProviderState.kt')
ai_assistant=read(SRC/'com/hammam/attendai/ai/AiAssistant.kt')
ai_vm=read(SRC/'com/hammam/attendai/ui/AiAssistantViewModel.kt')
chat_state=read(SRC/'com/hammam/attendai/ui/AssistantChatState.kt')
admin_ops_vm=read(SRC/'com/hammam/attendai/ui/AdminOperationsViewModel.kt')
app_container=read(SRC/'com/hammam/attendai/AppContainer.kt')
manifest_text=read(RES.parent/'AndroidManifest.xml')
for raw_render in ['Text(backendAuthState)','Text(flag.code)','Text(j.reportType)','Icons.Default.ChevronLeft']:
    ok(raw_render not in screens_txt,f'Repair-2 raw presentation remains: {raw_render}')
ok('android:supportsRtl="true"' in manifest_text,'RTL manifest support lost')
for state in ['UNAUTHENTICATED','PROVISIONING_REQUIRED','AUTHENTICATED','EXPIRED_OR_REVOKED','CONFIGURED_NOT_TESTED','MODEL_REQUIRED','AUTH_FAILED','UNSUPPORTED_MODEL','NETWORK_UNAVAILABLE','TIMEOUT','RATE_LIMITED','PROVIDER_SERVER_FAILURE','MALFORMED_RESPONSE','CONNECTION_FAILED']:
    ok(f'"{state}"' in ui_text_map,f'localized presentation mapping missing: {state}')
for flag in ['BLE_ATTENDANCE','QR_ATTENDANCE','NFC_ATTENDANCE','AI_ASSISTANT','WHATSAPP','EMAIL','AUTO_REPORTS','CLOUD_SYNC','REMOTE_BACKUP']:
    ok(f'"{flag}"' in ui_text_map,f'feature flag presentation mapping missing: {flag}')
for permission in ['VIEW_STUDENTS','EDIT_STUDENTS','START_LECTURE','END_LECTURE','EDIT_ATTENDANCE','APPROVE_ATTENDANCE','VIEW_REPORTS','SEND_REPORTS','APPROVE_REPORT','MANAGE_REPORT_SETTINGS','MANAGE_TEACHERS','MANAGE_SUBJECTS','MANAGE_SETTINGS','VIEW_AUDIT_LOG','EXPORT_AUDIT_LOG','MANAGE_USERS','MANAGE_ROLES','MANAGE_PERMISSIONS','MANAGE_ACADEMIC_STRUCTURE','MANAGE_TIMETABLE','TAKE_OVER_ATTENDANCE','MANAGE_AI_PROVIDER','MANAGE_BACKUP','MANAGE_CONFIGURATION','RUN_DATA_INTEGRITY','RESTORE_BACKUP','SUBMIT_APPEAL','REVIEW_APPEALS','EDIT_FROZEN_ATTENDANCE','MANAGE_DEVICE_ENROLLMENT','VIEW_SYSTEM_HEALTH']:
    ok(f'"{permission}"' in ui_text_map,f'permission presentation mapping missing: {permission}')
ok('val all=listOf(OPENAI,GEMINI,CLAUDE)' in ai_manager,'AI provider set changed unexpectedly')
ok(not re.search(r'\b(?:gpt|gemini|claude)-[A-Za-z0-9_.-]+',ai_manager,re.I),'hard-coded provider model catalog entry introduced')
ok('backendConfigured:suspend ()->Boolean' in ai_manager and 'preferences.backendBaseUrl.first()' in app_container,'backend-managed configured state is not tied to existing backend configuration')
test_body=ai_manager.split('suspend fun test(provider:String):Boolean',1)[1].split('suspend fun summarizeGroundedResult',1)[0] if 'suspend fun test(provider:String):Boolean' in ai_manager else ''
ok('selectedModel' in test_body and 'summarizeViaBackendChecked' in test_body and 'summarizeDirectChecked' in test_body,'Test Connection does not exercise selected provider/model request path')
ok('invalidateVerification(provider)' in ai_manager.split('suspend fun setSelectedModel',1)[1].split('suspend fun setDefaultProvider',1)[0],'model mutation does not invalidate prior provider verification')
ok('catch(e:CancellationException){\n            throw e\n        }catch(e:Exception)' in ai_manager,'AI HTTP cancellation is swallowed as a generic provider failure')
ok('AssistantMessageRole' in chat_state and 'beginRequest' in chat_state and 'beginRetry' in chat_state,'chat state / retry contract missing')
ok('state.messages' in screens_txt and 'FilledIconButton' in screens_txt and 'imePadding()' in screens_txt,'real chat message/composer presentation missing')
ok('state.query' not in screens_txt and 'state.answer' not in screens_txt,'legacy single-form assistant presentation remains')
ok('AssistantReplyCode' in ai_assistant and 'data class AssistantAnswer' in ai_assistant and 'val text:String' not in ai_assistant.split('data class AssistantAnswer',1)[1].split(')',1)[0],'offline assistant still owns localized literal response text')
ok('MANAGE_AI_PROVIDER' in admin_ops_vm and 'hasPermission' in admin_ops_vm.split('private fun aiAction',1)[1].split('fun setAiMode',1)[0],'AI provider configuration authorization guard missing')
ok('Log.e("AdminOperationsViewModel","AI_PROVIDER_OPERATION_FAILED",e)' not in admin_ops_vm,'AI provider failure still logs exception object')
ok('groundedFacts' in ai_vm and 'summarizeGroundedResult' in ai_vm,'assistant cloud path is not grounded on explicit authorized local facts')
ok('AssistantChatMessage::class' not in read(SRC/'com/hammam/attendai/data/local/HammamDatabase.kt'),'chat persistence unexpectedly added as a Room entity')
# Exact sync allowlist is frozen in Repair-2.
allowline=next((line for line in read(SRC/'com/hammam/attendai/sync/SyncIntegrityRules.kt').splitlines() if 'supportedEntities=setOf' in line),'')
expected_sync={'Teacher','AttendancePolicy','University','AcademicYear','Faculty','Department','Level','Semester','Batch','Section','Group','Student','Subject','Lecture','AttendanceRecord','AttendanceAppeal'}
actual_sync=set(re.findall(r'"([A-Za-z]+)"',allowline))
ok(actual_sync==expected_sync,f'sync allowlist changed: actual={sorted(actual_sync)}')

# PHASE 5H-RUNTIME-REPAIR-3 core access/session/navigation regressions
main_vm_r3=read(SRC/'com/hammam/attendai/ui/MainViewModel.kt')
main_activity_r3=read(SRC/'com/hammam/attendai/MainActivity.kt')
auth_repo_r3=read(SRC/'com/hammam/attendai/security/AuthorizationRepository.kt')
access_rules_r3=read(SRC/'com/hammam/attendai/security/LocalAccessRules.kt')
core_dao_r3=read(SRC/'com/hammam/attendai/data/local/dao/CoreDao.kt')
admin_vm_r3=read(SRC/'com/hammam/attendai/ui/AdminOperationsViewModel.kt')
preferences_r3=read(SRC/'com/hammam/attendai/data/settings/AppPreferences.kt')
application_r3=read(SRC/'com/hammam/attendai/HammamAttendAiApplication.kt')
ok('AtomicBoolean(false)' in main_vm_r3 and 'firstRunGate.compareAndSet(false,true)' in main_vm_r3,'Repair-3 First Run duplicate-submit gate missing')
ok('getAnySystemOwnerUser()!=null' in main_vm_r3 and 'getAnySystemOwnerUser()!=null' in application_r3,'First Run still trusts active-owner/preference state only')
ok('observeUserById' in main_vm_r3 and 'LocalAccessRules.resolve' in main_vm_r3,'current local session is not reconciled against live user/role state')
ok('markInitializedWithoutSession()' in main_vm_r3 and 'completeFirstRun(activeOwner.id' not in main_vm_r3,'lost First Run preference silently restores SYSTEM_OWNER session')
ok('confirmRecovery' in screens_txt and 'owner_upgrade_confirmation' in screens_txt and 'onRecoverExistingOwner()' in screens_txt,'First Run Administrator-to-SYSTEM_OWNER recovery lacks explicit confirmation')
ok('getAnySystemOwnerUser()==null' in auth_repo_r3,'SYSTEM_OWNER recovery can create a second owner when an inactive owner exists')
ok('SYSTEM_OWNER_CREATION_PROTECTED' in auth_repo_r3 and 'SYSTEM_OWNER_ROLE_PROTECTED' in auth_repo_r3 and 'SYSTEM_OWNER_PROTECTED' in auth_repo_r3,'SYSTEM_OWNER repository protections regressed')
ok('recordLocalSessionRestore' in auth_repo_r3 and 'LOCAL_SESSION_RESTORED' in auth_repo_r3,'local session recovery is not audited')
ok("CASE WHEN MAX(CASE WHEN r.name='SYSTEM_OWNER'" in core_dao_r3,'multi-role SYSTEM_OWNER presentation priority missing')
ok('l.scheduledEnd>:now' in core_dao_r3 and 'observeNextLectureForUser(userId:String,now:Long)' in core_dao_r3,'dashboard next-lecture time guard missing')
ok('.ifEmpty{listOf(AdminOpsSection.DATA)}' not in screens_txt,'Admin Operations still falls back to unauthorized Local Data')
ok('allowedManagementSections(permissions)' in screens_txt and 'no_authorized_management_actions' in screens_txt,'Admin Operations controlled empty state missing')
ok('val effectiveSection=section.takeIf{it in available} ?: available.firstOrNull()' in screens_txt and 'when(safeSection)' in screens_txt,'Admin Operations can render stale unauthorized selected section')
ok('"Academic Supervisor"->{' in screens_txt and 'daily.pendingApprovalReports.toString()' in screens_txt.split('"Academic Supervisor"->{',1)[1].split('else->{',1)[0],'Academic Supervisor dashboard pending report metric is not scoped')
ok('LocalAccessRules.hasManagementAccess(permissions)' in main_activity_r3,'management navigation is not permission-derived')
ok('var presentedUserId by rememberSaveable{mutableStateOf(currentUserId)}' in main_activity_r3 and 'if(presentedUserId!=currentUserId)' in main_activity_r3 and 'clearSessionUiState()' in main_activity_r3,'account transition clearing is missing or replays on ordinary Activity recreation')
ok('LaunchedEffect(permissions,currentRole)' in main_activity_r3,'route authorization is not revalidated after role/permission change')
ok('LocalSessionRecoveryScreen' in main_activity_r3 and 'LocalSessionStatus.READY' in main_activity_r3,'invalid local session does not fail closed before main navigation')
ok('FileIoSafety.requireOpened(context.contentResolver.openOutputStream' in screens_txt,'diagnostics export null output stream is not guarded')
ok('shareDiagnosticsFallback' in screens_txt and 'FileProvider.getUriForFile' in screens_txt,'diagnostics export safe fallback missing')
ok('PasswordVisualTransformation()' in screens_txt and 'KeyboardType.NumberPassword' in screens_txt,'Repair-3 PIN fields are not masked/numeric')
ok('onBiometricUnavailable' in main_activity_r3 and 'onAuthenticationError' in main_activity_r3 and 'onAuthenticationFailed' in main_activity_r3,'biometric unavailable/cancel/failure handling missing')
ok('container.appLock.clear()' in main_vm_r3 and '_biometricEnabled.value=false' in main_vm_r3,'disabling App Lock does not clear biometric state')
ok('private suspend fun actor():String' in admin_vm_r3 and 'isUserActive(id)' in admin_vm_r3,'AdminOperations actor does not validate active local session')
ok('e.message?:"OPERATION_FAILED"' not in admin_vm_r3,'AdminOperations still exposes raw exception message')
ok('currentUserId.value!!' not in main_vm_r3,'force-unwrapped current actor remains in MainViewModel')
ok('setLocalSession' in preferences_r3 and 'clearLocalSession' in preferences_r3,'local-session preference operations missing')
ok('diagnosticsText' in screens_txt and 'backendAuthToken' not in screens_txt.split('internal fun diagnosticsText',1)[1].split('@Composable fun StudentsScreen',1)[0],'diagnostics formatter exposes backend auth token field')

# PHASE 5H-RUNTIME-REPAIR-4 device/BLE regressions
student_mode_r4=read(SRC/'com/hammam/attendai/ui/StudentModeViewModel.kt')
ble_policy_r4=read(SRC/'com/hammam/attendai/ble/BleRuntimePolicy.kt')
ble_ready_r4=read(SRC/'com/hammam/attendai/ble/BlePlatformReadiness.kt')
ble_adv_r4=read(SRC/'com/hammam/attendai/ble/StudentBleAdvertiser.kt')
ble_scan_r4=read(SRC/'com/hammam/attendai/ble/BlePresenceDetector.kt')
presence_service_r4=read(SRC/'com/hammam/attendai/ble/StudentPresenceService.kt')
attendance_service_r4=read(SRC/'com/hammam/attendai/ble/AttendanceForegroundService.kt')
device_repo_r4=read(SRC/'com/hammam/attendai/data/repository/DeviceEnrollmentRepository.kt')
local_data_r4=read(SRC/'com/hammam/attendai/ui/LocalDataToolsScreen.kt')
rotating_r4=read(SRC/'com/hammam/attendai/ble/RotatingPresenceToken.kt')
ok('activeLecture.value!!' not in student_mode_r4,'D034 activeLecture force unwrap returned')
ok('currentStudentContext()' in student_mode_r4 and 'StudentDeviceSessionRules.usable' in student_mode_r4,'D062 device session is not reconciled through live active user/student state')
ok('preferences.userId.flatMapLatest{id->flow{emit(id?.let{dao.getStudentForUser' not in student_mode_r4.replace(' ',''),'legacy blind persisted student lookup returned')
ok('object BleProtocol' in ble_policy_r4 and 'SERVICE_UUID="86c90000-7b29-4b4a-9f19-bd856f5c12b1"' in ble_policy_r4,'BLE protocol UUID constant missing/changed')
all_ble='\n'.join(read(p) for p in (SRC/'com/hammam/attendai/ble').glob('*.kt'))
ok(all_ble.count('86c90000-7b29-4b4a-9f19-bd856f5c12b1')==1,'BLE service UUID duplicated outside shared protocol constant')
ok('POST_NOTIFICATIONS' not in ble_policy_r4,'notification permission incorrectly coupled into BLE permission policy')
ok('notificationRuntimePermissionRequired' in ble_policy_r4 and 'BlePlatformReadiness.missingPermissions' in screens_txt,'central BLE permission matrix is not wired into UI')
ok('BlePlatformReadiness.advertiseError' in ble_adv_r4 and 'BlePlatformReadiness.scanError' in ble_scan_r4,'advertiser/scanner readiness checks are not centralized')
ok('e.message' not in ble_scan_r4 and 'e.message' not in presence_service_r4 and 'e.message' not in attendance_service_r4,'BLE/service runtime exposes raw exception messages')
ok('override suspend fun fail(code:String)' in ble_scan_r4,'detector controlled runtime failure transition missing')
ok('callbackLock' in ble_scan_r4 and 'callback===this' in ble_scan_r4,'scanner callback ownership race guard missing')
ok('DeviceActiveInvariantRules.replacementMatches' in device_repo_r4,'replacement approval does not fail closed on active-device mismatch')
ok('state.activeDeviceIds.isEmpty() && state.registeredDeviceId==null' in ble_policy_r4,'initial enrollment does not fail closed on stale registered-device state')
ok('deviceReviewInFlight' in admin_ops_vm and 'deviceReviewBusy' in admin_ops_vm,'device review duplicate-submit guard missing')
ok('pendingDeviceReplacements' in admin_ops_vm and 'observeActiveUserScopes' in admin_ops_vm,'device review list is not live scope-aware')
ok('deviceReviewBusy' in local_data_r4,'device review UI does not honor in-flight state')
ok('BleProtocol.ATTENDANCE_QR_PREFIX' in rotating_r4,'HAD1 dynamic QR prefix is not centralized/preserved')
ok('BleProtocol.PAIRING_PREFIX' in device_repo_r4,'HAP1 pairing prefix is not centralized/preserved')
ok('ServiceCompat.stopForeground' in presence_service_r4 and 'ServiceCompat.stopForeground' in attendance_service_r4,'BLE foreground service cleanup missing')
ok('container.preferences.userId.first()' in presence_service_r4 and 'getStudentForUser(currentUser.id)' in presence_service_r4,'student presence service does not independently revalidate live local session')
ok('PairingOfferRules.stateMatches' in device_repo_r4 and 'PAIRING_STATE_MISMATCH' in device_repo_r4,'pairing stored-state binding check missing')
ok('PAIRING_SIGNATURE_INVALID' in device_repo_r4 and 'check(verify(publicKey' in device_repo_r4,'pairing signature validation missing')
ok('state.status=="PENDING"' in device_repo_r4 and 'PAIRING_OFFER_NOT_PENDING' in device_repo_r4,'pairing single-use pending-state gate missing')
ok('getPendingDeviceReplacementRequestForStudent(studentId)?.let{return@withTransaction it}' in device_repo_r4,'duplicate pending replacement request guard missing')
ok('status="APPROVED"' in device_repo_r4 and 'status=DeviceStatus.REPLACED' in device_repo_r4 and 'request.newPresenceSecretCiphertext,now,null,DeviceStatus.ACTIVE,1' in device_repo_r4,'replacement approval atomic state transition missing')
ok('status="REJECTED"' in device_repo_r4,'replacement rejection transition missing')
ok('it.status==DeviceStatus.ACTIVE' in device_repo_r4 and 'it.status==StudentStatus.ACTIVE && it.registeredDeviceId==device.id' in device_repo_r4,'rotating token is not bound to active student/registered active device')
ok('PresenceStartContextRules.resolve' in presence_service_r4,'student presence service start extras are not validated through pure start-context rule')
ok('android:foregroundServiceType="connectedDevice"' in manifest_text and 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE' in manifest_text,'connected-device foreground service manifest contract missing')
ok('android.permission.BLUETOOTH_SCAN' in manifest_text and 'neverForLocation' in manifest_text,'Android 12+ BLE scan neverForLocation declaration missing')
ok('android.permission.ACCESS_FINE_LOCATION' in manifest_text and 'maxSdkVersion="30"' in manifest_text,'legacy BLE location permission is not capped at API 30')


# PHASE 5H-RUNTIME-REPAIR-5 attendance/appeals/reports/notifications/privacy regressions
attendance_r5=read(SRC/'com/hammam/attendai/data/repository/AttendanceRepository.kt')
appeal_repo_r5=read(SRC/'com/hammam/attendai/data/repository/AttendanceAppealRepository.kt')
appeal_vm_r5=read(SRC/'com/hammam/attendai/ui/AttendanceAppealViewModel.kt')
report_repo_r5=read(SRC/'com/hammam/attendai/data/repository/ReportRepository.kt')
report_models_r5=read(SRC/'com/hammam/attendai/reports/ReportModels.kt')
report_processor_r5=read(SRC/'com/hammam/attendai/reports/ReportProcessor.kt')
pdf_r5=read(SRC/'com/hammam/attendai/reports/PdfReportGenerator.kt')
csv_r5=read(SRC/'com/hammam/attendai/reports/CsvReportGenerator.kt')
notification_rules_r5=read(SRC/'com/hammam/attendai/sync/NotificationDeliveryRules.kt')
processors_r5=read(SRC/'com/hammam/attendai/sync/Processors.kt')
work_r5=read(SRC/'com/hammam/attendai/sync/WorkOrchestrator.kt')
attachment_r5=read(SRC/'com/hammam/attendai/appeals/AttachmentValidator.kt')
core_r5=read(SRC/'com/hammam/attendai/data/local/dao/CoreDao.kt')
ok('newStatus!!' not in appeal_repo_r5,'D035 accepted appeal force unwrap returned')
ok('AppealConsistencyRules.acceptedStatus' in appeal_repo_r5,'appeal accepted status is not captured through consistency rule')
ok('preferences.userId.first()' not in appeal_vm_r5 and 'LocalAccessRules.resolve' in appeal_vm_r5,'D063 appeal session uses stale persisted user identity')
ok('getStudentForUser(actorId)' in appeal_repo_r5 and 'StudentStatus.ACTIVE' in appeal_repo_r5,'appeal submitter is not bound to active linked Student')
ok('hasScopedPermission(reviewerId,"EDIT_FROZEN_ATTENDANCE","GROUP",lecture.groupId)' in appeal_repo_r5,'frozen appeal edit is not group-scoped in repository')
ok('markReportJobsStaleForLecture' in appeal_repo_r5 and 'markReportJobsStaleForLecture' in attendance_r5,'official reports are not invalidated after accepted/manual attendance changes')
ok('page!!' not in pdf_r5 and 'canvas!!' not in pdf_r5,'D032 unsafe PDF page/canvas assertion remains')
attendance_pdf_body=pdf_r5.split('fun generate(',1)[1].split('fun generateAudit',1)[0] if 'fun generate(' in pdf_r5 and 'fun generateAudit' in pdf_r5 else ''
ok('.take(' not in attendance_pdf_body,'official attendance PDF silently truncates rows')
ok('rows.take(1000)' in pdf_r5 and 'Audit export intentionally caps at 1000' in pdf_r5,'audit-only export cap is no longer explicit')
ok('ReportCsvSafety.spreadsheetSafe' in csv_r5 and "first in setOf('=','+','-','@')" in report_models_r5,'CSV formula-injection guard missing')
ok('Charsets.UTF_8' in csv_r5,'CSV report is not explicitly UTF-8')
ok('validateSchedule' in report_models_r5 and 'REPORT_CUSTOM_UNSUPPORTED' in report_models_r5,'truthful CUSTOM unsupported contract missing')
ok('FilterChip(selected=false,onClick={},enabled=false' not in screens_txt and 'custom_report_unavailable_note' in screens_txt,'D031 misleading dead CUSTOM report control remains')
ok('d.plusDays(1).atStartOfDay(z)' in report_repo_r5,'monthly report period is not deterministic across repeated schedule runs')
ok('ReportRepository(database,authorization)' in app_container,'report repository is not wired to live authorization source')
ok('canAccessReportTarget' in report_repo_r5 and 'canAccessReportJob' in report_repo_r5,'report repository mutation scope enforcement missing')
ok('observeReportSettingsForUser' in report_repo_r5 and 'observeReportTeachersForUser' in report_repo_r5 and 'observeReportSubjectsForUser' in report_repo_r5 and 'reportActor' in admin_ops_vm,'report settings presentation is not live scope-aware')
ok("approvalStatus IN ('APPROVED','FROZEN')" in core_r5 and "l.status IN ('COMPLETED','FROZEN')" in core_r5,'official report DAO query includes non-official attendance')
ok('GENERATED_REPORT_HASH_MISMATCH' in report_processor_r5 and 'sha256(file)!=generated.hash' in report_processor_r5,'generated report hash is not verified before delivery')
ok('REPORT_STALE_AFTER_ATTENDANCE_CHANGE' in report_repo_r5 and 'REPORT_STALE_AFTER_ATTENDANCE_CHANGE' in screens_txt,'stale official report state lacks controlled regeneration UX')
ok('val reports=PeriodicWorkRequestBuilder<ReportWorker>(1,TimeUnit.HOURS).setBackoffCriteria' in work_r5 and 'OneTimeWorkRequestBuilder<ReportWorker>().build()' in work_r5,'ReportWorker still requires network before local generation')
ok('ReportWorker>(1,TimeUnit.HOURS).setConstraints(network)' not in work_r5,'periodic ReportWorker is still network-gated')
ok('safeError=if(r.ok)null else NotificationDeliveryRules.safeErrorCode' in processors_r5,'notification worker persists raw provider failure')
ok('error=r.error' not in processors_r5.split('class NotificationProcessor',1)[1],'notification worker raw provider error storage returned')
ok('fun safeErrorCode' in notification_rules_r5,'notification provider error sanitizer missing')
ok('ReportLifecycleRules.safeFailureCode' in report_processor_r5 and 'e.javaClass.simpleName.take(40)' in report_processor_r5,'report worker exception sanitization missing')
ok('uri.scheme!=ContentResolver.SCHEME_CONTENT' in attachment_r5.replace(' ',''),'appeal attachment accepts unrestricted filesystem URI')
ok('countActiveSessions()>1' in attendance_r5,'attendance active-session conflict detection missing')
ok('source=PresenceSource.MANUAL' in appeal_repo_r5 and 'ApprovalStatus.DRAFT' in appeal_repo_r5,'accepted appeal does not return changed attendance to manual review state')
ok('LectureStatus.NEEDS_REVIEW' in appeal_repo_r5,'accepted appeal does not reopen finalized lecture')
ok('finalStatus==FinalAttendanceStatus.ABSENT' in attendance_r5 and 'enqueueAbsenceNotifications' in attendance_r5.split('suspend fun approveLecture',1)[1].split('suspend fun editAttendanceRecord',1)[0],'absence notification is not post-approval')
ok('deduplicationKey' in read(SRC/'com/hammam/attendai/data/local/entity/Entities.kt') and 'unique=true' in read(SRC/'com/hammam/attendai/data/local/entity/Entities.kt'),'notification/report dedup uniqueness missing')

# Package path consistency
for p in kt_files:
    txt=read(p); m=re.search(r'^package\s+([\w.]+)',txt,re.M)
    if not m: errors.append(f'missing package: {p}'); continue
    pkg=m.group(1); expected=Path(*pkg.split('.'))
    rel=p.relative_to(SRC)
    ok(rel.parent.as_posix().endswith(expected.as_posix()),f'package/path mismatch {p}: {pkg}')
# duplicate top-level declarations
seen={}
for p in kt_files:
    for m in re.finditer(r'^(?:data\s+|sealed\s+|enum\s+|abstract\s+)?(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)',read(p),re.M):
        seen.setdefault(m.group(1),[]).append(str(p.relative_to(ROOT)))
for name,ps in seen.items(): ok(len(ps)==1,f'duplicate top-level {name}: {ps}')
# entities/table uniqueness and DB registration
entities=[]
for p in (SRC/'com/hammam/attendai/data/local/entity').glob('*.kt'):
    txt=read(p)
    entities += re.findall(r'@Entity\(tableName="([^"]+)"[^)]*\)\s+(?:data\s+)?class\s+([A-Za-z0-9_]+)',txt,re.S)
tables=[x[0] for x in entities]; ok(len(tables)==len(set(tables)),f'duplicate entity tables: {[x for x in set(tables) if tables.count(x)>1]}')
db=read(SRC/'com/hammam/attendai/data/local/HammamDatabase.kt')
ok('version = 5' in db,'Room database version != 5')
registered=set(re.findall(r'([A-Za-z0-9_]+)::class',db.split('version = 5')[0]))
for table,cls in entities: ok(cls in registered,f'entity not registered in @Database: {cls}/{table}')
ok(all(x in db for x in ['MIGRATION_1_2','MIGRATION_2_3','MIGRATION_3_4','MIGRATION_4_5']),'migration declaration missing')
ok('arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)' in db,'migration registration path incomplete')
ok('fallbackToDestructiveMigration' not in ''.join(read(p) for p in kt_files),'destructive migration found')
# Migration 3->4 duplicate ADD columns/tables against entity names
m34=db.split('MIGRATION_3_4',1)[1]
for col in ['weeklyScheduleId','actorRole']: ok(m34.count(f'ADD COLUMN {col}')==1,f'Migration3->4 duplicate/missing column {col}')
for table in ['user_scopes','user_permission_grants','device_replacement_requests','weekly_timetable_versions']: ok(m34.count(f'CREATE TABLE IF NOT EXISTS {table}')==1,f'Migration3->4 duplicate/missing table {table}')
m45=db.split('MIGRATION_4_5',1)[1]
ok(m45.count('CREATE TABLE IF NOT EXISTS sync_entity_metadata')==1,'Migration4->5 sync_entity_metadata missing/duplicate')
ok('SyncEntityMetadataEntity::class' in db,'sync metadata entity not registered')
entity_source=read(SRC/'com/hammam/attendai/data/local/entity/Entities.kt')
ok(entity_source.count('@Entity(')==43,f"Room entity annotation count != 43 after single metadata sidecar: {entity_source.count('@Entity(')}")
# PHASE 5F local backfill/ordering must not depend on the network-constrained worker alone.
processor=read(SRC/'com/hammam/attendai/sync/Processors.kt')
vm=read(SRC/'com/hammam/attendai/ui/MainViewModel.kt')
ok('suspend fun prepareLocalBackfill()' in processor,'5F local backfill entrypoint missing')
ok('container.syncProcessor.prepareLocalBackfill()' in vm,'5F offline startup backfill wiring missing')
# PHASE 5G academic graph sync: backfill, mutation wiring, exclusions and dependency ordering.
graph=read(SRC/'com/hammam/attendai/sync/AcademicGraphSyncOutbox.kt')
integrity=read(SRC/'com/hammam/attendai/sync/SyncIntegrityRules.kt')
student_repo=read(SRC/'com/hammam/attendai/data/repository/StudentRepository.kt')
academic_repo=read(SRC/'com/hammam/attendai/data/repository/AcademicManagementRepository.kt')
lecture_repo=read(SRC/'com/hammam/attendai/data/repository/LectureSchedulerRepository.kt')
attendance_repo=read(SRC/'com/hammam/attendai/data/repository/AttendanceRepository.kt')
ok('AcademicGraphSyncOutbox(db,encrypt).backfillExistingIfEnabled()' in processor,'5G local graph backfill missing from SyncProcessor')
ok(all(x in graph for x in ['recordStudent','recordSubject','recordLecture','recordAttendanceRecord']),'5G graph outbox coverage incomplete')
ok('registeredDeviceId' not in graph and 'presenceSecretCiphertext' not in graph,'5G sync payload leaks device/presence secret field')
ok('syncOutbox?.recordStudent(updated,now)' in student_repo,'Student academic-scope mutation does not enqueue sync atomically')
ok('graphSyncOutbox?.recordSubject' in academic_repo,'Subject mutation sync wiring missing')
ok('syncOutbox?.recordLecture' in lecture_repo,'Lecture materialization sync wiring missing')
ok('upsertSyncedRecord' in attendance_repo and 'updateSyncedLecture' in attendance_repo,'Attendance graph mutation sync helpers missing')
for t in ['Student','Subject','Lecture','AttendanceRecord']:
    ok(f'"{t}"' in integrity,f'5G supported entity missing from Android protocol: {t}')
for forbidden in ['StudentDevice','PresenceInterval','PresenceEvent','UserRole','RolePermission']:
    # They may appear elsewhere in project, but must not be in the protocol allowlist literal.
    allowline=next((line for line in integrity.splitlines() if 'supportedEntities=setOf' in line),'')
    ok(f'"{forbidden}"' not in allowline,f'forbidden runtime/security entity accidentally enabled for sync: {forbidden}')
# DAO method availability for dao.foo calls
core=read(SRC/'com/hammam/attendai/data/local/dao/CoreDao.kt')
declared=set(re.findall(r'\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(',core))
used=set()
for p in kt_files:
    if p.name=='CoreDao.kt': continue
    used.update(re.findall(r'\bdao\.([A-Za-z_][A-Za-z0-9_]*)\s*\(',read(p)))
# only flag names absent globally; false positives are unlikely because project uses CoreDao as dao
for name in sorted(used-declared): errors.append(f'dao call missing in CoreDao: {name}')
# Routes enum duplicate values
main=read(SRC/'com/hammam/attendai/MainActivity.kt')
for enum in ['Tab','DetailRoute']:
    m=re.search(rf'enum class {enum}\{{([^}}]+)\}}',main)
    ok(bool(m),f'{enum} enum missing')
    if m:
        vals=[x.strip() for x in m.group(1).split(',') if x.strip()]; ok(len(vals)==len(set(vals)),f'duplicate {enum} routes')
# Manifest + referenced components/resources
manifest=ET.parse(RES.parent/'AndroidManifest.xml').getroot(); ns='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application'); ok(app is not None,'application missing')
if app is not None:
    for tag in ['activity','service','provider']:
        for node in app.findall(tag):
            name=node.attrib.get(ns+'name',''); exported=node.attrib.get(ns+'exported')
            ok(exported is not None,f'{tag} missing android:exported: {name}')
            if name.startswith('.'):
                cls=name[1:]; candidates=list(SRC.rglob(cls.split('.')[-1]+'.kt')); ok(bool(candidates),f'manifest class missing: {name}')
    provider=app.find('provider')
    ok(provider is not None,'FileProvider missing')
ok((RES/'xml/file_paths.xml').exists(),'file_paths.xml missing')
ok((RES/'xml/data_extraction_rules.xml').exists(),'data_extraction_rules.xml missing')
# WorkManager worker refs
alltxt='\n'.join(read(p) for p in kt_files)
worker_defs=set(re.findall(r'^class\s+([A-Za-z0-9_]+Worker)\b',alltxt,re.M))
worker_refs=set(re.findall(r'(?:PeriodicWorkRequestBuilder|OneTimeWorkRequestBuilder)<([A-Za-z0-9_]+Worker)>',alltxt))
for w in worker_refs: ok(w in worker_defs,f'WorkManager class ref missing: {w}')
# Feature flags
for flag in ['BLE_ATTENDANCE','QR_ATTENDANCE','NFC_ATTENDANCE','AI_ASSISTANT','WHATSAPP','EMAIL','AUTO_REPORTS','CLOUD_SYNC']:
    ok(flag in alltxt,f'feature flag missing: {flag}')
# Critical wiring callers
critical={
'StudentBleAdvertiser':['StudentPresenceService.kt'],
'DeviceEnrollmentRepository':['AppContainer.kt'],
'StudentCsv':['AdminOperationsViewModel.kt'],
'PinHasher':['AppLockManager.kt'],
'TeacherReportSettingEntity':['AdminOperationsViewModel.kt','ReportRepository.kt'],
'DataIntegrityRepository':['AppContainer.kt','AdminOperationsViewModel.kt'],
'DatabaseMigrationGuard':['AppContainer.kt'],
}
for symbol,expected in critical.items():
    locations=[p.name for p in kt_files if symbol in read(p)]
    for e in expected: ok(e in locations,f'critical wiring missing {symbol} caller {e}; found={locations}')
# DI-exposed implementations are intentionally referenced by property, not class name, from callers.
property_wiring={
'backupManager':['AdminOperationsViewModel.kt'],
'aiProviderManager':['AdminOperationsViewModel.kt','MainViewModel.kt'],
'appLock':['MainViewModel.kt'],
'devices':['StudentModeViewModel.kt'],
}
for prop,expected in property_wiring.items():
    locations=[p.name for p in kt_files if f'container.{prop}' in read(p)]
    for e in expected: ok(e in locations,f'critical property wiring missing container.{prop} caller {e}; found={locations}')
# Common Compose API misuse caught in prior code
screens=read(SRC/'com/hammam/attendai/ui/Screens.kt')
ok(not re.search(r'FilterChip\([^,]+,\s*\{[^}]*=it\}',screens),'invalid FilterChip onClick(Boolean) pattern')
# Local DI architecture note
if 'com.google.dagger:hilt' not in build and '@HiltAndroidApp' not in alltxt: notes.append('DI=manual AppContainer; Hilt binding checks N/A for current architecture')
# PHASE 5G upgrade compatibility: 5E/5F Teacher/Policy may already be SENT without a server marker.
core_dao=read(SRC/'com/hammam/attendai/data/local/dao/CoreDao.kt')
processors=read(SRC/'com/hammam/attendai/sync/Processors.kt')
ok('countSentSyncForEntity' in core_dao,'legacy SENT parent confirmation query missing')
ok('marker||dao.countSentSyncForEntity(entityType,entityId)>0' in processors,'legacy Teacher/Policy SENT compatibility missing')
# PHASE 5H Repair-6 local-data/CRUD hardening regression guards.
student_csv=read(SRC/'com/hammam/attendai/importexport/StudentCsv.kt')
csv_codec=read(SRC/'com/hammam/attendai/importexport/CsvCodec.kt')
student_repo=read(SRC/'com/hammam/attendai/data/repository/StudentRepository.kt')
timetable_repo=read(SRC/'com/hammam/attendai/data/repository/TimetableRepository.kt')
timetable_csv=read(SRC/'com/hammam/attendai/importexport/TimetableCsvFormat.kt')
backup_manager=read(SRC/'com/hammam/attendai/backup/EncryptedBackupManager.kt')
restore_applier=read(SRC/'com/hammam/attendai/backup/PendingRestoreApplier.kt')
config_manager=read(SRC/'com/hammam/attendai/backup/ConfigurationBackupManager.kt')
audit_repo=read(SRC/'com/hammam/attendai/data/repository/AuditRepository.kt')
feature_repo=read(SRC/'com/hammam/attendai/data/repository/FeatureFlagRepository.kt')
integrity_repo=read(SRC/'com/hammam/attendai/data/repository/DataIntegrityRepository.kt')
local_tools=read(SRC/'com/hammam/attendai/ui/LocalDataToolsScreen.kt')
admin_vm=read(SRC/'com/hammam/attendai/ui/AdminOperationsViewModel.kt')
ok('acceptedHeaders' in student_csv and 'CSV_HEADER_INVALID' in student_csv,'Repair-6 Student CSV explicit header validation missing')
ok('CsvCodec.parse' in student_csv and 'rows.drop(1)' in student_csv,'Repair-6 Student CSV parser/header contract missing')
ok('StudentImportTarget' in student_csv and 'validateAcademicTarget' in student_repo,'Repair-6 Student CSV academic target validation missing')
ok(all(x in student_repo for x in ['target.levelId','target.batchId','target.sectionId','target.groupId']),'Repair-6 Student CSV hierarchy target incomplete')
ok('spreadsheetSafe' in csv_codec and "setOf('=','+','-','@')" in csv_codec,'Repair-6 CSV formula-injection guard missing')
ok('TimetableCsvFormat.parse' in timetable_repo,'Repair-6 timetable safe parser not wired')
ok("split(',')" not in timetable_repo and 'line.split(",")' not in timetable_repo,'Repair-6 naive timetable CSV split reintroduced')
ok('TIMETABLE_CSV_HEADER_INVALID' in timetable_csv and 'CsvCodec.parse' in timetable_csv,'Repair-6 timetable explicit header/quoted CSV validation missing')
ok('Uri.fromFile' not in alltxt,'file:// URI construction reintroduced')
ok('PRAGMA user_version' in backup_manager,'Repair-6 backup actual DB version validation missing')
ok('PRAGMA quick_check' in backup_manager,'Repair-6 backup SQLite integrity validation missing')
ok('BACKUP_DATABASE_VERSION_MISMATCH' in backup_manager or 'BackupValidationRules.validateVersion' in backup_manager,'Repair-6 backup declared/actual version comparison missing')
ok('LocalAtomicFile.replaceFrom' in backup_manager,'Repair-6 backup safe final-artifact replacement missing')
ok('PRAGMA quick_check' in restore_applier and 'rollback' in restore_applier,'Repair-6 restore integrity/rollback hardening missing')
ok('pending.delete()' in restore_applier and 'marker.delete()' in restore_applier,'Repair-6 failed/stale restore staging cleanup missing')
ok('HAMMAM_CONFIG_V1' in config_manager,'configuration format changed from HAMMAM_CONFIG_V1')
ok('SYSTEM_OWNER_REQUIRED_FOR_ROLEPERM_IMPORT' in config_manager,'Repair-6 ROLEPERM owner protection missing')
for code in ['MANAGE_SUBJECTS','MANAGE_REPORT_SETTINGS','MANAGE_SETTINGS','MANAGE_ACADEMIC_STRUCTURE']:
    ok(code in config_manager,f'Repair-6 configuration section authorization missing: {code}')
ok('withTransaction' in config_manager and 'preferences.setLanguage' in config_manager,'Repair-6 configuration transactional DB + post-commit UI preference flow missing')
ok('VIEW_AUDIT_LOG' in audit_repo and 'EXPORT_AUDIT_LOG' in audit_repo,'Repair-6 audit repository authorization missing')
ok('CsvCodec.escape' in audit_repo,'Repair-6 audit CSV spreadsheet/escaping hardening missing')
ok('FeatureFlagCatalog' in feature_repo and 'UNKNOWN_FEATURE_FLAG' in feature_repo and 'MANAGE_SETTINGS' in feature_repo,'Repair-6 feature-flag catalog/auth hardening missing')
ok('RUN_DATA_INTEGRITY' in integrity_repo,'Repair-6 data-integrity repository authorization missing')
ok('suggestedAction:String' in integrity_repo and 'INTEGRITY_ACTION_' in integrity_repo,'Repair-6 integrity action-code localization boundary missing')
ok('confirmRestore' in local_tools and 'restore_backup_confirm_title' in local_tools,'Repair-6 explicit restore confirmation UI missing')
ok('studentTransferInFlight' in admin_vm and 'timetableApprovalInFlight' in admin_vm,'Repair-6 duplicate destructive-operation guards missing')

print('STATIC_REGRESSION='+('PASS' if not errors else 'FAIL'))
for n in notes: print('NOTE',n)
for e in errors: print('ERROR',e)
sys.exit(1 if errors else 0)
