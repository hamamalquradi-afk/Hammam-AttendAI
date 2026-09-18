package com.hammam.attendai.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.repository.LectureActionResult
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.domain.setup.FirstRunSetup
import com.hammam.attendai.domain.setup.FirstRunSetupValidator
import com.hammam.attendai.sync.WorkOrchestrator
import com.hammam.attendai.ble.AttendanceForegroundService
import com.hammam.attendai.ble.DetectorState
import com.hammam.attendai.ai.AiKeyMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProviderStatusSummary(
    val name:String,
    val configured:Boolean,
    val connectionStatus:String,
    val selectedModel:String?=null,
    val lastTest:Long?=null,
    val error:String?=null,
)

data class HomeContextState(
    val nextLecture:LectureEntity?=null,
    val weeklyTimetable:WeeklyTimetableVersionEntity?=null,
    val scopedSubjects:List<SubjectEntity> = emptyList(),
    val semesterEndingSoon:Boolean=false,
)

data class DashboardState(
    val studentCount:Int=0,
    val pendingNotifications:Int=0,
    val pendingReports:Int=0,
    val pendingSync:Int=0,
    val activeLecture:LectureEntity?=null,
    val lastBackup:BackupHistoryEntity?=null,
    val failedWorkManagerJobs:Int=0,
    val lastSuccessfulSync:Long?=null,
    val providers:List<ProviderStatusSummary> = emptyList(),
    val recentSanitizedErrors:List<String> = emptyList(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app:Application,private val savedStateHandle:SavedStateHandle):AndroidViewModel(app){
    private val application=app
    private val container=(app as HammamAttendAiApplication).container
    private val dao=container.database.coreDao()
    val firstRunComplete:StateFlow<Boolean?> = container.preferences.firstRunComplete.map<Boolean,Boolean?>{it}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val currentUserId=container.preferences.userId.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val currentRole=container.preferences.role.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val permissions:StateFlow<Set<String>> = currentUserId.flatMapLatest { id ->
        if(id==null) flowOf(emptySet()) else dao.observePermissionCodes(id).map{it.toSet()}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptySet())
    val students=currentUserId.flatMapLatest{id->if(id==null)flowOf(emptyList()) else dao.observeScopedStudents(id)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reportHistory=currentUserId.flatMapLatest{id->if(id==null)flowOf(emptyList()) else dao.observeScopedReportHistory(id)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val featureFlags=container.featureFlags.observe().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val backendBaseUrl=container.preferences.backendBaseUrl.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val _backendAuthMasked=MutableStateFlow(container.secretStore.maskedSuffix("backend.auth.token"))
    val backendAuthMasked:StateFlow<String?> = _backendAuthMasked.asStateFlow()
    val theme=container.preferences.theme.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"SYSTEM")
    val language=container.preferences.language.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"AR")
    val academicWeekStart=container.preferences.academicWeekStart.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"MONDAY")
    val ownerSetupRequired=container.preferences.ownerSetupRequired.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    private val nextLecture=currentUserId.flatMapLatest{id->if(id==null)flowOf(null) else dao.observeNextLectureForUser(id)}
    private val latestWeekly=currentUserId.flatMapLatest{id->if(id==null)flowOf(null) else dao.observeLatestScopedWeeklyVersion(id)}
    private val scopedSubjects=currentUserId.flatMapLatest{id->if(id==null)flowOf(emptyList()) else dao.observeScopedSubjects(id)}
    val homeContext=combine(nextLecture,latestWeekly,scopedSubjects,dao.observeSemesters()){next,weekly,subjects,semesters->
        val today=java.time.LocalDate.now();val soon=today.plusDays(14)
        val ending=semesters.any{s->s.status.name=="ACTIVE"&&runCatching{java.time.LocalDate.parse(s.endDate)}.getOrNull()?.let{!it.isBefore(today)&&!it.isAfter(soon)}==true}
        HomeContextState(next,weekly,subjects,ending)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),HomeContextState())
    private val scopedActiveLecture=currentUserId.flatMapLatest{id->if(id==null)flowOf(null) else dao.observeScopedActiveLecture(id)}
    private val scopedReviewLecture=currentUserId.flatMapLatest{id->if(id==null)flowOf(null) else dao.observeScopedReviewLecture(id)}
    val attendanceLecture=combine(scopedActiveLecture,scopedReviewLecture){active,review->active?:review}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val baseDashboard=combine(
        students.map{it.size},dao.observePendingNotificationCount(),dao.observePendingReportCount(),scopedActiveLecture,dao.observeLastBackup()
    ){a,b,c,d,e->DashboardState(studentCount=a,pendingNotifications=b,pendingReports=c,activeLecture=d,lastBackup=e)}
    private val runtimeHealth=MutableStateFlow(DashboardState())
    val dashboard=combine(baseDashboard,dao.observePendingSyncCount(),runtimeHealth){state,sync,runtime->
        state.copy(
            pendingSync=sync,
            failedWorkManagerJobs=runtime.failedWorkManagerJobs,
            lastSuccessfulSync=runtime.lastSuccessfulSync,
            providers=runtime.providers,
            recentSanitizedErrors=runtime.recentSanitizedErrors,
        )
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),DashboardState())
    val bleState:StateFlow<DetectorState> = container.bleDetector.state.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),DetectorState.Idle)
    val activeLectureRecords:StateFlow<List<AttendanceRecordEntity>> = attendanceLecture.flatMapLatest { lecture ->
        val id=lecture?.id
        if(id==null) flowOf(emptyList()) else dao.observeLectureRecords(id)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private val selectedStudentId=savedStateHandle.getStateFlow<String?>("selectedStudentId",null)
    val selectedStudent:StateFlow<StudentEntity?> = combine(students,selectedStudentId){list,id->list.firstOrNull{it.id==id}}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val selectedStudentRecords:StateFlow<List<AttendanceRecordEntity>> = combine(currentUserId,selectedStudentId){user,id->user to id}.flatMapLatest { (user,id) ->
        if(user==null||id==null) flowOf(emptyList()) else dao.observeScopedStudentRecords(user,id)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private val _appUnlocked=MutableStateFlow(!container.appLock.configured())
    val appUnlocked:StateFlow<Boolean> = _appUnlocked.asStateFlow()
    private val _appLockConfigured=MutableStateFlow(container.appLock.configured());val appLockConfigured=_appLockConfigured.asStateFlow()
    private val _biometricEnabled=MutableStateFlow(container.appLock.biometricEnabled());val biometricEnabled=_biometricEnabled.asStateFlow()
    private val _message=MutableStateFlow<String?>(null)
    val message:StateFlow<String?> = _message.asStateFlow()
    private val _firstRunInProgress=MutableStateFlow(false)
    val firstRunInProgress:StateFlow<Boolean> = _firstRunInProgress.asStateFlow()
    private val _firstRunRecoveryAdmin=MutableStateFlow<UserEntity?>(null)
    val firstRunRecoveryAdmin:StateFlow<UserEntity?> = _firstRunRecoveryAdmin.asStateFlow()
    private var attendanceMutationInFlight=false
    private val _reportPreview=MutableStateFlow<GeneratedReportEntity?>(null)
    val reportPreview:StateFlow<GeneratedReportEntity?> = _reportPreview.asStateFlow()

    init {
        refreshSystemHealth()
        viewModelScope.launch{refreshFirstRunRecoveryState()}
    }

    private suspend fun refreshFirstRunRecoveryState(){
        if(container.preferences.firstRunComplete.first()){_firstRunRecoveryAdmin.value=null;return}
        val owner=dao.getSystemOwnerUser()
        if(owner!=null){
            container.preferences.completeFirstRun(owner.id,"SYSTEM_OWNER",container.preferences.language.first())
            _firstRunRecoveryAdmin.value=null
            return
        }
        if(dao.countUsers()>0){
            val admin=dao.getActiveAdministratorUser()
            _firstRunRecoveryAdmin.value=admin
            if(admin==null)_message.value="EXISTING_DATABASE_NO_ADMIN"
        }else{
            _firstRunRecoveryAdmin.value=null
        }
    }

    fun refreshSystemHealth(){viewModelScope.launch{
        val backendConfigured=!container.preferences.backendBaseUrl.first().isNullOrBlank()
        val flags=dao.getFeatureFlags().associate{it.code to it.enabled}
        val ai=runCatching{com.hammam.attendai.ai.AiProviderNames.all.map{provider->
            val h=container.aiProviderManager.health(provider)
            val configured=h.configured && (h.mode!=AiKeyMode.BACKEND_MANAGED || backendConfigured)
            ProviderStatusSummary(provider,configured,if(!configured)"NOT_CONFIGURED" else h.connectionStatus,h.selectedModel,h.lastTest,sanitizeHealthError(h.error))
        }}.getOrDefault(emptyList())
        suspend fun remote(name:String,channel:String):ProviderStatusSummary{
            val enabled=flags[name]==true;val latest=runCatching{dao.getLatestNotificationForChannel(channel)}.getOrNull()
            val configured=enabled&&backendConfigured
            val status=when{!enabled->"DISABLED";!backendConfigured->"NOT_CONFIGURED";latest?.status?.name=="FAILED"||latest?.status?.name=="NEEDS_MANUAL_REVIEW"->"ERROR";latest?.sentAt!=null->"CONNECTED";else->"READY"}
            return ProviderStatusSummary(name,configured,status,lastTest=latest?.sentAt?:latest?.scheduledAt,error=sanitizeHealthError(latest?.error))
        }
        val syncLatest=runCatching{dao.getLatestSyncQueueItem()}.getOrNull();val cloudEnabled=flags["CLOUD_SYNC"]==true
        val cloud=ProviderStatusSummary("CLOUD_SYNC",cloudEnabled&&backendConfigured,when{!cloudEnabled->"DISABLED";!backendConfigured->"NOT_CONFIGURED";syncLatest?.status?.name=="FAILED"||syncLatest?.status?.name=="NEEDS_MANUAL_REVIEW"->"ERROR";syncLatest?.status?.name=="SENT"->"CONNECTED";else->"READY"},lastTest=syncLatest?.lastAttempt,error=sanitizeHealthError(syncLatest?.error))
        val failed=withContext(Dispatchers.IO){runCatching{
            val wm=WorkManager.getInstance(application)
            listOf("hammam_sync","hammam_notifications","hammam_reports","hammam_lecture_materialization","hammam_timetable_reminder").sumOf{name->wm.getWorkInfosForUniqueWork(name).get().count{it.state==WorkInfo.State.FAILED}}
        }.getOrDefault(0)}
        val errors=(runCatching{dao.getRecentSyncErrors()+dao.getRecentNotificationErrors()+dao.getRecentReportErrors()}.getOrDefault(emptyList())).mapNotNull(::sanitizeHealthError).distinct().take(10)
        runtimeHealth.value=DashboardState(failedWorkManagerJobs=failed,lastSuccessfulSync=dao.getLastSuccessfulSync(),providers=ai+listOf(remote("WHATSAPP","WHATSAPP"),remote("EMAIL","EMAIL"),cloud),recentSanitizedErrors=errors)
    }}

    private fun sanitizeHealthError(value:String?):String?=value?.take(160)?.replace(Regex("(?i)(sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+|bearer\\s+[A-Za-z0-9._-]+)"),"[REDACTED]")

    fun completeFirstRun(setup:FirstRunSetup){
        if(_firstRunInProgress.value)return
        viewModelScope.launch{
            _firstRunInProgress.value=true
            try{
                val normalized=FirstRunSetupValidator.normalize(setup)
                require(dao.countUsers()==0){"DATABASE_ALREADY_INITIALIZED"}
                var createdUserId:String?=null
                container.database.withTransaction{
                    val userId=container.authorization.createInitialOwner(normalized.displayName)
                    createdUserId=userId
                    container.featureFlags.seedDefaults()
                    val yearId=if(normalized.academicYearName.isNotBlank())
                        container.academic.addAcademicYear(normalized.academicYearName,normalized.academicYearStart,normalized.academicYearEnd,userId) else null
                    if(yearId!=null&&normalized.semesterName.isNotBlank())
                        container.academic.addSemester(normalized.semesterName,yearId,normalized.semesterStart,normalized.semesterEnd,userId)
                }
                val userId=createdUserId?:error("FIRST_RUN_OWNER_CREATION_FAILED")
                var pinFailed=false
                normalized.pin?.let{pin->
                    runCatching{container.appLock.setPin(pin.toCharArray())}.onFailure{container.appLock.clear();pinFailed=true}
                }
                container.preferences.completeFirstRun(userId,"SYSTEM_OWNER",normalized.language)
                if(normalized.pin!=null&&!pinFailed){_appLockConfigured.value=true;_appUnlocked.value=true}
                _firstRunRecoveryAdmin.value=null
                _message.value=if(pinFailed)"PIN_SETUP_FAILED" else null
            }catch(t:Throwable){
                _message.value=t.message?:"FIRST_RUN_SETUP_FAILED"
                refreshFirstRunRecoveryState()
            }finally{
                _firstRunInProgress.value=false
            }
        }
    }

    fun recoverExistingAdministratorAsOwner(){
        if(_firstRunInProgress.value)return
        viewModelScope.launch{
            _firstRunInProgress.value=true
            try{
                val admin=dao.getActiveAdministratorUser()?:error("EXISTING_DATABASE_NO_ADMIN")
                container.authorization.seedAuthorizationModel()
                container.authorization.claimUpgradeSystemOwner(admin.id)
                container.preferences.completeFirstRun(admin.id,"SYSTEM_OWNER",container.preferences.language.first())
                _firstRunRecoveryAdmin.value=null
                _message.value=null
            }catch(t:Throwable){
                _message.value=t.message?:"FIRST_RUN_RECOVERY_FAILED"
            }finally{
                _firstRunInProgress.value=false
            }
        }
    }
    fun addStudent(name:String,number:String?){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"EDIT_STUDENTS")){_message.value="EDIT_STUDENTS_PERMISSION_REQUIRED";return@launch}
        runCatching{container.students.add(name,number,actor)}.onFailure{_message.value=it.message?:"STUDENT_ADD_FAILED"}
    }}
    fun selectStudent(id:String?){savedStateHandle["selectedStudentId"]=id}

    fun setBackendBaseUrl(url:String){viewModelScope.launch{container.preferences.setBackendBaseUrl(url.ifBlank{null});_message.value="BACKEND_URL_UPDATED";refreshSystemHealth()}}
    fun setBackendAuthToken(token:String){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@launch}
        container.secretStore.put("backend.auth.token",token.ifBlank{null});_backendAuthMasked.value=container.secretStore.maskedSuffix("backend.auth.token");_message.value="BACKEND_AUTH_UPDATED";refreshSystemHealth()
    }}
    fun deleteBackendAuthToken(){setBackendAuthToken("")}
    fun setTheme(mode:String){viewModelScope.launch{require(mode in setOf("SYSTEM","LIGHT","DARK"));container.preferences.setTheme(mode);_message.value="THEME_UPDATED"}}
    fun setLanguage(language:String){viewModelScope.launch{require(language in setOf("AR","EN"));container.preferences.setLanguage(language);_message.value="LANGUAGE_UPDATED"}}
    fun setAcademicWeekStart(day:String){viewModelScope.launch{if(!container.authorization.hasPermission(currentUserId.value,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@launch};container.preferences.setAcademicWeekStart(day);_message.value="ACADEMIC_WEEK_START_UPDATED"}}
    fun claimUpgradeSystemOwner(){viewModelScope.launch{val actor=currentUserId.value?:return@launch;runCatching{container.authorization.claimUpgradeSystemOwner(actor);container.preferences.setOwnerSetupRequired(false);container.preferences.setLocalRole("SYSTEM_OWNER")}.onSuccess{_message.value="SYSTEM_OWNER_SETUP_COMPLETE"}.onFailure{_message.value=it.message?:"SYSTEM_OWNER_SETUP_FAILED"}}}
    fun setFeatureFlag(code:String,enabled:Boolean){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@launch}
        container.featureFlags.set(code,enabled);_message.value="FEATURE_FLAG_UPDATED";refreshSystemHealth()
    }}
    fun clearMessage(){_message.value=null}
    fun unlockWithPin(pin:String){if(container.appLock.verify(pin.toCharArray())){_appUnlocked.value=true;_message.value=null}else _message.value="APP_LOCK_INVALID_PIN"}
    fun unlockWithBiometric(){if(container.appLock.biometricEnabled())_appUnlocked.value=true}
    fun configureAppLockPin(pin:String){viewModelScope.launch{val role=currentRole.value;require(role in setOf("SYSTEM_OWNER","System Owner","Administrator","Representative")){"APP_LOCK_ROLE_NOT_ALLOWED"};container.appLock.setPin(pin.toCharArray());_appLockConfigured.value=true;_appUnlocked.value=true;_message.value="APP_LOCK_PIN_UPDATED"}}
    fun disableAppLock(){viewModelScope.launch{container.appLock.clear();_appLockConfigured.value=false;_biometricEnabled.value=false;_appUnlocked.value=true;_message.value="APP_LOCK_DISABLED"}}
    fun setBiometricAppLock(enabled:Boolean){viewModelScope.launch{container.appLock.setBiometric(enabled);_biometricEnabled.value=enabled;_message.value="APP_LOCK_BIOMETRIC_UPDATED"}}

    fun takeOverAttendance(){viewModelScope.launch{
        val actor=currentUserId.value?:return@launch
        _message.value=when(val r=container.attendance.takeOverActiveLecture(actor,"Manual host takeover")){is LectureActionResult.Success->"ATTENDANCE_HOST_TAKEN_OVER";is LectureActionResult.Failure->r.reason}
    }}
    fun verifyDynamicQr(payload:String){viewModelScope.launch{
        val actor=currentUserId.value?:return@launch;val session=dao.getActiveSession()?:run{_message.value="NO_ACTIVE_SESSION";return@launch}
        val token=payload.trim().removePrefix("HAD1|");val resolved=container.presenceTokenResolver.resolve(token,System.currentTimeMillis())?:run{_message.value="QR_INVALID_OR_EXPIRED";return@launch}
        val ok=container.attendance.recordQrVerification(session.id,resolved.studentId,actor);_message.value=if(ok)"QR_ATTENDANCE_VERIFIED" else "QR_ATTENDANCE_REJECTED"
    }}

    fun startAttendance(){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"START_LECTURE")){_message.value="START_LECTURE_PERMISSION_REQUIRED";return@launch}
        _message.value=when(val r=container.attendance.startNextLecture(actor!!)){
            is LectureActionResult.Success->{
                val bleStarted=runCatching{ContextCompat.startForegroundService(application,Intent(application,AttendanceForegroundService::class.java))}.isSuccess
                if(bleStarted)"LECTURE_STARTED" else {container.attendance.markDetectorIssue("FOREGROUND_SERVICE_START_FAILED");"LECTURE_STARTED_BLE_FALLBACK"}
            }
            is LectureActionResult.Failure->r.reason
        }
    }}
    fun endAttendance(){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"END_LECTURE")){_message.value="END_LECTURE_PERMISSION_REQUIRED";return@launch}
        _message.value=when(val r=container.attendance.endActiveLecture(actor!!)){
            is LectureActionResult.Success->{application.stopService(Intent(application,AttendanceForegroundService::class.java));"LECTURE_ENDED_NEEDS_REVIEW"}
            is LectureActionResult.Failure->r.reason
        }
    }}

    fun editAttendance(recordId:String,status:FinalAttendanceStatus,attendancePercentage:Double,reason:String){
        if(attendanceMutationInFlight)return
        attendanceMutationInFlight=true
        viewModelScope.launch{try{
            val actor=currentUserId.value?:return@launch
            val ok=container.attendance.editAttendanceRecord(recordId,actor,status,attendancePercentage,reason)
            _message.value=if(ok)"ATTENDANCE_UPDATED" else "ATTENDANCE_UPDATE_REJECTED"
        }catch(e:Exception){_message.value=e.message?:"ATTENDANCE_UPDATE_FAILED"}finally{attendanceMutationInFlight=false}}
    }

    fun approveAttendance(freeze:Boolean=false){
        if(attendanceMutationInFlight)return
        attendanceMutationInFlight=true
        viewModelScope.launch{try{
            val actor=currentUserId.value?:return@launch
            val lecture=attendanceLecture.value?:run{_message.value="NO_LECTURE_FOR_REVIEW";return@launch}
            _message.value=when(val r=container.attendance.approveLecture(lecture.id,actor,freeze)){
                is LectureActionResult.Success->if(freeze)"ATTENDANCE_APPROVED_FROZEN" else "ATTENDANCE_APPROVED"
                is LectureActionResult.Failure->r.reason
            }
        }finally{attendanceMutationInFlight=false}}
    }

    fun resumeAttendanceDetection(){viewModelScope.launch{
        if(dao.getActiveSession()==null){_message.value="NO_ACTIVE_SESSION";return@launch}
        runCatching{ContextCompat.startForegroundService(application,Intent(application,AttendanceForegroundService::class.java).putExtra(AttendanceForegroundService.EXTRA_RESTORED,true))}
            .onSuccess{_message.value="ATTENDANCE_DETECTION_RESUMED"}.onFailure{container.attendance.markDetectorIssue("FOREGROUND_SERVICE_START_FAILED");_message.value="BLE_SERVICE_START_FAILED"}
    }}
    fun generateCurrentReport(){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"SEND_REPORTS")){_message.value="SEND_REPORTS_PERMISSION_REQUIRED";return@launch}
        val lecture=dashboard.value.activeLecture ?: actor?.let{dao.getNextLectureForUser(it)}
        if(lecture==null){_message.value="NO_LECTURE_FOR_REPORT";return@launch}
        val queued=container.reports.enqueueForLecture(lecture)
        _message.value=if(queued)"REPORT_QUEUED" else "REPORT_ALREADY_QUEUED"
        if(queued) WorkOrchestrator.kickReports(application)
    }}
    fun approveReport(jobId:String){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@launch}
        if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch}
        val ok=container.reports.approve(jobId,actor!!);_message.value=if(ok)"REPORT_APPROVED" else "REPORT_APPROVAL_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)
    }}
    fun loadReportPreview(jobId:String){viewModelScope.launch{val actor=currentUserId.value;if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch};_reportPreview.value=container.reports.generated(jobId);if(_reportPreview.value==null)_message.value="REPORT_NOT_GENERATED_YET"}}
    fun clearReportPreview(){_reportPreview.value=null}
    fun cancelReport(jobId:String){viewModelScope.launch{val actor=currentUserId.value;if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@launch};if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch};_message.value=if(container.reports.cancel(jobId,actor!!))"REPORT_CANCELLED" else "REPORT_CANCEL_NOT_AVAILABLE"}}
    fun regenerateReport(jobId:String){viewModelScope.launch{val actor=currentUserId.value;if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@launch};if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch};val ok=container.reports.regenerate(jobId,actor!!);_message.value=if(ok)"REPORT_REGENERATE_QUEUED" else "REPORT_REGENERATE_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)}}

    fun retryReport(jobId:String){viewModelScope.launch{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"SEND_REPORTS")){_message.value="SEND_REPORTS_PERMISSION_REQUIRED";return@launch}
        if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch}
        val ok=container.reports.retry(jobId,actor!!);_message.value=if(ok)"REPORT_RETRY_QUEUED" else "REPORT_RETRY_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)
    }}

}
