package com.hammam.attendai.ui

import com.hammam.attendai.ble.DynamicQrPresencePayload
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.repository.LectureActionResult
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.domain.setup.FirstRunSetup
import com.hammam.attendai.domain.setup.FirstRunSetupValidator
import com.hammam.attendai.sync.WorkOrchestrator
import com.hammam.attendai.sync.BackendAccountState
import com.hammam.attendai.sync.BackendSecretKeys
import com.hammam.attendai.ble.AttendanceForegroundService
import com.hammam.attendai.ble.DetectorState
import com.hammam.attendai.ai.AiKeyMode
import com.hammam.attendai.security.LocalAccessRules
import com.hammam.attendai.security.LocalSessionSnapshot
import com.hammam.attendai.security.LocalSessionStatus
import com.hammam.attendai.security.FirstRunBootstrapRules
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val _firstRunComplete=MutableStateFlow<Boolean?>(null)
    val firstRunComplete:StateFlow<Boolean?> = _firstRunComplete.asStateFlow()
    val sessionState:StateFlow<LocalSessionSnapshot> = container.preferences.userId.flatMapLatest { storedId ->
        if(storedId==null) flowOf(LocalAccessRules.resolve(null,false,false,emptyList()))
        else combine(dao.observeUserById(storedId),dao.observeRoleNames(storedId)){user,roles->
            LocalAccessRules.resolve(storedId,user!=null,user?.isActive==true,roles)
        }
    }.stateIn(viewModelScope,SharingStarted.Eagerly,LocalAccessRules.checking())
    val currentUserId:StateFlow<String?> = sessionState.map{it.activeUserId}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val currentRole:StateFlow<String?> = sessionState.map{it.primaryRole}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val permissions:StateFlow<Set<String>> = currentUserId.flatMapLatest { id ->
        if(id==null) flowOf(emptySet()) else dao.observePermissionCodes(id).map{it.toSet()}
    }.stateIn(viewModelScope,SharingStarted.Eagerly,emptySet())
    val students=currentUserId.flatMapLatest{id->if(id==null)flowOf(emptyList()) else dao.observeScopedStudents(id)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reportHistory=currentUserId.flatMapLatest{id->if(id==null)flowOf(emptyList()) else dao.observeScopedReportHistory(id)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val featureFlags=container.featureFlags.observe().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val backendBaseUrl=container.preferences.backendBaseUrl.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val syncWorkspaceId=container.preferences.syncWorkspaceId.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val backendAccountId=container.preferences.backendAccountId.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val backendAuthState=container.preferences.backendAuthState.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),BackendAccountState.UNAUTHENTICATED.name)
    val backendSessionExpiresAt=container.preferences.backendSessionExpiresAt.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val _backendAuthMasked=MutableStateFlow(container.secretStore.maskedSuffix(BackendSecretKeys.DEPLOYMENT_CREDENTIAL))
    val backendAuthMasked:StateFlow<String?> = _backendAuthMasked.asStateFlow()
    val theme=container.preferences.theme.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"SYSTEM")
    val language=container.preferences.language.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"AR")
    val academicWeekStart=container.preferences.academicWeekStart.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"MONDAY")
    val ownerSetupRequired=container.preferences.ownerSetupRequired.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    private val dashboardClock=flow{while(true){emit(System.currentTimeMillis());delay(30_000)}}
    private val nextLecture=combine(currentUserId,dashboardClock){id,now->id to now}.flatMapLatest{(id,now)->if(id==null)flowOf(null) else dao.observeNextLectureForUser(id,now)}
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
    private val _sessionRecoveryOwner=MutableStateFlow<UserEntity?>(null)
    val sessionRecoveryOwner:StateFlow<UserEntity?> = _sessionRecoveryOwner.asStateFlow()
    private val _sessionRecoveryAdmin=MutableStateFlow<UserEntity?>(null)
    val sessionRecoveryAdmin:StateFlow<UserEntity?> = _sessionRecoveryAdmin.asStateFlow()
    private val firstRunGate=AtomicBoolean(false)
    private val attendanceMutationInFlight=AtomicBoolean(false)
    private val attendanceStartInFlight=AtomicBoolean(false)
    private val attendanceEndInFlight=AtomicBoolean(false)
    private val attendanceTakeoverInFlight=AtomicBoolean(false)
    private val reportGenerationInFlight=AtomicBoolean(false)
    private val reportApprovalInFlight=ConcurrentHashMap.newKeySet<String>()
    private val reportRetryInFlight=ConcurrentHashMap.newKeySet<String>()
    private val reportCancelInFlight=ConcurrentHashMap.newKeySet<String>()
    private val reportRegenerateInFlight=ConcurrentHashMap.newKeySet<String>()
    private val _reportPreview=MutableStateFlow<GeneratedReportEntity?>(null)
    val reportPreview:StateFlow<GeneratedReportEntity?> = _reportPreview.asStateFlow()

    init {
        refreshSystemHealth()
        viewModelScope.launch{refreshFirstRunRecoveryState()}
        viewModelScope.launch{
            sessionState.collect{session->
                if(session.status==LocalSessionStatus.READY){
                    _sessionRecoveryOwner.value=null;_sessionRecoveryAdmin.value=null
                    session.primaryRole?.let{role->if(container.preferences.role.first()!=role)container.preferences.setLocalRole(role)}
                }else if(_firstRunComplete.value==true){
                    val owner=dao.getSystemOwnerUser();_sessionRecoveryOwner.value=owner
                    _sessionRecoveryAdmin.value=if(owner==null&&dao.getAnySystemOwnerUser()==null)dao.getActiveAdministratorUser() else null
                }
            }
        }
        viewModelScope.launch{while(true){runCatching{container.authorization.expireGrants()};delay(30_000)}}
        // PHASE 5F: prepare idempotent local sync backfill even while the device is offline.
        viewModelScope.launch{runCatching{container.syncProcessor.prepareLocalBackfill()}}
        viewModelScope.launch{runCatching{container.backendAccountSessions.refreshState()};refreshSystemHealth()}
    }

    private suspend fun refreshFirstRunRecoveryState(){
        val preferenceComplete=container.preferences.firstRunComplete.first()
        val userCount=dao.countUsers()
        val activeOwner=dao.getSystemOwnerUser()
        val anyOwner=dao.getAnySystemOwnerUser()
        when{
            userCount==0->{
                _firstRunComplete.value=false
                _firstRunRecoveryAdmin.value=null
            }
            activeOwner!=null->{
                if(!preferenceComplete)container.preferences.markInitializedWithoutSession()
                _firstRunComplete.value=true
                _firstRunRecoveryAdmin.value=null
            }
            preferenceComplete->{
                _firstRunComplete.value=true
                _firstRunRecoveryAdmin.value=null
            }
            anyOwner!=null->{
                _firstRunComplete.value=false
                _firstRunRecoveryAdmin.value=null
                _message.value="EXISTING_DATABASE_OWNER_UNAVAILABLE"
            }
            else->{
                val admin=dao.getActiveAdministratorUser()
                _firstRunComplete.value=false
                _firstRunRecoveryAdmin.value=admin
                if(admin==null)_message.value="EXISTING_DATABASE_NO_ADMIN"
            }
        }
        if(_firstRunComplete.value==true && sessionState.value.status!=LocalSessionStatus.READY){
            val owner=dao.getSystemOwnerUser();_sessionRecoveryOwner.value=owner
            _sessionRecoveryAdmin.value=if(owner==null&&dao.getAnySystemOwnerUser()==null)dao.getActiveAdministratorUser() else null
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
        val syncAuthenticated=container.preferences.backendAuthState.first()==BackendAccountState.AUTHENTICATED.name && !container.preferences.syncWorkspaceId.first().isNullOrBlank()
        val cloud=ProviderStatusSummary("CLOUD_SYNC",cloudEnabled&&backendConfigured&&syncAuthenticated,when{!cloudEnabled->"DISABLED";!backendConfigured->"NOT_CONFIGURED";!syncAuthenticated->"AUTH_REQUIRED";syncLatest?.status?.name=="FAILED"||syncLatest?.status?.name=="NEEDS_MANUAL_REVIEW"->"ERROR";syncLatest?.status?.name=="SENT"->"CONNECTED";else->"READY"},lastTest=syncLatest?.lastAttempt,error=sanitizeHealthError(syncLatest?.error))
        val failed=withContext(Dispatchers.IO){runCatching{
            val wm=WorkManager.getInstance(application)
            listOf("hammam_sync","hammam_notifications","hammam_reports","hammam_lecture_materialization","hammam_timetable_reminder").sumOf{name->wm.getWorkInfosForUniqueWork(name).get().count{it.state==WorkInfo.State.FAILED}}
        }.getOrDefault(0)}
        val errors=(runCatching{dao.getRecentSyncErrors()+dao.getRecentNotificationErrors()+dao.getRecentReportErrors()}.getOrDefault(emptyList())).mapNotNull(::sanitizeHealthError).distinct().take(10)
        runtimeHealth.value=DashboardState(failedWorkManagerJobs=failed,lastSuccessfulSync=dao.getLastSuccessfulSync(),providers=ai+listOf(remote("WHATSAPP","WHATSAPP"),remote("EMAIL","EMAIL"),cloud),recentSanitizedErrors=errors)
    }}

    private fun sanitizeHealthError(value:String?):String?=value?.take(160)?.replace(Regex("(?i)(sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+|bearer\\s+[A-Za-z0-9._-]+)"),"[REDACTED]")

    fun completeFirstRun(setup:FirstRunSetup){
        if(!firstRunGate.compareAndSet(false,true))return
        _firstRunInProgress.value=true
        viewModelScope.launch{
            try{
                val normalized=FirstRunSetupValidator.normalize(setup)
                require(FirstRunBootstrapRules.canCreateBootstrapOwner(dao.countUsers(),dao.getAnySystemOwnerUser()!=null)){"DATABASE_ALREADY_INITIALIZED"}
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
                container.preferences.setOwnerSetupRequired(false)
                _firstRunComplete.value=true
                if(normalized.pin!=null&&!pinFailed){_appLockConfigured.value=true;_appUnlocked.value=true}
                _firstRunRecoveryAdmin.value=null
                _message.value=if(pinFailed)"PIN_SETUP_FAILED" else null
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){
                _message.value=LocalAccessRules.safeErrorCode(e.message,"FIRST_RUN_SETUP_FAILED")
                refreshFirstRunRecoveryState()
            }finally{
                _firstRunInProgress.value=false
                firstRunGate.set(false)
            }
        }
    }

    fun recoverExistingAdministratorAsOwner(){
        if(!firstRunGate.compareAndSet(false,true))return
        _firstRunInProgress.value=true
        viewModelScope.launch{
            try{
                val admin=dao.getActiveAdministratorUser()?:error("EXISTING_DATABASE_NO_ADMIN")
                require(dao.getAnySystemOwnerUser()==null){"SYSTEM_OWNER_ALREADY_EXISTS"}
                container.authorization.seedAuthorizationModel()
                container.authorization.claimUpgradeSystemOwner(admin.id)
                container.preferences.completeFirstRun(admin.id,"SYSTEM_OWNER",container.preferences.language.first())
                container.preferences.setOwnerSetupRequired(false)
                _firstRunComplete.value=true
                _firstRunRecoveryAdmin.value=null
                _message.value=null
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){_message.value=LocalAccessRules.safeErrorCode(e.message,"FIRST_RUN_RECOVERY_FAILED")}
            finally{_firstRunInProgress.value=false;firstRunGate.set(false)}
        }
    }

    fun restoreSystemOwnerSession(){runtimeSafeLaunch("LOCAL_SESSION_RECOVERY_FAILED"){
        val owner=dao.getSystemOwnerUser()?:error("ACTIVE_SYSTEM_OWNER_REQUIRED")
        require("SYSTEM_OWNER" in container.authorization.roleNames(owner.id)){"ACTIVE_SYSTEM_OWNER_REQUIRED"}
        container.authorization.recordLocalSessionRestore(owner.id)
        container.preferences.setLocalSession(owner.id,"SYSTEM_OWNER")
        _message.value="LOCAL_SESSION_RESTORED"
    }}

    fun restoreAdministratorSessionForOwnerUpgrade(){runtimeSafeLaunch("LOCAL_SESSION_RECOVERY_FAILED"){
        require(dao.getAnySystemOwnerUser()==null){"SYSTEM_OWNER_ALREADY_EXISTS"}
        val admin=dao.getActiveAdministratorUser()?:error("ACTIVE_ADMINISTRATOR_REQUIRED")
        require("Administrator" in container.authorization.roleNames(admin.id)){"ACTIVE_ADMINISTRATOR_REQUIRED"}
        container.authorization.recordLocalSessionRestore(admin.id)
        container.preferences.setLocalSession(admin.id,"Administrator")
        container.preferences.setOwnerSetupRequired(true)
        _message.value="LOCAL_ADMIN_SESSION_RESTORED"
    }}
    fun addStudent(name:String,number:String?,levelId:String,batchId:String,sectionId:String,groupId:String){runtimeSafeLaunch("STUDENT_ADD_FAILED") {
        val actor=currentUserId.value?:error("LOCAL_SESSION_INVALID")
        if(!container.authorization.hasPermission(actor,"EDIT_STUDENTS")){_message.value="EDIT_STUDENTS_PERMISSION_REQUIRED";return@runtimeSafeLaunch}
        container.students.add(name,number,levelId,batchId,sectionId,groupId,actor)
    }}
    fun updateStudentAcademicScope(studentId:String,levelId:String,batchId:String,sectionId:String,groupId:String){runtimeSafeLaunch("STUDENT_SCOPE_UPDATE_FAILED") {
        val actor=currentUserId.value?:error("LOCAL_SESSION_INVALID")
        if(!container.authorization.hasPermission(actor,"EDIT_STUDENTS")){_message.value="EDIT_STUDENTS_PERMISSION_REQUIRED";return@runtimeSafeLaunch}
        container.students.updateAcademicScope(studentId,levelId,batchId,sectionId,groupId,actor)
    }}
    fun selectStudent(id:String?){savedStateHandle["selectedStudentId"]=id}

    private fun runtimeSafeLaunch(failureCode:String, action:suspend()->Unit)=viewModelScope.launch{
        try{ action() }
        catch(e:kotlinx.coroutines.CancellationException){ throw e }
        catch(e:Exception){Log.w("MainViewModel","$failureCode:${e.javaClass.simpleName}");_message.value=LocalAccessRules.safeErrorCode(e.message,failureCode)}
    }

    fun setBackendBaseUrl(url:String){runtimeSafeLaunch("BACKEND_URL_UPDATE_FAILED") {container.preferences.setBackendBaseUrl(url.ifBlank{null});_message.value="BACKEND_URL_UPDATED";refreshSystemHealth()}}
    fun setSyncWorkspaceId(workspaceId:String){runtimeSafeLaunch("SYNC_WORKSPACE_UPDATE_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        val normalized=workspaceId.trim()
        if(normalized.isNotEmpty()&&!com.hammam.attendai.sync.SyncIntegrityRules.validWorkspaceId(normalized)){_message.value="SYNC_WORKSPACE_INVALID";return@safe}
        val bindingError=container.syncIdentityGuard.workspaceChangeError(normalized.ifBlank{null})
        if(bindingError!=null){_message.value=bindingError;return@safe}
        container.preferences.setSyncWorkspaceId(normalized.ifBlank{null});_message.value="SYNC_WORKSPACE_UPDATED";refreshSystemHealth()
    }}
    fun setBackendAuthToken(token:String){runtimeSafeLaunch("BACKEND_AUTH_UPDATE_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        container.secretStore.put(BackendSecretKeys.DEPLOYMENT_CREDENTIAL,token.ifBlank{null});_backendAuthMasked.value=container.secretStore.maskedSuffix(BackendSecretKeys.DEPLOYMENT_CREDENTIAL);_message.value="BACKEND_AUTH_UPDATED";refreshSystemHealth()
    }}
    fun deleteBackendAuthToken(){setBackendAuthToken("")}
    fun provisionBackendAccount(){runtimeSafeLaunch("BACKEND_ACCOUNT_PROVISION_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        if(!container.preferences.backendAccountId.first().isNullOrBlank()&&container.preferences.backendAuthState.first()!=BackendAccountState.PROVISIONING_REQUIRED.name){_message.value="BACKEND_ACCOUNT_ALREADY_PROVISIONED";return@safe}
        val user=actor?.let{dao.getUserById(it)}?:run{_message.value="LOCAL_USER_REQUIRED";return@safe}
        val result=container.backendAccountSessions.provisionCurrentAccount(user.displayName,container.preferences.syncWorkspaceId.first())
        if(result.ok){container.syncProcessor.resumeAfterReauth();WorkOrchestrator.kickNetworkQueues(getApplication())}
        _message.value=if(result.ok)"BACKEND_ACCOUNT_PROVISIONED" else result.error?:"BACKEND_ACCOUNT_PROVISION_FAILED"
        refreshSystemHealth()
    }}
    fun renewBackendSession(){runtimeSafeLaunch("BACKEND_SESSION_RENEW_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        val result=container.backendAccountSessions.renewSession()
        if(result.ok){container.syncProcessor.resumeAfterReauth();WorkOrchestrator.kickNetworkQueues(getApplication())}
        _message.value=if(result.ok)"BACKEND_SESSION_ACTIVE" else result.error?:"BACKEND_SESSION_RENEW_FAILED";refreshSystemHealth()
    }}
    fun logoutBackendSession(){runtimeSafeLaunch("BACKEND_SESSION_LOGOUT_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        container.backendAccountSessions.logout();_message.value="BACKEND_SESSION_LOGGED_OUT";refreshSystemHealth()
    }}
    fun setTheme(mode:String){runtimeSafeLaunch("THEME_UPDATE_FAILED") {require(mode in setOf("SYSTEM","LIGHT","DARK"));container.preferences.setTheme(mode);_message.value="THEME_UPDATED"}}
    fun setLanguage(language:String){runtimeSafeLaunch("LANGUAGE_UPDATE_FAILED") {require(language in setOf("AR","EN"));container.preferences.setLanguage(language);_message.value="LANGUAGE_UPDATED"}}
    fun setAcademicWeekStart(day:String){runtimeSafeLaunch("ACADEMIC_WEEK_START_UPDATE_FAILED") safe@{if(!container.authorization.hasPermission(currentUserId.value,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe};container.preferences.setAcademicWeekStart(day);_message.value="ACADEMIC_WEEK_START_UPDATED"}}
    fun claimUpgradeSystemOwner(){runtimeSafeLaunch("SYSTEM_OWNER_SETUP_FAILED") safe@{val actor=currentUserId.value?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe};container.authorization.claimUpgradeSystemOwner(actor);container.preferences.setOwnerSetupRequired(false);container.preferences.setLocalRole("SYSTEM_OWNER");_message.value="SYSTEM_OWNER_SETUP_COMPLETE"}}
    fun setFeatureFlag(code:String,enabled:Boolean){runtimeSafeLaunch("FEATURE_FLAG_UPDATE_FAILED") safe@{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"MANAGE_SETTINGS")){_message.value="MANAGE_SETTINGS_PERMISSION_REQUIRED";return@safe}
        val uid=actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe};container.featureFlags.set(code,enabled,uid);_message.value="FEATURE_FLAG_UPDATED";refreshSystemHealth()
    }}
    fun clearMessage(){_message.value=null}
    fun reportUiMessage(code:String){_message.value=code}
    fun unlockWithPin(pin:String){if(container.appLock.verify(pin.toCharArray())){_appUnlocked.value=true;_message.value=null}else _message.value="APP_LOCK_INVALID_PIN"}
    fun unlockWithBiometric(){if(container.appLock.configured()&&container.appLock.biometricEnabled()){_appUnlocked.value=true;_message.value=null}else _message.value="APP_LOCK_BIOMETRIC_UNAVAILABLE"}
    fun onBiometricUnavailable(){_message.value="APP_LOCK_BIOMETRIC_UNAVAILABLE"}
    fun onBiometricCancelled(){_message.value="APP_LOCK_BIOMETRIC_CANCELLED"}
    fun onBiometricFailure(){_message.value="APP_LOCK_BIOMETRIC_FAILED"}
    fun configureAppLockPin(pin:String){runtimeSafeLaunch("APP_LOCK_PIN_UPDATE_FAILED") {require(LocalAccessRules.canConfigureAppLock(currentRole.value)){"APP_LOCK_ROLE_NOT_ALLOWED"};require(LocalAccessRules.validPin(pin)){"INVALID_PIN"};container.appLock.setPin(pin.toCharArray());_appLockConfigured.value=true;_appUnlocked.value=true;_message.value="APP_LOCK_PIN_UPDATED"}}
    fun disableAppLock(){runtimeSafeLaunch("APP_LOCK_DISABLE_FAILED") {require(LocalAccessRules.canConfigureAppLock(currentRole.value)){"APP_LOCK_ROLE_NOT_ALLOWED"};container.appLock.clear();_appLockConfigured.value=false;_biometricEnabled.value=false;_appUnlocked.value=true;_message.value="APP_LOCK_DISABLED"}}
    fun setBiometricAppLock(enabled:Boolean){runtimeSafeLaunch("APP_LOCK_BIOMETRIC_UPDATE_FAILED") {require(LocalAccessRules.canConfigureAppLock(currentRole.value)){"APP_LOCK_ROLE_NOT_ALLOWED"};container.appLock.setBiometric(enabled);_biometricEnabled.value=container.appLock.biometricEnabled();_message.value="APP_LOCK_BIOMETRIC_UPDATED"}}

    fun takeOverAttendance(){
        if(!attendanceTakeoverInFlight.compareAndSet(false,true))return
        runtimeSafeLaunch("ATTENDANCE_TAKEOVER_FAILED") safe@{try{
            val actor=currentUserId.value?:return@safe
            _message.value=when(val r=container.attendance.takeOverActiveLecture(actor,"Manual host takeover")){is LectureActionResult.Success->"ATTENDANCE_HOST_TAKEN_OVER";is LectureActionResult.Failure->r.reason}
        }finally{attendanceTakeoverInFlight.set(false)}}
    }
    fun verifyDynamicQr(payload:String){viewModelScope.launch{
        if(dao.isFeatureEnabled("QR_ATTENDANCE")!=true){_message.value="QR_ATTENDANCE_DISABLED";return@launch}
        val actor=currentUserId.value?:return@launch;val session=dao.getActiveSession()?:run{_message.value="NO_ACTIVE_SESSION";return@launch}
        val token=DynamicQrPresencePayload.extractToken(payload)?:run{_message.value="QR_PAYLOAD_INVALID";return@launch}
        val resolved=container.presenceTokenResolver.resolve(token,System.currentTimeMillis())?:run{_message.value="QR_INVALID_OR_EXPIRED";return@launch}
        val ok=container.attendance.recordQrVerification(session.id,resolved.studentId,resolved.deviceId,actor);_message.value=if(ok)"QR_ATTENDANCE_VERIFIED" else "QR_ATTENDANCE_REJECTED"
    }}

    fun startAttendance(){
        if(!attendanceStartInFlight.compareAndSet(false,true))return
        runtimeSafeLaunch("ATTENDANCE_START_FAILED") safe@{try{
            val actor=currentUserId.value
            if(!container.authorization.hasPermission(actor,"START_LECTURE")){_message.value="START_LECTURE_PERMISSION_REQUIRED";return@safe}
            _message.value=when(val r=container.attendance.startNextLecture(actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe})){
                is LectureActionResult.Success->{
                    val bleStarted=runCatching{ContextCompat.startForegroundService(application,Intent(application,AttendanceForegroundService::class.java))}.isSuccess
                    if(bleStarted)"LECTURE_STARTED" else {container.attendance.markDetectorIssue("FOREGROUND_SERVICE_START_FAILED");"LECTURE_STARTED_BLE_FALLBACK"}
                }
                is LectureActionResult.Failure->r.reason
            }
        }finally{attendanceStartInFlight.set(false)}}
    }
    fun endAttendance(){
        if(!attendanceEndInFlight.compareAndSet(false,true))return
        runtimeSafeLaunch("ATTENDANCE_END_FAILED") safe@{try{
            val actor=currentUserId.value
            if(!container.authorization.hasPermission(actor,"END_LECTURE")){_message.value="END_LECTURE_PERMISSION_REQUIRED";return@safe}
            _message.value=when(val r=container.attendance.endActiveLecture(actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe})){
                is LectureActionResult.Success->{application.stopService(Intent(application,AttendanceForegroundService::class.java));"LECTURE_ENDED_NEEDS_REVIEW"}
                is LectureActionResult.Failure->r.reason
            }
        }finally{attendanceEndInFlight.set(false)}}
    }

    fun editAttendance(recordId:String,status:FinalAttendanceStatus,attendancePercentage:Double,reason:String){
        if(!attendanceMutationInFlight.compareAndSet(false,true))return
        viewModelScope.launch{try{
            val actor=currentUserId.value?:return@launch
            val ok=container.attendance.editAttendanceRecord(recordId,actor,status,attendancePercentage,reason)
            _message.value=if(ok)"ATTENDANCE_UPDATED" else "ATTENDANCE_UPDATE_REJECTED"
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){_message.value=LocalAccessRules.safeErrorCode(e.message,"ATTENDANCE_UPDATE_FAILED")}finally{attendanceMutationInFlight.set(false)}}
    }

    fun approveAttendance(freeze:Boolean=false){
        if(!attendanceMutationInFlight.compareAndSet(false,true))return
        viewModelScope.launch{try{
            val actor=currentUserId.value?:return@launch
            val lecture=attendanceLecture.value?:run{_message.value="NO_LECTURE_FOR_REVIEW";return@launch}
            _message.value=when(val r=container.attendance.approveLecture(lecture.id,actor,freeze)){
                is LectureActionResult.Success->if(freeze)"ATTENDANCE_APPROVED_FROZEN" else "ATTENDANCE_APPROVED"
                is LectureActionResult.Failure->r.reason
            }
        }finally{attendanceMutationInFlight.set(false)}}
    }

    fun resumeAttendanceDetection(){viewModelScope.launch{
        if(dao.getActiveSession()==null){_message.value="NO_ACTIVE_SESSION";return@launch}
        runCatching{ContextCompat.startForegroundService(application,Intent(application,AttendanceForegroundService::class.java).putExtra(AttendanceForegroundService.EXTRA_RESTORED,true))}
            .onSuccess{_message.value="ATTENDANCE_DETECTION_RESUMED"}.onFailure{container.attendance.markDetectorIssue("FOREGROUND_SERVICE_START_FAILED");_message.value="BLE_SERVICE_START_FAILED"}
    }}
    fun generateCurrentReport(){
        if(!reportGenerationInFlight.compareAndSet(false,true))return
        runtimeSafeLaunch("REPORT_GENERATION_FAILED") safe@{try{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"SEND_REPORTS")){_message.value="SEND_REPORTS_PERMISSION_REQUIRED";return@safe}
        val lecture=actor?.let{dao.getLatestReportEligibleLectureForUser(it)}
        if(lecture==null){_message.value="NO_ELIGIBLE_LECTURE_FOR_REPORT";return@safe}
        val queued=container.reports.enqueueForLecture(lecture,actorId=actor)
        _message.value=if(queued)"REPORT_QUEUED" else "REPORT_ALREADY_QUEUED"
        if(queued) WorkOrchestrator.kickReports(application)
        }finally{reportGenerationInFlight.set(false)}}
    }
    fun approveReport(jobId:String){
        if(!reportApprovalInFlight.add(jobId))return
        runtimeSafeLaunch("REPORT_APPROVAL_FAILED") safe@{try{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@safe}
        if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@safe}
        val ok=container.reports.approve(jobId,actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe});_message.value=if(ok)"REPORT_APPROVED" else "REPORT_APPROVAL_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)
        }finally{reportApprovalInFlight.remove(jobId)}}
    }
    fun loadReportPreview(jobId:String){viewModelScope.launch{val actor=currentUserId.value;if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@launch};val row=container.reports.generated(jobId);if(row!=null && !java.io.File(row.filePath).isFile){container.reports.markGeneratedFileMissing(jobId);_reportPreview.value=null;_message.value="GENERATED_REPORT_FILE_MISSING"}else{_reportPreview.value=row;if(row==null)_message.value="REPORT_NOT_GENERATED_YET"}}}
    fun clearReportPreview(){_reportPreview.value=null}
    fun cancelReport(jobId:String){if(!reportCancelInFlight.add(jobId))return;runtimeSafeLaunch("REPORT_CANCEL_FAILED") safe@{try{val actor=currentUserId.value;if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@safe};if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@safe};_message.value=if(container.reports.cancel(jobId,actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe}))"REPORT_CANCELLED" else "REPORT_CANCEL_NOT_AVAILABLE"}finally{reportCancelInFlight.remove(jobId)}}}
    fun regenerateReport(jobId:String){if(!reportRegenerateInFlight.add(jobId))return;runtimeSafeLaunch("REPORT_REGENERATE_FAILED") safe@{try{val actor=currentUserId.value;if(!container.authorization.hasPermission(actor,"APPROVE_REPORT")){_message.value="APPROVE_REPORT_PERMISSION_REQUIRED";return@safe};if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@safe};val ok=container.reports.regenerate(jobId,actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe});_message.value=if(ok)"REPORT_REGENERATE_QUEUED" else "REPORT_REGENERATE_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)}finally{reportRegenerateInFlight.remove(jobId)}}}

    fun retryReport(jobId:String){
        if(!reportRetryInFlight.add(jobId))return
        runtimeSafeLaunch("REPORT_RETRY_FAILED") safe@{try{
        val actor=currentUserId.value
        if(!container.authorization.hasPermission(actor,"SEND_REPORTS")){_message.value="SEND_REPORTS_PERMISSION_REQUIRED";return@safe}
        if(!container.authorization.canAccessReportJob(actor,jobId)){_message.value="REPORT_SCOPE_PERMISSION_REQUIRED";return@safe}
        val ok=container.reports.retry(jobId,actor?:run{_message.value="LOCAL_SESSION_REQUIRED";return@safe});_message.value=if(ok)"REPORT_RETRY_QUEUED" else "REPORT_RETRY_NOT_AVAILABLE";if(ok)WorkOrchestrator.kickReports(application)
        }finally{reportRetryInFlight.remove(jobId)}}
    }

}
