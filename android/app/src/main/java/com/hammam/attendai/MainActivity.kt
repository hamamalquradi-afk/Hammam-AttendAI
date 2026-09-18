package com.hammam.attendai

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.hammam.attendai.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity:FragmentActivity(){
    private val vm:MainViewModel by viewModels()
    private val appealVm:AttendanceAppealViewModel by viewModels()
    private val aiVm:AiAssistantViewModel by viewModels()
    private val studentVm:StudentModeViewModel by viewModels()
    private val adminVm:AdminOperationsViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialLanguage=runBlocking{(application as HammamAttendAiApplication).container.preferences.language.first()};AppLocaleController.apply(this,initialLanguage)
        setContent{val theme by vm.theme.collectAsState();val language by vm.language.collectAsState();LaunchedEffect(language){if(AppLocaleController.apply(this@MainActivity,language))recreate()};HammamTheme(theme){MainShell(vm,appealVm,aiVm,studentVm,adminVm,onBiometricUnlock=::promptBiometricUnlock)}}
    }

    private fun promptBiometricUnlock(){
        val authenticators=BiometricManager.Authenticators.BIOMETRIC_WEAK
        if(BiometricManager.from(this).canAuthenticate(authenticators)!=BiometricManager.BIOMETRIC_SUCCESS)return
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                super.onAuthenticationSucceeded(result)
                vm.unlockWithBiometric()
            }
        })
        val info=BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()
        prompt.authenticate(info)
    }
}

enum class Tab{DASHBOARD,MY_ATTENDANCE,ATTENDANCE,STUDENTS,REPORTS,AI,SETTINGS}
enum class DetailRoute{NONE,STUDENT_HISTORY,APPEAL_FORM,APPEALS_REVIEW,ADMIN_OPERATIONS}

@Composable fun MainShell(vm:MainViewModel,appealVm:AttendanceAppealViewModel,aiVm:AiAssistantViewModel,studentVm:StudentModeViewModel,adminVm:AdminOperationsViewModel,onBiometricUnlock:()->Unit){
    val firstRun by vm.firstRunComplete.collectAsState()
    if(firstRun==null){Surface{CircularProgressIndicator()};return}
    if(firstRun==false){FirstRunScreen(vm::completeFirstRun);return}
    val appUnlocked by vm.appUnlocked.collectAsState();val appLockConfigured by vm.appLockConfigured.collectAsState();val biometricEnabled by vm.biometricEnabled.collectAsState()
    if(firstRun==true && appLockConfigured && !appUnlocked){AppLockScreen(biometricEnabled,vm::unlockWithPin,onBiometricUnlock);return}
    var tab by rememberSaveable{mutableStateOf(Tab.DASHBOARD)}
    var detail by rememberSaveable{mutableStateOf(DetailRoute.NONE)}
    val permissions by vm.permissions.collectAsState();val currentRole by vm.currentRole.collectAsState();val homeContext by vm.homeContext.collectAsState()
    val academicWeekStart by vm.academicWeekStart.collectAsState();val ownerSetupRequired by vm.ownerSetupRequired.collectAsState()
    val dash by vm.dashboard.collectAsState(); val students by vm.students.collectAsState();val attendanceLecture by vm.attendanceLecture.collectAsState();val records by vm.activeLectureRecords.collectAsState();val bleState by vm.bleState.collectAsState()
    val selectedStudent by vm.selectedStudent.collectAsState();val selectedStudentRecords by vm.selectedStudentRecords.collectAsState()
    val reportHistory by vm.reportHistory.collectAsState();val reportPreview by vm.reportPreview.collectAsState();val featureFlags by vm.featureFlags.collectAsState();val backendBaseUrl by vm.backendBaseUrl.collectAsState();val backendAuthMasked by vm.backendAuthMasked.collectAsState();val appealState by appealVm.state.collectAsState();val appealRows by appealVm.reviewRows.collectAsState();val appealFilter by appealVm.filter.collectAsState();val aiState by aiVm.state.collectAsState()
    val studentSelf by studentVm.student.collectAsState();val studentLecture by studentVm.activeLecture.collectAsState();val studentDevices by studentVm.devices.collectAsState();val studentRecords by studentVm.records.collectAsState();val replacementRequests by studentVm.replacementRequests.collectAsState();val studentAdvertising by studentVm.advertising.collectAsState();val studentPresenceError by studentVm.presenceError.collectAsState();val pairingPayload by studentVm.pairingPayload.collectAsState();val qrPayload by studentVm.qrPayload.collectAsState()
    val adminUniversities by adminVm.universities.collectAsState();val adminFaculties by adminVm.faculties.collectAsState();val adminDepartments by adminVm.departments.collectAsState();val adminYears by adminVm.academicYears.collectAsState();val adminSemesters by adminVm.semesters.collectAsState();val adminLevels by adminVm.levels.collectAsState();val adminBatches by adminVm.batches.collectAsState();val adminSections by adminVm.sections.collectAsState();val adminGroups by adminVm.groups.collectAsState();val adminTeachers by adminVm.teachers.collectAsState();val adminSubjects by adminVm.subjects.collectAsState();val adminVersions by adminVm.timetableVersions.collectAsState();val timetableReview by adminVm.review.collectAsState();val adminMessage by adminVm.message.collectAsState()
    val message by vm.message.collectAsState();val studentMessage by studentVm.message.collectAsState();val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(message){message?.let{snackbar.showSnackbar(it);vm.clearMessage()}}
    LaunchedEffect(studentMessage){studentMessage?.let{snackbar.showSnackbar(it);studentVm.clearMessage()}}
    LaunchedEffect(adminMessage){adminMessage?.let{snackbar.showSnackbar(it);adminVm.clearMessage()}}
    val items=buildList{
        add(Tab.DASHBOARD to R.string.dashboard)
        if(currentRole=="Student")add(Tab.MY_ATTENDANCE to R.string.my_attendance)
        if(permissions.any{it in setOf("START_LECTURE","END_LECTURE","EDIT_ATTENDANCE")})add(Tab.ATTENDANCE to R.string.attendance)
        if(permissions.any{it in setOf("VIEW_STUDENTS","EDIT_STUDENTS")})add(Tab.STUDENTS to R.string.students)
        if(permissions.any{it in setOf("VIEW_REPORTS","SEND_REPORTS")})add(Tab.REPORTS to R.string.reports)
        if(featureFlags.firstOrNull{it.code=="AI_ASSISTANT"}?.enabled==true)add(Tab.AI to R.string.ai_nav)
        add(Tab.SETTINGS to R.string.settings)
    }
    LaunchedEffect(items){if(items.none{it.first==tab})tab=Tab.DASHBOARD}
    Scaffold(
        snackbarHost={SnackbarHost(snackbar)},
        bottomBar={if(detail==DetailRoute.NONE)NavigationBar{items.forEach{(t,label)->NavigationBarItem(selected=tab==t,onClick={tab=t},alwaysShowLabel=false,icon={Icon(when(t){Tab.DASHBOARD->Icons.Default.Dashboard;Tab.MY_ATTENDANCE->Icons.Default.Person;Tab.ATTENDANCE->Icons.Default.HowToReg;Tab.STUDENTS->Icons.Default.Groups;Tab.REPORTS->Icons.Default.Assessment;Tab.AI->Icons.Default.SmartToy;Tab.SETTINGS->Icons.Default.Settings},null)},label={Text(stringResource(label))})}}}
    ){pad->
        Surface(Modifier.padding(pad)){
            when(detail){
                DetailRoute.STUDENT_HISTORY->{
                    val student=selectedStudent
                    if(student==null){LaunchedEffect(Unit){detail=DetailRoute.NONE}}
                    else StudentHistoryScreen(student,selectedStudentRecords,onBack={detail=DetailRoute.NONE}){record->appealVm.openRecord(record.id);detail=DetailRoute.APPEAL_FORM}
                }
                DetailRoute.APPEAL_FORM->AttendanceAppealFormScreen(appealState,onBack={appealVm.clearForm();detail=DetailRoute.STUDENT_HISTORY}){reason,description,uri->appealVm.submit(reason,description,uri)}
                DetailRoute.APPEALS_REVIEW->AppealsReviewScreen(appealRows,appealFilter,appealVm::setFilter,onBack={detail=DetailRoute.NONE}){id,accept,note,status,pct->appealVm.review(id,accept,note,status,pct)}
                DetailRoute.ADMIN_OPERATIONS->AdminOperationsScreen(permissions,adminUniversities,adminFaculties,adminDepartments,adminYears,adminSemesters,adminLevels,adminBatches,adminSections,adminGroups,adminTeachers,adminSubjects,adminVersions,timetableReview,onBack={detail=DetailRoute.NONE},adminVm=adminVm)
                DetailRoute.NONE->when(tab){
                    Tab.DASHBOARD->{
                        val pendingApprovals=reportHistory.count{it.status.name=="PENDING_APPROVAL"}
                        val pendingReviews=records.count{it.finalStatus.name=="MANUAL_REVIEW"||it.approvalStatus.name=="PENDING"}
                        val daily=DailyHomeUiState(currentRole,permissions,homeContext,appealRows.count{it.status.name=="PENDING"},pendingApprovals,pendingReviews,bleState,studentLecture,studentAdvertising,studentDevices.count{it.status.name=="ACTIVE"})
                        DashboardScreen(dash,permissions.contains("VIEW_SYSTEM_HEALTH"),daily,vm::startAttendance,vm::resumeAttendanceDetection,vm::takeOverAttendance,onOpenManagement={detail=DetailRoute.ADMIN_OPERATIONS},onOpenMyAttendance={tab=Tab.MY_ATTENDANCE})
                    }
                    Tab.MY_ATTENDANCE->StudentModeScreen(studentSelf,studentLecture,studentDevices,studentRecords,replacementRequests,studentAdvertising,studentPresenceError,pairingPayload,qrPayload,studentVm::enrollInitial,studentVm::requestReplacement,studentVm::generateQrFallback,studentVm::startAdvertising,studentVm::stopAdvertising){r->appealVm.openRecord(r.id);detail=DetailRoute.APPEAL_FORM}
                    Tab.ATTENDANCE->AttendanceScreen(attendanceLecture,records,bleState,permissions.contains("EDIT_ATTENDANCE"),permissions.contains("APPROVE_ATTENDANCE"),permissions.contains("EDIT_FROZEN_ATTENDANCE"),vm::startAttendance,vm::endAttendance,vm::resumeAttendanceDetection,vm::takeOverAttendance,vm::verifyDynamicQr,vm::editAttendance,vm::approveAttendance)
                    Tab.STUDENTS->StudentsScreen(students,vm::addStudent){s->vm.selectStudent(s.id);detail=DetailRoute.STUDENT_HISTORY}
                    Tab.REPORTS->ReportsScreen(reportHistory,reportPreview,vm::generateCurrentReport,vm::approveReport,vm::retryReport,vm::loadReportPreview,vm::clearReportPreview,vm::cancelReport,vm::regenerateReport)
                    Tab.AI->AiAssistantScreen(aiState,aiVm::setQuery,aiVm::ask,aiVm::summarizeWithCloud)
                    Tab.SETTINGS->SettingsScreen(permissions.contains("REVIEW_APPEALS"),permissions.contains("MANAGE_SETTINGS"),permissions.any{it in setOf("MANAGE_ACADEMIC_STRUCTURE","MANAGE_TEACHERS","MANAGE_SUBJECTS","MANAGE_TIMETABLE","MANAGE_REPORT_SETTINGS","MANAGE_AI_PROVIDER","MANAGE_BACKUP","RUN_DATA_INTEGRITY")},currentRole in setOf("SYSTEM_OWNER","System Owner","Administrator","Representative"),permissions.contains("VIEW_SYSTEM_HEALTH"),appLockConfigured,biometricEnabled,language,theme,academicWeekStart,ownerSetupRequired&&currentRole=="Administrator",backendBaseUrl,backendAuthMasked,featureFlags,dash,vm::setBackendBaseUrl,vm::setBackendAuthToken,vm::deleteBackendAuthToken,vm::setFeatureFlag,vm::configureAppLockPin,vm::disableAppLock,vm::setBiometricAppLock,vm::setLanguage,vm::setTheme,vm::setAcademicWeekStart,vm::claimUpgradeSystemOwner,onOpenAppeals={appealVm.setFilter(com.hammam.attendai.domain.model.AppealStatus.PENDING);detail=DetailRoute.APPEALS_REVIEW},onOpenManagement={detail=DetailRoute.ADMIN_OPERATIONS})
                }
            }
        }
    }
}
