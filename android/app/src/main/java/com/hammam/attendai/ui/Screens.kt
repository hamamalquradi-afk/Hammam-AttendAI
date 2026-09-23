@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hammam.attendai.ui

import com.hammam.attendai.sync.SyncIntegrityRules

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hammam.attendai.BuildConfig
import com.hammam.attendai.R
import com.hammam.attendai.ai.*
import com.hammam.attendai.appeals.AttachmentValidationResult
import com.hammam.attendai.appeals.AttachmentValidator
import com.hammam.attendai.ble.DetectorState
import com.hammam.attendai.ble.QrCodec
import com.hammam.attendai.ble.BleOperation
import com.hammam.attendai.ble.BlePermissionPolicy
import com.hammam.attendai.ble.BlePlatformReadiness
import com.hammam.attendai.data.local.dao.AppealReviewRow
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.domain.setup.FirstRunSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.biometric.BiometricManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.hammam.attendai.importexport.FileIoSafety
import com.hammam.attendai.security.LocalAccessRules
import com.hammam.attendai.security.LocalSessionStatus
import java.io.File
import java.util.Date

data class DailyHomeUiState(
    val role:String?,val permissions:Set<String>,val home:HomeContextState,
    val pendingAppeals:Int,val pendingApprovalReports:Int,val pendingReviews:Int,
    val bleState:DetectorState,val studentLecture:LectureEntity?,val studentAdvertising:Boolean,val studentDeviceCount:Int,
)

@Composable fun DashboardScreen(
    s:DashboardState,showSystemHealth:Boolean,daily:DailyHomeUiState,
    onStartAttendance:()->Unit,onResumeAttendance:()->Unit,onTakeOver:()->Unit,onOpenManagement:()->Unit,onOpenMyAttendance:()->Unit,
){
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)}
        item{Card{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(stringResource(R.string.active_lecture),style=MaterialTheme.typography.titleMedium);Text(if(s.activeLecture==null)stringResource(R.string.no_active_lecture) else s.activeLecture.subjectId)
            daily.home.nextLecture?.let{Text("${stringResource(R.string.next_lecture)}: ${formatTime(it.scheduledStart)}",style=MaterialTheme.typography.bodySmall)}
        }}}
        when(daily.role){
            "Student"->{
                item{Metric(stringResource(R.string.my_attendance),if(daily.studentLecture==null)stringResource(R.string.no_active_lecture) else stringResource(R.string.active_lecture),Modifier.fillMaxWidth())}
                item{MetricPair(stringResource(R.string.presence_detection_status),if(daily.studentAdvertising)stringResource(R.string.enabled) else stringResource(R.string.disabled),stringResource(R.string.my_device),daily.studentDeviceCount.toString())}
                item{FilledTonalButton(onClick=onOpenMyAttendance,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.my_attendance))}}
            }
            "Teacher"->{
                item{Text(stringResource(R.string.own_subjects),style=MaterialTheme.typography.titleMedium)}
                item{Text(daily.home.scopedSubjects.joinToString(" • "){it.name}.ifBlank{"—"})}
                item{Metric(stringResource(R.string.pending_reports),daily.pendingApprovalReports.toString(),Modifier.fillMaxWidth())}
            }
            "Representative","Assistant Representative"->{
                item{MetricPair(stringResource(R.string.pending_reviews),daily.pendingReviews.toString(),stringResource(R.string.attendance_appeals),daily.pendingAppeals.toString())}
                item{Text("${stringResource(R.string.weekly_timetable_status)}: ${daily.home.weeklyTimetable?.status?.let{localizedInternalLabel(it)} ?: stringResource(R.string.status_not_imported)}")}
                val now=System.currentTimeMillis();val next=daily.home.nextLecture;val canStart=next!=null&&next.status in setOf(LectureStatus.READY,LectureStatus.SCHEDULED)&&next.scheduledEnd>next.scheduledStart&&now>=next.scheduledStart&&now<next.scheduledEnd
                if(s.activeLecture==null && canStart && "START_LECTURE" in daily.permissions)item{Button(onClick=onStartAttendance,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.start_attendance))}}
                if(s.activeLecture!=null)item{OutlinedButton(onClick=onResumeAttendance,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.resume_attendance))}}
                if(daily.role=="Assistant Representative"&&s.activeLecture!=null&&"TAKE_OVER_ATTENDANCE" in daily.permissions)item{Button(onClick=onTakeOver,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.take_over_attendance))}}
                if(s.activeLecture!=null&&daily.bleState !is DetectorState.Active)item{Text(stringResource(R.string.ble_readiness_warning),color=MaterialTheme.colorScheme.error)}
            }
            "Academic Supervisor"->{
                item{Metric(stringResource(R.string.pending_reports),daily.pendingApprovalReports.toString(),Modifier.fillMaxWidth())}
                item{Text(daily.home.scopedSubjects.joinToString(" • "){it.name}.ifBlank{stringResource(R.string.no_scoped_subjects)})}
                if(LocalAccessRules.hasManagementAccess(daily.permissions))item{FilledTonalButton(onClick=onOpenManagement,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.management_tools))}}
            }
            "SYSTEM_OWNER","System Owner","Administrator"->{
                item{MetricPair(stringResource(R.string.pending_notifications),s.pendingNotifications.toString(),stringResource(R.string.pending_reports),s.pendingReports.toString())}
                item{FilledTonalButton(onClick=onOpenManagement,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.management_tools))}}
                if(daily.home.semesterEndingSoon)item{Text(stringResource(R.string.semester_ending_action),color=MaterialTheme.colorScheme.primary)}
            }
            else->{item{Metric(stringResource(R.string.total_students),s.studentCount.toString(),Modifier.fillMaxWidth())}}
        }
        if(daily.role!="Student")item{Metric(stringResource(R.string.total_students),s.studentCount.toString(),Modifier.fillMaxWidth())}
        if(showSystemHealth){
            item{Text(stringResource(R.string.system_health),style=MaterialTheme.typography.titleLarge)}
            item{DeviceReadinessCard(s)}
        }
    }
}

@Composable private fun MetricPair(label1:String,value1:String,label2:String,value2:String){
    BoxWithConstraints(Modifier.fillMaxWidth()){
        if(maxWidth<520.dp)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Metric(label1,value1,Modifier.fillMaxWidth());Metric(label2,value2,Modifier.fillMaxWidth())}
        else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric(label1,value1,Modifier.weight(1f));Metric(label2,value2,Modifier.weight(1f))}
    }
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier=Modifier){Card(modifier){Column(Modifier.padding(16.dp)){Text(value,style=MaterialTheme.typography.headlineSmall);Text(label)}}}

data class DeviceSnapshot(
    val sdk:Int,val androidRelease:String,val appVersion:String,val manufacturer:String,val model:String,
    val bleSupported:Boolean,val blePermissionsGranted:Boolean,val bluetoothEnabled:Boolean?,val notificationPermission:Boolean,
    val batteryUnrestricted:Boolean,val internet:Boolean,val databaseVersion:Int,val pendingSync:Int,val pendingNotifications:Int,val pendingReports:Int,
    val failedWorkManagerJobs:Int,val lastSuccessfulSync:Long?,val lastBackupAt:Long?,val providers:List<ProviderStatusSummary>,val recentSanitizedErrors:List<String>
)

@SuppressLint("MissingPermission")
private fun readDeviceSnapshot(context:Context,s:DashboardState):DeviceSnapshot{
    val pm=context.packageManager
    val bleSupported=pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val blePermissionsGranted=BlePlatformReadiness.missingPermissions(context,BleOperation.SCAN).isEmpty()
    val bluetoothEnabled=if(!bleSupported||!blePermissionsGranted)null else runCatching{(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled}.getOrNull()
    val notificationPermission=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryUnrestricted=Build.VERSION.SDK_INT<23 || power.isIgnoringBatteryOptimizations(context.packageName)
    val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val internet=connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    return DeviceSnapshot(Build.VERSION.SDK_INT,Build.VERSION.RELEASE,BuildConfig.VERSION_NAME,Build.MANUFACTURER,Build.MODEL,bleSupported,blePermissionsGranted,bluetoothEnabled,notificationPermission,batteryUnrestricted,internet,BuildConfig.DATABASE_VERSION,s.pendingSync,s.pendingNotifications,s.pendingReports,s.failedWorkManagerJobs,s.lastSuccessfulSync,s.lastBackup?.createdAt,s.providers,s.recentSanitizedErrors)
}

@Composable private fun DeviceReadinessCard(s:DashboardState){
    val context=LocalContext.current
    val d=readDeviceSnapshot(context,s)
    Card{Column(Modifier.fillMaxWidth().padding(vertical=4.dp)){
        ListItem(headlineContent={Text(stringResource(R.string.android_version))},supportingContent={LtrText("${d.androidRelease} / API ${d.sdk}")},leadingContent={Icon(Icons.Default.Android,null)})
        ListItem(headlineContent={Text(stringResource(R.string.app_version))},supportingContent={LtrText(d.appVersion)},leadingContent={Icon(Icons.Default.Info,null)})
        ListItem(headlineContent={Text(stringResource(R.string.database_status))},supportingContent={Text("${stringResource(R.string.enabled)} • v${d.databaseVersion}")},leadingContent={Icon(Icons.Default.Storage,null)})
        ListItem(headlineContent={Text(stringResource(R.string.ble_supported))},supportingContent={Text(if(d.bleSupported)stringResource(R.string.enabled) else stringResource(R.string.disabled))},leadingContent={Icon(Icons.Default.Bluetooth,null)})
        ListItem(headlineContent={Text(stringResource(R.string.bluetooth_enabled))},supportingContent={Text(if(!d.blePermissionsGranted)stringResource(R.string.permission_required) else if(d.bluetoothEnabled==true)stringResource(R.string.enabled) else stringResource(R.string.disabled))})
        ListItem(headlineContent={Text(stringResource(R.string.notification_permission))},supportingContent={Text(if(d.notificationPermission)stringResource(R.string.granted) else stringResource(R.string.permission_required))})
        ListItem(headlineContent={Text(stringResource(R.string.internet_status))},supportingContent={Text(if(d.internet)stringResource(R.string.online) else stringResource(R.string.offline))})
        ListItem(headlineContent={Text(stringResource(R.string.battery_optimization))},supportingContent={Text(if(d.batteryUnrestricted)stringResource(R.string.battery_unrestricted) else stringResource(R.string.battery_optimized))})
        ListItem(headlineContent={Text(stringResource(R.string.pending_sync))},supportingContent={LtrText("${d.pendingSync} / ${d.pendingNotifications} / ${d.pendingReports}")})
        ListItem(headlineContent={Text(stringResource(R.string.failed_workmanager_jobs))},supportingContent={LtrText(d.failedWorkManagerJobs.toString())})
        ListItem(headlineContent={Text(stringResource(R.string.last_successful_sync))},supportingContent={Text(d.lastSuccessfulSync?.let(::formatTime) ?: "—")})
        ListItem(headlineContent={Text(stringResource(R.string.last_backup))},supportingContent={Text(d.lastBackupAt?.let(::formatTime) ?: "—")})
        if(d.providers.isNotEmpty()){HorizontalDivider();Text(stringResource(R.string.provider_health),Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.titleMedium)}
        d.providers.forEach{p->ListItem(headlineContent={LtrText(p.name)},supportingContent={Column{Text("${if(p.configured)stringResource(R.string.configured) else stringResource(R.string.not_configured)} • ${localizedInternalLabel(p.connectionStatus)}");p.selectedModel?.let{LtrText(it)};p.lastTest?.let{Text("${stringResource(R.string.last_test)}: ${formatTime(it)}")};p.error?.let{Text(userMessageText(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}})}
    }}
}

internal fun sanitizeDiagnosticValue(value:String):String=value
    .replace(Regex("""(?i)((?:authorization|api[_ -]?key|token|password|pin(?:_hash)?)\s*[:=]\s*(?:bearer\s+)?[^\s,;|]+|bearer\s+[^\s,;|]+|sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+)"""),"[REDACTED]")
    .replace('\n',' ').replace('\r',' ').take(240)

internal fun diagnosticsText(d:DeviceSnapshot)=buildString{
    appendLine("Hammam AttendAI diagnostics")
    appendLine("appVersion=${sanitizeDiagnosticValue(d.appVersion)}")
    appendLine("androidRelease=${sanitizeDiagnosticValue(d.androidRelease)}")
    appendLine("androidSdk=${d.sdk}")
    appendLine("manufacturer=${sanitizeDiagnosticValue(d.manufacturer)}")
    appendLine("model=${sanitizeDiagnosticValue(d.model)}")
    appendLine("databaseVersion=${d.databaseVersion}")
    appendLine("bleSupported=${d.bleSupported}")
    appendLine("blePermissionsGranted=${d.blePermissionsGranted}")
    appendLine("bluetoothEnabled=${d.bluetoothEnabled}")
    appendLine("notificationPermission=${d.notificationPermission}")
    appendLine("batteryUnrestricted=${d.batteryUnrestricted}")
    appendLine("internet=${d.internet}")
    appendLine("pendingSync=${d.pendingSync}")
    appendLine("pendingNotifications=${d.pendingNotifications}")
    appendLine("pendingReports=${d.pendingReports}")
    appendLine("failedWorkManagerJobs=${d.failedWorkManagerJobs}")
    appendLine("lastSuccessfulSync=${d.lastSuccessfulSync ?: "NONE"}")
    appendLine("lastBackupAt=${d.lastBackupAt ?: "NONE"}")
    d.providers.forEach{p->appendLine("provider.${sanitizeDiagnosticValue(p.name)}=configured:${p.configured},status:${sanitizeDiagnosticValue(p.connectionStatus)},model:${sanitizeDiagnosticValue(p.selectedModel ?: "NONE")},lastTest:${p.lastTest ?: "NONE"},error:${sanitizeDiagnosticValue(p.error ?: "NONE")}")}
    appendLine("recentSanitizedErrors=${sanitizeDiagnosticValue(d.recentSanitizedErrors.joinToString("|").ifBlank{"NONE"})}")
}

@Composable fun StudentsScreen(
    students:List<StudentEntity>,levels:List<LevelEntity>,batches:List<BatchEntity>,sections:List<SectionEntity>,groups:List<GroupEntity>,
    onAdd:(String,String?,String,String,String,String)->Unit,onUpdateScope:(String,String,String,String,String)->Unit,onSelect:(StudentEntity)->Unit
){
    var query by rememberSaveable{mutableStateOf("")};var showAdd by rememberSaveable{mutableStateOf(false)};var scopeStudentId by rememberSaveable{mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Text(stringResource(R.string.students),style=MaterialTheme.typography.headlineMedium)
        FilledTonalButton(onClick={showAdd=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.PersonAdd,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.add_student))}
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.search_students))},leadingIcon={Icon(Icons.Default.Search,null)})
        val filtered=students.filter{query.isBlank()||it.fullName.contains(query,true)||(it.universityNumber?.contains(query)==true)}
        if(filtered.isEmpty())Box(Modifier.fillMaxSize().padding(24.dp)){Text(stringResource(R.string.empty_students))}
        else LazyColumn(Modifier.weight(1f)){items(filtered,key={it.id}){student->
            val batch=student.batchId?.let{id->batches.firstOrNull{it.id==id}};val section=student.sectionId?.let{id->sections.firstOrNull{it.id==id}};val group=student.groupId?.let{id->groups.firstOrNull{it.id==id}}
            val incomplete=student.levelId==null||levels.none{it.id==student.levelId}||batch?.levelId!=student.levelId||section?.batchId!=student.batchId||group?.sectionId!=student.sectionId
            ListItem(
                headlineContent={Text(student.fullName)},
                supportingContent={Column{LtrText(student.universityNumber.orEmpty());if(incomplete)Text(stringResource(R.string.needs_review),color=MaterialTheme.colorScheme.error)}},
                leadingContent={Icon(Icons.Default.AccountCircle,null)},
                trailingContent={IconButton(onClick={scopeStudentId=student.id}){Icon(Icons.Default.Edit,null)}},
                modifier=Modifier.clickable{onSelect(student)}
            )
        }}
    }
    if(showAdd)StudentScopeDialog(
        title=stringResource(R.string.add_student),levels=levels,batches=batches,sections=sections,groups=groups,showIdentity=true,
        onDismiss={showAdd=false},onConfirm={name,number,level,batch,section,group->onAdd(name,number,level,batch,section,group);showAdd=false}
    )
    scopeStudentId?.let{id->
        val student=students.firstOrNull{it.id==id}
        if(student!=null)StudentScopeDialog(
            title=student.fullName,levels=levels,batches=batches,sections=sections,groups=groups,showIdentity=false,
            initialLevel=student.levelId.orEmpty(),initialBatch=student.batchId.orEmpty(),initialSection=student.sectionId.orEmpty(),initialGroup=student.groupId.orEmpty(),
            onDismiss={scopeStudentId=null},onConfirm={_,_,level,batch,section,group->onUpdateScope(student.id,level,batch,section,group);scopeStudentId=null}
        )
    }
}

@Composable private fun StudentScopeDialog(
    title:String,levels:List<LevelEntity>,batches:List<BatchEntity>,sections:List<SectionEntity>,groups:List<GroupEntity>,showIdentity:Boolean,
    initialLevel:String="",initialBatch:String="",initialSection:String="",initialGroup:String="",onDismiss:()->Unit,
    onConfirm:(String,String?,String,String,String,String)->Unit
){
    var name by rememberSaveable{mutableStateOf("")};var number by rememberSaveable{mutableStateOf("")}
    var levelId by rememberSaveable{mutableStateOf(initialLevel)};var batchId by rememberSaveable{mutableStateOf(initialBatch)};var sectionId by rememberSaveable{mutableStateOf(initialSection)};var groupId by rememberSaveable{mutableStateOf(initialGroup)}
    val levelBatches=batches.filter{it.levelId==levelId};val batchSections=sections.filter{it.batchId==batchId};val sectionGroups=groups.filter{it.sectionId==sectionId}
    val validScope=levels.any{it.id==levelId}&&levelBatches.any{it.id==batchId}&&batchSections.any{it.id==sectionId}&&sectionGroups.any{it.id==groupId}
    AlertDialog(
        onDismissRequest=onDismiss,
        confirmButton={TextButton(enabled=validScope&&(!showIdentity||name.isNotBlank()),onClick={onConfirm(name,number.ifBlank{null},levelId,batchId,sectionId,groupId)}){Text(stringResource(R.string.continue_action))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}},title={Text(title)},
        text={Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(showIdentity){OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.full_name))});OutlinedTextField(number,{number=it},label={Text(stringResource(R.string.university_number))},textStyle=technicalTextStyle())}
            EntityChoice(stringResource(R.string.level),levelId,levels.map{it.id to it.name}){levelId=it;batchId="";sectionId="";groupId=""}
            EntityChoice(stringResource(R.string.batch),batchId,levelBatches.map{it.id to it.name}){batchId=it;sectionId="";groupId=""}
            EntityChoice(stringResource(R.string.section),sectionId,batchSections.map{it.id to it.name}){sectionId=it;groupId=""}
            EntityChoice(stringResource(R.string.group),groupId,sectionGroups.map{it.id to it.name}){groupId=it}
        }}
    )
}

@Composable fun StudentModeScreen(student:StudentEntity?,activeLecture:LectureEntity?,devices:List<StudentDeviceEntity>,records:List<AttendanceRecordEntity>,requests:List<DeviceReplacementRequestEntity>,advertising:Boolean,presenceError:String?,pairingPayload:String?,qrPayload:String?,onEnroll:()->Unit,onRequestReplacement:()->Unit,onGenerateQr:()->Unit,onStartAdvertising:()->Unit,onStopAdvertising:()->Unit,onAppeal:(AttendanceRecordEntity)->Unit){
    val context=LocalContext.current
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){r->if(r.values.all{it})onStartAdvertising()}
    val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val notificationMissing=BlePermissionPolicy.notificationRuntimePermissionRequired(Build.VERSION.SDK_INT) && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
    fun requestAdvertising(){
        val missing=BlePlatformReadiness.missingPermissions(context,BleOperation.ADVERTISE)
        if(missing.isEmpty())onStartAdvertising() else permissionLauncher.launch(missing.toTypedArray())
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text(stringResource(R.string.my_attendance),style=MaterialTheme.typography.headlineMedium)}
        if(student==null){item{Text(stringResource(R.string.student_profile_not_linked),color=MaterialTheme.colorScheme.error)};return@LazyColumn}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp)){Text(student.fullName,fontWeight=FontWeight.SemiBold);LtrText(student.universityNumber.orEmpty());Text(if(activeLecture==null)stringResource(R.string.no_active_lecture) else stringResource(R.string.presence_detection_for_lecture,activeLecture.id))}}}
        val activeDevices=devices.filter{it.status==DeviceStatus.ACTIVE}
        val activeDevice=activeDevices.singleOrNull()?.takeIf{it.id==student.registeredDeviceId}
        val deviceStateInvalid=activeDevice==null && (activeDevices.isNotEmpty() || student.registeredDeviceId!=null)
        item{ListItem(headlineContent={Text(stringResource(R.string.my_device))},supportingContent={when{deviceStateInvalid->Text(userMessageText("ACTIVE_DEVICE_STATE_INVALID"),color=MaterialTheme.colorScheme.error);activeDevice==null->Text(stringResource(R.string.no_active_device));else->Column{Text(localizedInternalLabel(activeDevice.status.name));LtrText("••••${activeDevice.devicePublicId.takeLast(8)}");Text(stringResource(R.string.enrolled_at,formatTime(activeDevice.registeredAt)),style=MaterialTheme.typography.bodySmall)}}},leadingContent={Icon(Icons.Default.PhoneAndroid,null)})}
        when{deviceStateInvalid->{ } ; activeDevice==null->item{Button(onClick=onEnroll,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.enroll_device))}};else->item{OutlinedButton(onClick=onRequestReplacement,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.request_device_replacement))}}}
        pairingPayload?.let{payload->item{Card{Column(Modifier.fillMaxWidth().padding(12.dp)){Text(stringResource(R.string.one_time_pairing_code),fontWeight=FontWeight.SemiBold);LtrText(payload);Text(stringResource(R.string.pairing_code_note),style=MaterialTheme.typography.bodySmall)}}}}
        requests.firstOrNull{it.status=="PENDING"}?.let{item{Text(stringResource(R.string.device_replacement_pending),style=MaterialTheme.typography.bodySmall)}}
        if(activeLecture!=null && activeDevice!=null){
            if(notificationMissing)item{Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(stringResource(R.string.notification_permission_optional_ble_note),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick={notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.request_notification_permission))}}}}
            item{if(advertising)Button(onClick=onStopAdvertising,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.stop_presence_detection))} else Button(onClick={requestAdvertising()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.start_presence_detection))}}
            item{OutlinedButton(onClick=onGenerateQr,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.dynamic_qr_fallback))}}
            qrPayload?.let{payload->item{val qr=remember(payload){QrCodec.encode(payload,420)};Card{Column(Modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){Image(qr.asImageBitmap(),contentDescription=stringResource(R.string.dynamic_qr_fallback),modifier=Modifier.sizeIn(maxWidth=280.dp,maxHeight=280.dp));Text(stringResource(R.string.qr_expires_quickly),style=MaterialTheme.typography.bodySmall)}}}}
        }
        item{Text(if(advertising)stringResource(R.string.presence_detection_active) else stringResource(R.string.presence_detection_inactive),color=if(advertising)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
        presenceError?.let{code->item{Text(stringResource(R.string.presence_detection_problem,userMessageText(code)),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
        item{Text(stringResource(R.string.attendance_history),style=MaterialTheme.typography.titleLarge)}
        if(records.isEmpty())item{Text(stringResource(R.string.no_attendance_records))}
        else items(records,key={it.id}){r->Card{Column(Modifier.fillMaxWidth().padding(12.dp)){Text("${statusLabel(r.finalStatus)} • ${"%.1f".format(r.attendancePercentage*100)}%");Text(formatTime(r.createdAt));OutlinedButton(onClick={onAppeal(r)}){Text(stringResource(R.string.submit_appeal))}}}}
    }
}

@Composable fun StudentHistoryScreen(student:StudentEntity,records:List<AttendanceRecordEntity>,onBack:()->Unit,onAppeal:(AttendanceRecordEntity)->Unit){
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)};Column{Text(student.fullName,style=MaterialTheme.typography.headlineSmall);LtrText(student.universityNumber.orEmpty())}}
        Spacer(Modifier.height(12.dp));Text(stringResource(R.string.attendance_history),style=MaterialTheme.typography.titleLarge)
        if(records.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(stringResource(R.string.no_attendance_records))}
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){items(records,key={it.id}){r->Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(formatTime(r.createdAt),fontWeight=FontWeight.SemiBold)
            Text("${statusLabel(r.finalStatus)} • ${"%.1f".format(r.attendancePercentage*100)}%")
            Text("${stringResource(R.string.late)}: ${r.lateMinutes} • ${stringResource(R.string.left_early)}: ${r.earlyLeaveMinutes}")
            OutlinedButton(onClick={onAppeal(r)}){Icon(Icons.Default.Feedback,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.submit_appeal))}
        }}}}
    }
}

@Composable fun AttendanceScreen(lecture:LectureEntity?,nextLecture:LectureEntity?,records:List<AttendanceRecordEntity>,bleState:DetectorState,canStartAction:Boolean,canEnd:Boolean,canTakeOver:Boolean,canEdit:Boolean,canApprove:Boolean,canFreeze:Boolean,onStart:()->Unit,onEnd:()->Unit,onResumeDetection:()->Unit,onTakeOver:()->Unit,onQrVerified:(String)->Unit,onEdit:(String,FinalAttendanceStatus,Double,String)->Unit,onApprove:(Boolean)->Unit){
    val context=LocalContext.current
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){results->if(results.values.all{it})onStart()}
    val resumePermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){results->if(results.values.all{it})onResumeDetection()}
    val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    val notificationMissing=BlePermissionPolicy.notificationRuntimePermissionRequired(Build.VERSION.SDK_INT) && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
    val bluetoothLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){}
    val qrCamera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->bitmap?.let{QrCodec.decode(it)}?.let(onQrVerified)}
    var qrText by rememberSaveable{mutableStateOf("")}
    var editingRecordId by rememberSaveable{mutableStateOf<String?>(null)}
    val isActive=lecture?.status==LectureStatus.ACTIVE
    val isReview=lecture?.status==LectureStatus.NEEDS_REVIEW
    val now=System.currentTimeMillis();val canStart=nextLecture!=null&&nextLecture.status in setOf(LectureStatus.READY,LectureStatus.SCHEDULED)&&nextLecture.scheduledEnd>nextLecture.scheduledStart&&now>=nextLecture.scheduledStart&&now<nextLecture.scheduledEnd
    fun startWithPermissions(){
        val missing=BlePlatformReadiness.missingPermissions(context,BleOperation.SCAN)
        if(missing.isEmpty())onStart() else permissionLauncher.launch(missing.toTypedArray())
    }
    fun resumeWithPermissions(){
        val missing=BlePlatformReadiness.missingPermissions(context,BleOperation.SCAN)
        if(missing.isEmpty())onResumeDetection() else resumePermissionLauncher.launch(missing.toTypedArray())
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(R.string.attendance),style=MaterialTheme.typography.headlineMedium)}
        if(notificationMissing)item{Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(stringResource(R.string.notification_permission_optional_ble_note),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick={notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.request_notification_permission))}}}}
        item{Card{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(when{isReview->stringResource(R.string.attendance_review_title);isActive->stringResource(R.string.active_lecture);else->stringResource(R.string.no_active_lecture)},style=MaterialTheme.typography.titleMedium)
            if(lecture!=null){LtrText(lecture.id);Text("${stringResource(R.string.current_records)}: ${records.size}")}
            if(isActive){Text(stringResource(R.string.presence_active));Text(stringResource(R.string.ble_state_format,detectorStateLabel(bleState)));if(bleState is DetectorState.Error)Text(stringResource(R.string.ble_unavailable),color=MaterialTheme.colorScheme.error)}
            if(isReview)Text(stringResource(R.string.needs_review),color=MaterialTheme.colorScheme.tertiary)
        }}}
        when{
            lecture==null&&canStart&&canStartAction->item{Button(onClick={startWithPermissions()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.start_attendance))}}
            isActive->{
                if(bleState !is DetectorState.Active)item{OutlinedButton(onClick={resumeWithPermissions()},modifier=Modifier.fillMaxWidth()){Icon(Icons.AutoMirrored.Filled.BluetoothSearching,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.resume_detection))}}
                if(bleState is DetectorState.Error && bleState.code=="BLUETOOTH_DISABLED")item{OutlinedButton(onClick={bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.enable_bluetooth))}}
                if(canTakeOver)item{OutlinedButton(onClick=onTakeOver,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.take_over_attendance))}}
                item{OutlinedButton(onClick={qrCamera.launch(null)},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.QrCodeScanner,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.scan_dynamic_qr))}}
                item{OutlinedTextField(qrText,{qrText=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.dynamic_qr_code))},textStyle=technicalTextStyle());Button(onClick={onQrVerified(qrText);qrText=""},enabled=qrText.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.verify_qr))}}
                if(canEnd)item{Button(onClick=onEnd,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.end_lecture))}}
            }
            isReview && canApprove->{
                val unresolved=records.any{it.finalStatus==FinalAttendanceStatus.MANUAL_REVIEW}
                item{Button(onClick={onApprove(false)},enabled=!unresolved,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.approve_attendance))}}
                if(canFreeze)item{OutlinedButton(onClick={onApprove(true)},enabled=!unresolved,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.approve_and_freeze))}}
            }
        }
        if(records.isNotEmpty()){
            item{Text(if(isReview)stringResource(R.string.attendance_review_title) else stringResource(R.string.live_attendance),style=MaterialTheme.typography.titleMedium)}
            items(records,key={it.id}){r->Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                LtrText(r.studentId)
                Text("${statusLabel(r.finalStatus)} • ${"%.0f".format(r.attendancePercentage*100)}% • ${stringResource(R.string.confidence)} ${"%.0f".format(r.confidenceScore*100)}%")
                Text(stringResource(R.string.presence_minutes,r.verifiedPresenceSeconds/60))
                if(!r.notes.isNullOrBlank())Text(stringResource(R.string.technical_warning,r.notes),color=MaterialTheme.colorScheme.tertiary,style=MaterialTheme.typography.bodySmall)
                if(isReview&&canEdit)OutlinedButton(onClick={editingRecordId=r.id},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.edit_attendance))}
            }}}
        }
    }
    records.firstOrNull{it.id==editingRecordId}?.let{record->AttendanceEditDialog(record,onDismiss={editingRecordId=null}){status,pct,reason->onEdit(record.id,status,pct,reason);editingRecordId=null}}
}

@Composable private fun AttendanceEditDialog(record:AttendanceRecordEntity,onDismiss:()->Unit,onConfirm:(FinalAttendanceStatus,Double,String)->Unit){
    var status by rememberSaveable(record.id){mutableStateOf(record.finalStatus.takeIf{it!=FinalAttendanceStatus.MANUAL_REVIEW}?:FinalAttendanceStatus.ABSENT)}
    var pct by rememberSaveable(record.id){mutableStateOf("${"%.1f".format(record.attendancePercentage*100)}")}
    var reason by rememberSaveable(record.id){mutableStateOf("")};var menu by rememberSaveable(record.id){mutableStateOf(false)}
    val parsed=pct.toDoubleOrNull()?.div(100.0)
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.edit_attendance))},text={Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Box{OutlinedButton(onClick={menu=true},modifier=Modifier.fillMaxWidth()){Text("${stringResource(R.string.final_status)}: ${statusLabel(status)}")};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){FinalAttendanceStatus.entries.filter{it!=FinalAttendanceStatus.MANUAL_REVIEW}.forEach{s->DropdownMenuItem(text={Text(statusLabel(s))},onClick={status=s;menu=false})}}}
        OutlinedTextField(pct,{pct=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.attendance_percentage))},singleLine=true)
        OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))},minLines=2)
    }},confirmButton={TextButton(enabled=reason.isNotBlank()&&parsed!=null&&parsed in 0.0..1.0,onClick={parsed?.let{onConfirm(status,it,reason)}}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable fun ReportsScreen(history:List<ReportJobEntity>,preview:GeneratedReportEntity?,onGenerate:()->Unit,onApprove:(String)->Unit,onRetry:(String)->Unit,onPreview:(String)->Unit,onClosePreview:()->Unit,onCancel:(String)->Unit,onRegenerate:(String)->Unit){
    val context=LocalContext.current
    fun openGenerated(row:GeneratedReportEntity,share:Boolean){
        try{
            val file=java.io.File(row.filePath)
            if(!file.isFile)throw java.io.FileNotFoundException("REPORT_FILE_NOT_FOUND")
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            val mime=when(row.format.uppercase()){ "PDF"->"application/pdf";"CSV"->"text/csv";else->"text/plain" }
            val action=if(share)Intent.ACTION_SEND else Intent.ACTION_VIEW
            val i=Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if(share)i.setType(mime).putExtra(Intent.EXTRA_STREAM,uri) else i.setDataAndType(uri,mime)
            context.startActivity(if(share)Intent.createChooser(i,context.getString(R.string.share_report)) else i)
        }catch(e:android.content.ActivityNotFoundException){
            android.widget.Toast.makeText(context,context.getString(R.string.message_report_no_app),android.widget.Toast.LENGTH_LONG).show()
        }catch(e:SecurityException){
            android.util.Log.e("ReportsScreen","REPORT_FILE_ACCESS_DENIED",e);android.widget.Toast.makeText(context,context.getString(R.string.message_report_access_denied),android.widget.Toast.LENGTH_LONG).show()
        }catch(e:IllegalArgumentException){
            android.util.Log.e("ReportsScreen","REPORT_FILE_URI_INVALID",e);android.widget.Toast.makeText(context,context.getString(R.string.message_report_uri_invalid),android.widget.Toast.LENGTH_LONG).show()
        }catch(e:java.io.FileNotFoundException){
            android.widget.Toast.makeText(context,context.getString(R.string.message_report_file_missing),android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(R.string.reports),style=MaterialTheme.typography.headlineMedium)}
        item{Button(onClick=onGenerate,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.PictureAsPdf,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.send_report_now))}}
        item{Text(stringResource(R.string.report_history),style=MaterialTheme.typography.titleMedium)}
        if(history.isEmpty())item{Text(stringResource(R.string.no_reports))} else items(history,key={it.id}){j->Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(localizedInternalLabel(j.reportType),fontWeight=FontWeight.SemiBold);Text("${localizedInternalLabel(j.status.name)} • ${formatTime(j.scheduledAt)}")
            j.errorMessage?.let{Text(userMessageText(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(j.generatedAt!=null)OutlinedButton(onClick={onPreview(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.preview))}
            if(j.status==ReportJobStatus.PENDING_APPROVAL){Button(onClick={onApprove(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.approve_and_send))};OutlinedButton(onClick={onRegenerate(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.regenerate))};TextButton(onClick={onCancel(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.cancel))}}
            val stale=j.errorMessage?.contains("REPORT_STALE_AFTER_ATTENDANCE_CHANGE")==true
            if(stale && j.status in setOf(ReportJobStatus.FAILED,ReportJobStatus.SENT))OutlinedButton(onClick={onRegenerate(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.regenerate))}
            else if(j.status==ReportJobStatus.FAILED)OutlinedButton(onClick={onRetry(j.id)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.retry))}
        }}}
    }
    preview?.let{r->
        val unavailable=stringResource(R.string.preview_unavailable)
        var summaryText by remember(r.filePath){mutableStateOf<String?>(null)}
        LaunchedEffect(r.filePath,r.format){summaryText=if(r.format.equals("SUMMARY",true))withContext(Dispatchers.IO){runCatching{java.io.File(r.filePath).readText().take(3000)}.getOrDefault(unavailable)} else null}
        AlertDialog(onDismissRequest=onClosePreview,title={Text(stringResource(R.string.report_preview))},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("${localizedInternalLabel(r.format)} • ${r.sizeBytes} B");LtrText(r.hash);if(r.format.equals("SUMMARY",true))Text(summaryText?:"") }},confirmButton={TextButton(onClick={openGenerated(r,false)}){Text(stringResource(R.string.open))}},dismissButton={Row{TextButton(onClick={openGenerated(r,true)}){Text(stringResource(R.string.share_report))};TextButton(onClick=onClosePreview){Text(stringResource(R.string.close))}}})
    }
}

@Composable fun AttendanceAppealFormScreen(state:AppealUiState,onBack:()->Unit,onSubmit:(String,String,String?)->Unit){
    val context=LocalContext.current
    var reason by rememberSaveable{mutableStateOf("OTHER")}; var description by rememberSaveable{mutableStateOf("")}
    var attachmentUri by rememberSaveable{mutableStateOf<String?>(null)}; var attachmentName by rememberSaveable{mutableStateOf<String?>(null)}; var attachmentError by rememberSaveable{mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            when(val result=AttachmentValidator(context.contentResolver).validate(uri)){
                is AttachmentValidationResult.Valid->{attachmentUri=uri.toString();attachmentName=result.info.displayName;attachmentError=null}
                is AttachmentValidationResult.Invalid->{attachmentUri=null;attachmentName=null;attachmentError=result.reason}
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)};Text(stringResource(R.string.attendance_appeal),style=MaterialTheme.typography.headlineSmall)}
        if(state.loading){LinearProgressIndicator(Modifier.fillMaxWidth())}
        val c=state.context
        if(c==null){Text(state.message?.let{userMessageText(it)} ?: stringResource(R.string.loading))}
        else{
            ReadOnlyValue(stringResource(R.string.student_id),c.student.id,true)
            ReadOnlyValue(stringResource(R.string.student),c.student.fullName)
            ReadOnlyValue(stringResource(R.string.university_number),c.student.universityNumber ?: "—",true)
            ReadOnlyValue(stringResource(R.string.lecture_id),c.lecture.id,true)
            ReadOnlyValue(stringResource(R.string.subject),c.subject.name)
            ReadOnlyValue(stringResource(R.string.teacher),c.teacher?.fullName ?: "—")
            ReadOnlyValue(stringResource(R.string.lecture_date),formatTime(c.lecture.scheduledStart))
            ReadOnlyValue(stringResource(R.string.current_attendance_status),statusLabel(c.attendanceRecord.finalStatus))
            ReadOnlyValue(stringResource(R.string.current_attendance_percentage),"${"%.1f".format(c.attendanceRecord.attendancePercentage*100)}%")
            ChoiceDropdown(stringResource(R.string.appeal_reason_type),reason,listOf("OTHER")){reason=it}
            OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.appeal_description))},minLines=3)
            OutlinedButton(onClick={launcher.launch(arrayOf("image/*","application/pdf"))}){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.add_attachment))}
            attachmentName?.let{Text(it)}; attachmentError?.let{Text(stringResource(R.string.attachment_invalid)+": "+userMessageText(it),color=MaterialTheme.colorScheme.error)}
            Button(onClick={onSubmit(reason,description,attachmentUri)},enabled=description.isNotBlank()&&!state.loading,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.submit_appeal))}
            if(state.message=="APPEAL_SAVED_OFFLINE") Text(stringResource(R.string.appeal_saved_offline),color=MaterialTheme.colorScheme.primary)
            else state.message?.let{Text(userMessageText(it),color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable private fun ReadOnlyValue(label:String,value:String,ltr:Boolean=false){OutlinedTextField(value,{},Modifier.fillMaxWidth(),readOnly=true,label={Text(label)},textStyle=if(ltr)LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr) else LocalTextStyle.current)}

@Composable fun AppealsReviewScreen(rows:List<AppealReviewRow>,filter:AppealStatus?,onFilter:(AppealStatus?)->Unit,onBack:()->Unit,onReview:(String,Boolean,String,FinalAttendanceStatus?,Double?)->Unit){
    var reviewingId by rememberSaveable{mutableStateOf<String?>(null)}; var accepting by rememberSaveable{mutableStateOf(true)}
    val reviewing=rows.firstOrNull{it.appealId==reviewingId}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)};Text(stringResource(R.string.attendance_appeals),style=MaterialTheme.typography.headlineSmall)}
        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(listOf(AppealStatus.PENDING,AppealStatus.ACCEPTED,AppealStatus.REJECTED)){status->FilterChip(selected=filter==status,onClick={onFilter(status)},label={Text(appealStatusLabel(status))})}}
        if(rows.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(stringResource(R.string.no_appeals))}
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){items(rows,key={it.appealId}){row->Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(row.studentName,fontWeight=FontWeight.SemiBold);LtrText(row.universityNumber.orEmpty());Text("${row.subjectName} • ${formatTime(row.lectureDate)}")
            Text("${stringResource(R.string.current_attendance_status)}: ${statusLabel(row.currentAttendanceStatus)} • ${"%.1f".format(row.currentAttendancePercentage*100)}%")
            Text("${stringResource(R.string.appeal_reason_type)}: ${localizedInternalLabel(row.reasonType)}");Text(row.description)
            row.attachmentLocalUri?.let{Text("${stringResource(R.string.attachment)}: $it",style=MaterialTheme.typography.bodySmall)}
            Text("${stringResource(R.string.submitted_at)}: ${formatTime(row.submittedAt)}")
            if(row.status==AppealStatus.PENDING)Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick={reviewingId=row.appealId;accepting=true},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.accept))};OutlinedButton(onClick={reviewingId=row.appealId;accepting=false},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.reject))}}
            else row.decisionNote?.let{Text("${stringResource(R.string.decision_note)}: $it")}
        }}}}
    }
    reviewing?.let{row->ReviewDialog(row,accepting,onDismiss={reviewingId=null}){note,status,pct->onReview(row.appealId,accepting,note,status,pct);reviewingId=null}}
}

@Composable private fun ReviewDialog(row:AppealReviewRow,accept:Boolean,onDismiss:()->Unit,onConfirm:(String,FinalAttendanceStatus?,Double?)->Unit){
    var note by rememberSaveable{mutableStateOf("")};var status by rememberSaveable{mutableStateOf(row.currentAttendanceStatus)};var pct by rememberSaveable{mutableStateOf("${"%.1f".format(row.currentAttendancePercentage*100)}")};var menu by rememberSaveable{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(accept)stringResource(R.string.accept_appeal) else stringResource(R.string.reject_appeal))},text={Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(note,{note=it},label={Text(stringResource(R.string.decision_note))},minLines=2)
        if(accept){Box{OutlinedButton(onClick={menu=true}){Text(statusLabel(status));Icon(Icons.Default.ArrowDropDown,null)};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){FinalAttendanceStatus.entries.forEach{s->DropdownMenuItem(text={Text(statusLabel(s))},onClick={status=s;menu=false})}}};OutlinedTextField(pct,{pct=it},label={Text(stringResource(R.string.current_attendance_percentage))},singleLine=true)}
    }},confirmButton={TextButton(enabled=note.isNotBlank(),onClick={val p=if(accept)pct.toDoubleOrNull()?.div(100.0)?.coerceIn(0.0,1.0) else null;onConfirm(note,if(accept)status else null,p)}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}


@Composable fun AppLockScreen(biometricEnabled:Boolean,onPin:(String)->Unit,onBiometric:()->Unit){
    var pin by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Default.Lock,null,modifier=Modifier.size(48.dp));Spacer(Modifier.height(12.dp));Text(stringResource(R.string.app_lock),style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp));OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},label={Text(stringResource(R.string.pin))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
        Spacer(Modifier.height(8.dp));Button(onClick={onPin(pin)},enabled=pin.length>=4,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.unlock))}
        if(biometricEnabled)OutlinedButton(onClick=onBiometric,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.biometric_unlock))}
    }
}

@Composable fun SettingsScreen(canReviewAppeals:Boolean,canManageSettings:Boolean,canOpenManagement:Boolean,canUseAppLock:Boolean,canViewSystemHealth:Boolean,appLockConfigured:Boolean,biometricEnabled:Boolean,language:String,theme:String,academicWeekStart:String,canClaimOwnerSetup:Boolean,backendBaseUrl:String?,syncWorkspaceId:String?,backendAuthMasked:String?,backendAccountId:String?,backendAuthState:String,backendSessionExpiresAt:Long?,flags:List<FeatureFlagEntity>,systemState:DashboardState,onBackendUrl:(String)->Unit,onSyncWorkspace:(String)->Unit,onBackendAuth:(String)->Unit,onDeleteBackendAuth:()->Unit,onProvisionBackendAccount:()->Unit,onRenewBackendSession:()->Unit,onLogoutBackendSession:()->Unit,onFlag:(String,Boolean)->Unit,onSetPin:(String)->Unit,onDisableLock:()->Unit,onBiometric:(Boolean)->Unit,onLanguage:(String)->Unit,onTheme:(String)->Unit,onAcademicWeekStart:(String)->Unit,onClaimOwnerSetup:()->Unit,onDiagnosticsMessage:(String)->Unit,onOpenAppeals:()->Unit,onOpenManagement:()->Unit){
    val context=LocalContext.current
    var backend by rememberSaveable(backendBaseUrl){mutableStateOf(backendBaseUrl.orEmpty())}
    var syncWorkspace by rememberSaveable(syncWorkspaceId){mutableStateOf(syncWorkspaceId.orEmpty())}
    var backendToken by rememberSaveable{mutableStateOf("")}
    var newPin by rememberSaveable{mutableStateOf("")}
    var confirmOwnerSetup by rememberSaveable{mutableStateOf(false)}
    var confirmDisableLock by rememberSaveable{mutableStateOf(false)}
    var confirmBackendLogout by rememberSaveable{mutableStateOf(false)}
    val ioScope=rememberCoroutineScope()
    val biometricAvailable=remember(context){BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)==BiometricManager.BIOMETRIC_SUCCESS}
    val diagnosticsLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)ioScope.launch{
            val code=withContext(Dispatchers.IO){runCatching{
                FileIoSafety.requireOpened(context.contentResolver.openOutputStream(uri,"w"),"FILE_OUTPUT_STREAM_UNAVAILABLE").bufferedWriter(Charsets.UTF_8).use{it.write(diagnosticsText(readDeviceSnapshot(context,systemState)))}
                "DIAGNOSTICS_EXPORTED"
            }.getOrElse{LocalAccessRules.safeErrorCode(it.message,FileIoSafety.decision(FileIoSafety.fromExceptionClass(it.javaClass.simpleName)).messageCode)}}
            onDiagnosticsMessage(code)
        }
    }
    val shareDiagnosticsFallback={
        ioScope.launch{
            val result=withContext(Dispatchers.IO){runCatching{
                val dir=File(context.filesDir,"exports").apply{mkdirs()};dir.listFiles()?.filter{it.name.startsWith("hammam-attendai-diagnostics-")}?.forEach{it.delete()}
                val file=File(dir,"hammam-attendai-diagnostics-${System.currentTimeMillis()}.txt")
                file.bufferedWriter(Charsets.UTF_8).use{it.write(diagnosticsText(readDeviceSnapshot(context,systemState)))}
                FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            }}
            result.onSuccess{uri->runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},context.getString(R.string.diagnostics_export)))}.onSuccess{onDiagnosticsMessage("DIAGNOSTICS_READY_TO_SHARE")}.onFailure{onDiagnosticsMessage("FILE_PICKER_PLATFORM_FAILURE")}}
                .onFailure{onDiagnosticsMessage(LocalAccessRules.safeErrorCode(it.message,FileIoSafety.decision(FileIoSafety.fromExceptionClass(it.javaClass.simpleName)).messageCode))}
        }
    }
    val requestDiagnosticsExport={
        try{diagnosticsLauncher.launch("hammam-attendai-diagnostics.txt")}
        catch(e:Exception){val decision=FileIoSafety.decision(FileIoSafety.fromExceptionClass(e.javaClass.simpleName));if(decision.tryFallback)shareDiagnosticsFallback() else onDiagnosticsMessage(decision.messageCode)}
    }
    if(confirmOwnerSetup)AlertDialog(onDismissRequest={confirmOwnerSetup=false},title={Text(stringResource(R.string.owner_upgrade_setup))},text={Text(stringResource(R.string.owner_upgrade_confirmation))},confirmButton={TextButton(onClick={confirmOwnerSetup=false;onClaimOwnerSetup()}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick={confirmOwnerSetup=false}){Text(stringResource(R.string.cancel))}})
    if(confirmDisableLock)AlertDialog(onDismissRequest={confirmDisableLock=false},title={Text(stringResource(R.string.app_lock))},text={Text(stringResource(R.string.disable_app_lock_confirmation))},confirmButton={TextButton(onClick={confirmDisableLock=false;onDisableLock()}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick={confirmDisableLock=false}){Text(stringResource(R.string.cancel))}})
    if(confirmBackendLogout)AlertDialog(onDismissRequest={confirmBackendLogout=false},title={Text(stringResource(R.string.backend_account_state))},text={Text(stringResource(R.string.backend_logout_confirmation))},confirmButton={TextButton(onClick={confirmBackendLogout=false;onLogoutBackendSession()}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick={confirmBackendLogout=false}){Text(stringResource(R.string.cancel))}})
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        item{Text(stringResource(R.string.settings),style=MaterialTheme.typography.headlineMedium)}
        item{Text(stringResource(R.string.language),style=MaterialTheme.typography.titleMedium)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=language=="AR",onClick={onLanguage("AR")},label={Text(stringResource(R.string.language_arabic))});FilterChip(selected=language=="EN",onClick={onLanguage("EN")},label={Text(stringResource(R.string.language_english))})}}
        item{Text(stringResource(R.string.theme),style=MaterialTheme.typography.titleMedium)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("SYSTEM" to R.string.theme_system,"LIGHT" to R.string.theme_light,"DARK" to R.string.theme_dark).forEach{(v,label)->FilterChip(selected=theme==v,onClick={onTheme(v)},label={Text(stringResource(label))})}}}
        if(canManageSettings)item{ChoiceDropdown(stringResource(R.string.academic_week_start),academicWeekStart,java.time.DayOfWeek.values().toList().map{it.name}){onAcademicWeekStart(it)}}
        if(canClaimOwnerSetup)item{ListItem(headlineContent={Text(stringResource(R.string.owner_upgrade_setup))},supportingContent={Text(stringResource(R.string.owner_upgrade_setup_note))},leadingContent={Icon(Icons.Default.Security,null)},trailingContent={Button(onClick={confirmOwnerSetup=true}){Text(stringResource(R.string.confirm))}})}
        if(canReviewAppeals)item{ListItem(headlineContent={Text(stringResource(R.string.attendance_appeals))},supportingContent={Text(stringResource(R.string.appeal_review_desc))},leadingContent={Icon(Icons.AutoMirrored.Filled.Rule,null)},trailingContent={Icon(Icons.AutoMirrored.Filled.ArrowForward,null)},modifier=Modifier.clickable(onClick=onOpenAppeals))}
        if(canOpenManagement)item{ListItem(headlineContent={Text(stringResource(R.string.management_tools))},supportingContent={Text(stringResource(R.string.management_tools_desc))},leadingContent={Icon(Icons.Default.AdminPanelSettings,null)},trailingContent={Icon(Icons.AutoMirrored.Filled.ArrowForward,null)},modifier=Modifier.clickable(onClick=onOpenManagement))}
        if(canUseAppLock){
            item{Text(stringResource(R.string.app_lock),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(newPin,{newPin=it.filter(Char::isDigit).take(12)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.pin))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))}
            item{Button(onClick={onSetPin(newPin);newPin=""},enabled=newPin.length in 4..12,modifier=Modifier.fillMaxWidth()){Text(if(appLockConfigured)stringResource(R.string.replace_pin) else stringResource(R.string.enable_pin))}}
            if(appLockConfigured)item{ListItem(headlineContent={Text(stringResource(R.string.biometric_unlock))},supportingContent={if(!biometricAvailable)Text(stringResource(R.string.biometric_unavailable))},trailingContent={Switch(checked=biometricEnabled,onCheckedChange=onBiometric,enabled=biometricAvailable)})}
            if(appLockConfigured)item{TextButton(onClick={confirmDisableLock=true},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.disable_app_lock))}}
        }
        if(canManageSettings){
            item{Text(stringResource(R.string.backend_settings),style=MaterialTheme.typography.titleMedium)}
            item{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){OutlinedTextField(backend,{backend=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.backend_base_url))},supportingText={Text(stringResource(R.string.backend_url_note))})}}
            item{Button(onClick={onBackendUrl(backend)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save))}}
            item{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){OutlinedTextField(syncWorkspace,{syncWorkspace=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.sync_workspace_id))},supportingText={Text(stringResource(R.string.sync_workspace_note))},singleLine=true)}}
            item{Button(onClick={onSyncWorkspace(syncWorkspace)},enabled=syncWorkspace.isBlank()||SyncIntegrityRules.validWorkspaceId(syncWorkspace),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save_sync_workspace))}}
            item{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){OutlinedTextField(backendToken,{backendToken=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.backend_auth_token))},supportingText={Text(backendAuthMasked?.let{stringResource(R.string.backend_auth_configured,it)}?:stringResource(R.string.backend_auth_note))},singleLine=true,visualTransformation=PasswordVisualTransformation())}}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={onBackendAuth(backendToken);backendToken=""},enabled=backendToken.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.add_replace_key))};if(backendAuthMasked!=null)OutlinedButton(onClick=onDeleteBackendAuth,modifier=Modifier.weight(1f)){Text(stringResource(R.string.delete_key))}}}
            item{ListItem(headlineContent={Text(stringResource(R.string.backend_account_state))},supportingContent={Text(localizedInternalLabel(backendAuthState))},leadingContent={Icon(Icons.Default.CloudDone,null)})}
            if(backendAccountId!=null)item{ListItem(headlineContent={Text(stringResource(R.string.backend_account_id))},supportingContent={LtrText(backendAccountId)},leadingContent={Icon(Icons.Default.Badge,null)})}
            if(backendSessionExpiresAt!=null)item{ListItem(headlineContent={Text(stringResource(R.string.backend_session_expiry))},supportingContent={LtrText(backendSessionExpiresAt.toString())},leadingContent={Icon(Icons.Default.Schedule,null)})}
            if(backendAccountId==null||backendAuthState=="PROVISIONING_REQUIRED")item{Button(onClick=onProvisionBackendAccount,enabled=backendAuthMasked!=null&&backend.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.provision_backend_account))}}
            if(backendAccountId!=null&&backendAuthState!="PROVISIONING_REQUIRED")item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onRenewBackendSession,modifier=Modifier.weight(1f)){Text(stringResource(R.string.renew_backend_session))};OutlinedButton(onClick={confirmBackendLogout=true},modifier=Modifier.weight(1f)){Text(stringResource(R.string.logout_backend_session))}}}
            item{Text(stringResource(R.string.feature_flags),style=MaterialTheme.typography.titleMedium)}
            items(flags,key={it.code}){flag->ListItem(headlineContent={Text(localizedInternalLabel(flag.code))},trailingContent={Switch(checked=flag.enabled,onCheckedChange={onFlag(flag.code,it)})})}
        }
        item{ListItem(headlineContent={Text(stringResource(R.string.backup))},supportingContent={Text(stringResource(R.string.backup_local_note))},leadingContent={Icon(Icons.Default.Backup,null)})}
        item{ListItem(headlineContent={Text(stringResource(R.string.privacy))},supportingContent={Text(stringResource(R.string.presence_active))},leadingContent={Icon(Icons.Default.PrivacyTip,null)})}
        item{ListItem(headlineContent={Text(stringResource(R.string.about))},supportingContent={Text(stringResource(R.string.app_description))},leadingContent={Icon(Icons.Default.Info,null)})}
        if(canViewSystemHealth)item{ListItem(headlineContent={Text(stringResource(R.string.diagnostics_export))},supportingContent={Text(stringResource(R.string.diagnostics_export_note))},leadingContent={Icon(Icons.Default.Terminal,null)},modifier=Modifier.clickable{requestDiagnosticsExport()})}
    }
}

enum class AdminOpsSection{USERS,ACADEMIC,TEACHERS,SUBJECTS,TIMETABLE,REPORTS,AI,DATA}

@Composable fun AdminOperationsScreen(
    permissions:Set<String>,universities:List<UniversityEntity>,faculties:List<FacultyEntity>,departments:List<DepartmentEntity>,years:List<AcademicYearEntity>,semesters:List<SemesterEntity>,levels:List<LevelEntity>,batches:List<BatchEntity>,sections:List<SectionEntity>,groups:List<GroupEntity>,teachers:List<TeacherEntity>,subjects:List<SubjectEntity>,attendancePolicies:List<AttendancePolicyEntity>,versions:List<WeeklyTimetableVersionEntity>,review:com.hammam.attendai.data.repository.TimetableRepository.DraftReview?,onBack:()->Unit,adminVm:AdminOperationsViewModel,initialSection:AdminOpsSection=AdminOpsSection.ACADEMIC
){
    var section by rememberSaveable(initialSection){mutableStateOf(initialSection)}
    val scopedReportTeachers by adminVm.reportTeachers.collectAsState()
    val scopedReportSubjects by adminVm.reportSubjects.collectAsState()
    val available=LocalAccessRules.allowedManagementSections(permissions).map{AdminOpsSection.valueOf(it.name)}
    val effectiveSection=section.takeIf{it in available} ?: available.firstOrNull()
    LaunchedEffect(permissions,effectiveSection){if(effectiveSection!=null&&section!=effectiveSection)section=effectiveSection}
    if(available.isEmpty()){
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)};Text(stringResource(R.string.management_tools),style=MaterialTheme.typography.titleLarge)}
            Text(stringResource(R.string.no_authorized_management_actions),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.no_authorized_management_actions_note),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val safeSection=effectiveSection ?: return
    Column(Modifier.fillMaxSize().safeDrawingPadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)};Text(stringResource(R.string.management_tools),style=MaterialTheme.typography.titleLarge)}
        ScrollableTabRow(selectedTabIndex=available.indexOf(safeSection)){available.forEach{v->Tab(selected=safeSection==v,onClick={section=v},text={Text(when(v){AdminOpsSection.USERS->stringResource(R.string.users_access);AdminOpsSection.ACADEMIC->stringResource(R.string.academic_structure);AdminOpsSection.TEACHERS->stringResource(R.string.teachers);AdminOpsSection.SUBJECTS->stringResource(R.string.subjects);AdminOpsSection.TIMETABLE->stringResource(R.string.timetable);AdminOpsSection.REPORTS->stringResource(R.string.report_settings);AdminOpsSection.AI->stringResource(R.string.ai_providers);AdminOpsSection.DATA->stringResource(R.string.local_data_tools)})})}}
        when(safeSection){
            AdminOpsSection.USERS->UserAccessPane(permissions,adminVm)
            AdminOpsSection.ACADEMIC->AcademicStructurePane(permissions,universities,faculties,departments,years,semesters,levels,batches,sections,groups,adminVm)
            AdminOpsSection.TEACHERS->TeachersManagementPane(permissions,teachers,adminVm)
            AdminOpsSection.SUBJECTS->SubjectsManagementPane(permissions,subjects,teachers,levels,semesters,groups,attendancePolicies,adminVm)
            AdminOpsSection.TIMETABLE->TimetableManagementPane(permissions,groups,teachers,subjects,versions,review,adminVm)
            AdminOpsSection.REPORTS->ReportSettingsPane(permissions,scopedReportTeachers,scopedReportSubjects,adminVm)
            AdminOpsSection.AI->AiProvidersPane(permissions,adminVm)
            AdminOpsSection.DATA->LocalDataToolsPane(permissions,adminVm)
        }
    }
}

@Composable private fun UserAccessPane(permissions:Set<String>,vm:AdminOperationsViewModel){
    val users by vm.accessUsers.collectAsState();val roles by vm.accessRoles.collectAsState();val defs by vm.permissionDefinitions.collectAsState()
    val authorizedAdminReason=stringResource(R.string.authorized_admin_reason)
    var name by rememberSaveable{mutableStateOf("")};var role by rememberSaveable{mutableStateOf("Representative")};var scopeType by rememberSaveable{mutableStateOf("GROUP")};var scopeId by rememberSaveable{mutableStateOf("")};var reason by rememberSaveable{mutableStateOf(authorizedAdminReason)}
    var target by rememberSaveable{mutableStateOf("")};var permission by rememberSaveable{mutableStateOf("")};var hours by rememberSaveable{mutableStateOf("24")}
    val assignableRoles=roles.map{it.name}.filter{it!="SYSTEM_OWNER"}
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if("MANAGE_USERS" in permissions){
            item{Text(stringResource(R.string.users_access),style=MaterialTheme.typography.titleLarge)}
            item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.name))})}
            item{ChoiceDropdown(stringResource(R.string.role),role,assignableRoles.ifEmpty{listOf("Representative")}){role=it}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){ChoiceDropdown(stringResource(R.string.scope),scopeType,listOf("GROUP","SECTION","BATCH","LEVEL","TEACHER","STUDENT")){scopeType=it}};OutlinedTextField(scopeId,{scopeId=it},Modifier.weight(1f),label={Text(stringResource(R.string.scope_id))},textStyle=technicalTextStyle())}}
            item{Button(onClick={vm.createManagedUser(name,role,scopeType,scopeId)},enabled=name.isNotBlank()&&role.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.create_user))}}
        }
        items(users,key={it.id}){u->Card{Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(u.displayName,fontWeight=FontWeight.SemiBold);Text("${u.roleName?.let{localizedInternalLabel(it)} ?: "—"} • ${if(u.isActive)stringResource(R.string.enabled) else stringResource(R.string.disabled)}");if(u.roleName!="SYSTEM_OWNER"&&"MANAGE_USERS" in permissions)TextButton(onClick={vm.setUserActive(u.id,!u.isActive,reason)}){Text(if(u.isActive)stringResource(R.string.disable_user) else stringResource(R.string.enable_user))};if(u.roleName!="SYSTEM_OWNER"&&"MANAGE_ROLES" in permissions)ChoiceDropdown(stringResource(R.string.role),u.roleName.orEmpty(),assignableRoles){vm.replaceUserRole(u.id,it,reason)}}}}
        if("MANAGE_PERMISSIONS" in permissions||"MANAGE_USERS" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.scoped_permissions),style=MaterialTheme.typography.titleMedium)}
            item{EntityChoice(stringResource(R.string.user),target,users.filter{it.roleName!="SYSTEM_OWNER"}.map{it.id to it.displayName}){target=it}}
            item{OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))})}
            if("MANAGE_USERS" in permissions)item{Button(onClick={vm.grantUserScope(target,scopeType,scopeId,reason)},enabled=target.isNotBlank()&&scopeType.isNotBlank()&&reason.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.grant_scope))}}
            if("MANAGE_PERMISSIONS" in permissions){item{ChoiceDropdown(stringResource(R.string.permission),permission,defs.map{it.code}){permission=it}};item{OutlinedTextField(hours,{hours=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.expiry_hours))})};item{Button(onClick={vm.grantTemporaryPermission(target,permission,scopeType,scopeId,hours.toIntOrNull(),reason)},enabled=target.isNotBlank()&&permission.isNotBlank()&&reason.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.grant_temporary_permission))}}}
        }
    }
}

@Composable private fun AcademicStructurePane(permissions:Set<String>,universities:List<UniversityEntity>,faculties:List<FacultyEntity>,departments:List<DepartmentEntity>,years:List<AcademicYearEntity>,semesters:List<SemesterEntity>,levels:List<LevelEntity>,batches:List<BatchEntity>,sections:List<SectionEntity>,groups:List<GroupEntity>,vm:AdminOperationsViewModel){
    var kind by rememberSaveable{mutableStateOf("UNIVERSITY")};var name by rememberSaveable{mutableStateOf("")};var parent by rememberSaveable{mutableStateOf("")};var start by rememberSaveable{mutableStateOf("")};var end by rememberSaveable{mutableStateOf("")};var order by rememberSaveable{mutableStateOf("1")}
    var oldSemester by rememberSaveable{mutableStateOf("")};var newName by rememberSaveable{mutableStateOf("")};var newYear by rememberSaveable{mutableStateOf("")};var newStart by rememberSaveable{mutableStateOf("")};var newEnd by rememberSaveable{mutableStateOf("")};var copySubjects by rememberSaveable{mutableStateOf(true)};var copyTable by rememberSaveable{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text("${universities.size} • ${faculties.size} • ${departments.size} • ${years.size} • ${semesters.size} • ${levels.size} • ${batches.size} • ${sections.size} • ${groups.size}",style=MaterialTheme.typography.bodySmall)}
        if("MANAGE_ACADEMIC_STRUCTURE" in permissions){
            item{ChoiceDropdown(stringResource(R.string.entity_type),kind,listOf("UNIVERSITY","FACULTY","DEPARTMENT","ACADEMIC_YEAR","SEMESTER","LEVEL","BATCH","SECTION","GROUP")){kind=it;parent=""}}
            item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.name))})}
            if(kind!="UNIVERSITY"&&kind!="ACADEMIC_YEAR")item{val opts=when(kind){"FACULTY"->universities.map{it.id to it.name};"DEPARTMENT"->faculties.map{it.id to it.name};"SEMESTER"->years.map{it.id to it.name};"LEVEL"->departments.map{it.id to it.name};"BATCH"->levels.map{it.id to it.name};"SECTION"->batches.map{it.id to it.name};"GROUP"->sections.map{it.id to it.name};else->emptyList()};EntityChoice(stringResource(R.string.parent),parent,opts){parent=it}}
            if(kind=="ACADEMIC_YEAR"||kind=="SEMESTER"){item{OutlinedTextField(start,{start=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.date_format_ymd))})};item{OutlinedTextField(end,{end=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.date_format_ymd))})}}
            if(kind=="LEVEL")item{OutlinedTextField(order,{order=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.order_index))})}
            if(kind=="BATCH")item{EntityChoice(stringResource(R.string.academic_year),start,years.map{it.id to it.name}){start=it}}
            item{Button(onClick={when(kind){"UNIVERSITY"->vm.addUniversity(name);"FACULTY"->vm.addFaculty(parent,name);"DEPARTMENT"->vm.addDepartment(parent,name);"ACADEMIC_YEAR"->vm.addAcademicYear(name,start,end);"SEMESTER"->vm.addSemester(name,parent,start,end);"LEVEL"->vm.addLevel(parent,name,order.toIntOrNull()?:1);"BATCH"->vm.addBatch(parent,name,start);"SECTION"->vm.addSection(parent,name);"GROUP"->vm.addGroup(parent,name)}},enabled=name.isNotBlank()&&(kind in setOf("UNIVERSITY","ACADEMIC_YEAR")||parent.isNotBlank()),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add))}}
            item{HorizontalDivider();Text(stringResource(R.string.semester_rollover),style=MaterialTheme.typography.titleMedium)}
            item{EntityChoice(stringResource(R.string.current_semester),oldSemester,semesters.map{it.id to it.name}){oldSemester=it}}
            item{OutlinedTextField(newName,{newName=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.new_semester_name))})}
            item{EntityChoice(stringResource(R.string.academic_year),newYear,years.map{it.id to it.name}){newYear=it}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(newStart,{newStart=it},Modifier.weight(1f),label={Text(stringResource(R.string.start_date))});OutlinedTextField(newEnd,{newEnd=it},Modifier.weight(1f),label={Text(stringResource(R.string.end_date))})}}
            item{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(copySubjects,{copySubjects=it});Text(stringResource(R.string.copy_subjects));Spacer(Modifier.width(8.dp));Checkbox(copyTable,{copyTable=it});Text(stringResource(R.string.copy_timetable_template))}}
            item{Button(onClick={vm.rollover(oldSemester,newName,newYear,newStart,newEnd,copySubjects,copyTable)},enabled=oldSemester.isNotBlank()&&newName.isNotBlank()&&newYear.isNotBlank()&&newStart.isNotBlank()&&newEnd.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.close_and_start_semester))}}
        }
    }
}

@Composable private fun TeachersManagementPane(permissions:Set<String>,teachers:List<TeacherEntity>,vm:AdminOperationsViewModel){
    val administrativeArchiveReason=stringResource(R.string.administrative_archive_reason)
    var name by rememberSaveable{mutableStateOf("")};var phone by rememberSaveable{mutableStateOf("")};var wa by rememberSaveable{mutableStateOf("")};var email by rememberSaveable{mutableStateOf("")};var reason by rememberSaveable{mutableStateOf(administrativeArchiveReason)}
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if("MANAGE_TEACHERS" in permissions){item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.teacher_name))})};item{OutlinedTextField(phone,{phone=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.phone))},textStyle=technicalTextStyle())};item{OutlinedTextField(wa,{wa=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.whatsapp_label))},textStyle=technicalTextStyle())};item{OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.email_label))},textStyle=technicalTextStyle())};item{Button(onClick={vm.addTeacher(name,phone,wa,email)},enabled=name.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_teacher))}};item{OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))})}}
        items(teachers,key={it.id}){t->ListItem(headlineContent={Text(t.fullName)},supportingContent={LtrText(listOfNotNull(t.phone,t.email).joinToString(" • "))},trailingContent={if("MANAGE_TEACHERS" in permissions)TextButton(onClick={vm.archiveTeacher(t.id,reason)},enabled=reason.isNotBlank()){Text(stringResource(R.string.archive))}})}
    }
}

@Composable private fun SubjectsManagementPane(permissions:Set<String>,subjects:List<SubjectEntity>,teachers:List<TeacherEntity>,levels:List<LevelEntity>,semesters:List<SemesterEntity>,groups:List<GroupEntity>,policies:List<AttendancePolicyEntity>,vm:AdminOperationsViewModel){
    val administrativeArchiveReason=stringResource(R.string.administrative_archive_reason)
    var code by rememberSaveable{mutableStateOf("")};var name by rememberSaveable{mutableStateOf("")};var teacher by rememberSaveable{mutableStateOf("")};var level by rememberSaveable{mutableStateOf("")};var semester by rememberSaveable{mutableStateOf("")};var group by rememberSaveable{mutableStateOf("")};var policy by rememberSaveable{mutableStateOf("")};var reason by rememberSaveable{mutableStateOf(administrativeArchiveReason)}
    var policyName by rememberSaveable{mutableStateOf("")};var full by rememberSaveable{mutableStateOf("0.85")};var partial by rememberSaveable{mutableStateOf("0.50")};var late by rememberSaveable{mutableStateOf("10")};var early by rememberSaveable{mutableStateOf("10")};var absence by rememberSaveable{mutableStateOf("0.20")};var grace by rememberSaveable{mutableStateOf("120")};var minimum by rememberSaveable{mutableStateOf("300")};var confidence by rememberSaveable{mutableStateOf("0.60")};var editPolicySubject by rememberSaveable{mutableStateOf("")};var editPolicy by rememberSaveable{mutableStateOf("")}
    val fullValue=full.toDoubleOrNull();val partialValue=partial.toDoubleOrNull();val absenceValue=absence.toDoubleOrNull();val confidenceValue=confidence.toDoubleOrNull();val lateValue=late.toIntOrNull();val earlyValue=early.toIntOrNull();val graceValue=grace.toLongOrNull();val minimumValue=minimum.toLongOrNull()
    val policyValuesValid=fullValue!=null&&partialValue!=null&&absenceValue!=null&&confidenceValue!=null&&lateValue!=null&&earlyValue!=null&&graceValue!=null&&minimumValue!=null&&fullValue in 0.0..1.0&&partialValue in 0.0..1.0&&absenceValue in 0.0..1.0&&confidenceValue in 0.0..1.0&&fullValue>=partialValue&&partialValue>=absenceValue&&lateValue>=0&&earlyValue>=0&&graceValue>=0&&minimumValue>=0
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if("MANAGE_SUBJECTS" in permissions){
            item{Text(stringResource(R.string.attendance_policies),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(policyName,{policyName=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.policy_name))})}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(full,{full=it},Modifier.weight(1f),label={Text(stringResource(R.string.full_threshold))});OutlinedTextField(partial,{partial=it},Modifier.weight(1f),label={Text(stringResource(R.string.partial_threshold))});OutlinedTextField(absence,{absence=it},Modifier.weight(1f),label={Text(stringResource(R.string.absence_threshold))})}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(late,{late=it},Modifier.weight(1f),label={Text(stringResource(R.string.late_minutes))});OutlinedTextField(early,{early=it},Modifier.weight(1f),label={Text(stringResource(R.string.early_minutes))});OutlinedTextField(confidence,{confidence=it},Modifier.weight(1f),label={Text(stringResource(R.string.confidence_threshold))})}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(grace,{grace=it},Modifier.weight(1f),label={Text(stringResource(R.string.grace_seconds))});OutlinedTextField(minimum,{minimum=it},Modifier.weight(1f),label={Text(stringResource(R.string.verification_seconds))})}}
            item{Button(onClick={vm.addAttendancePolicy(policyName,full.toDouble(),partial.toDouble(),late.toInt(),early.toInt(),absence.toDouble(),grace.toLong(),minimum.toLong(),confidence.toDouble())},enabled=policyName.isNotBlank()&&policyValuesValid,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_attendance_policy))}}
            item{HorizontalDivider();Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(code,{code=it},Modifier.weight(1f),label={Text(stringResource(R.string.code))},textStyle=technicalTextStyle());OutlinedTextField(name,{name=it},Modifier.weight(2f),label={Text(stringResource(R.string.name))})}}
            item{EntityChoice(stringResource(R.string.teacher),teacher,teachers.map{it.id to it.fullName}){teacher=it}};item{EntityChoice(stringResource(R.string.level),level,levels.map{it.id to it.name}){level=it}};item{EntityChoice(stringResource(R.string.semester),semester,semesters.map{it.id to it.name}){semester=it}};item{EntityChoice(stringResource(R.string.group),group,groups.map{it.id to it.name}){group=it}};item{EntityChoice(stringResource(R.string.attendance_policy),policy,policies.map{it.id to it.name}){policy=it}}
            item{Button(onClick={vm.addSubject(code,name,teacher,level,semester,group,policy)},enabled=listOf(code,name,teacher,level,semester,group,policy).all{it.isNotBlank()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_subject))}};item{OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))})}
            if(subjects.any{it.attendancePolicyId==null||policies.none{p->p.id==it.attendancePolicyId}}){item{HorizontalDivider();Text(stringResource(R.string.subjects_requiring_attendance_policy),style=MaterialTheme.typography.titleMedium)};item{EntityChoice(stringResource(R.string.subject),editPolicySubject,subjects.filter{it.attendancePolicyId==null||policies.none{p->p.id==it.attendancePolicyId}}.map{it.id to it.name}){editPolicySubject=it;editPolicy=""}};item{EntityChoice(stringResource(R.string.attendance_policy),editPolicy,policies.map{it.id to it.name}){editPolicy=it}};item{Button(onClick={vm.updateSubjectAttendancePolicy(editPolicySubject,editPolicy)},enabled=editPolicySubject.isNotBlank()&&editPolicy.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.assign_attendance_policy))}}}
        }
        items(subjects,key={it.id}){sub->ListItem(headlineContent={Text(sub.name)},supportingContent={Text(listOf(sub.code,policies.firstOrNull{it.id==sub.attendancePolicyId}?.name?:stringResource(R.string.attendance_policy_required)).joinToString(" • "))},trailingContent={if("MANAGE_SUBJECTS" in permissions)TextButton(onClick={vm.archiveSubject(sub.id,reason)},enabled=reason.isNotBlank()){Text(stringResource(R.string.archive))}})}
    }
}

@Composable private fun TimetableManagementPane(permissions:Set<String>,groups:List<GroupEntity>,teachers:List<TeacherEntity>,subjects:List<SubjectEntity>,versions:List<WeeklyTimetableVersionEntity>,review:com.hammam.attendai.data.repository.TimetableRepository.DraftReview?,vm:AdminOperationsViewModel){
    val context=LocalContext.current
    val sessionUserId by vm.sessionUserId.collectAsState()
    var group by rememberSaveable{mutableStateOf("")};var weekStart by rememberSaveable{mutableStateOf("")};var approvalReason by rememberSaveable{mutableStateOf("")}
    var editRows by remember{mutableStateOf<List<com.hammam.attendai.data.repository.TimetableRepository.DraftRow>>(emptyList())}
    LaunchedEffect(sessionUserId){group="";weekStart="";approvalReason="";editRows=emptyList();vm.selectGroup(null)}
    LaunchedEffect(review?.version?.id,review?.rows){val r=review;editRows=r?.rows?.map{com.hammam.attendai.data.repository.TimetableRepository.DraftRow(it.dayOfWeek,it.subjectId,it.teacherId,it.startTime,it.endTime,it.room,it.lectureType,it.scheduleKind)}?:emptyList()}
    val persistedReviewRows=review?.rows?.map{com.hammam.attendai.data.repository.TimetableRepository.DraftRow(it.dayOfWeek,it.subjectId,it.teacherId,it.startTime,it.endTime,it.room,it.lectureType,it.scheduleKind)}?:emptyList()
    val hasUnsavedReviewChanges=review!=null&&editRows!=persistedReviewRows
    fun handleTimetableUri(uri:android.net.Uri?){if(uri!=null&&group.isNotBlank()&&weekStart.isNotBlank()){runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};vm.importDocument(group,weekStart,uri,context.contentResolver.getType(uri))}}
    val fileLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),::handleTimetableUri)
    val contentFallback=rememberLauncherForActivityResult(ActivityResultContracts.GetContent(),::handleTimetableUri)
    val cameraIoScope=rememberCoroutineScope()
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->if(bitmap!=null&&group.isNotBlank()&&weekStart.isNotBlank()){val selectedGroup=group;val selectedWeekStart=weekStart;cameraIoScope.launch{
        try{
            val f=withContext(Dispatchers.IO){val dir=java.io.File(context.filesDir,"timetable_imports").apply{mkdirs()};dir.listFiles()?.sortedByDescending{it.lastModified()}?.drop(4)?.forEach{it.delete()};java.io.File(dir,"capture-${System.currentTimeMillis()}.jpg").also{file->file.outputStream().use{out->if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out))error("TIMETABLE_IMAGE_WRITE_FAILED")}}}
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",f);vm.importCameraImage(selectedGroup,selectedWeekStart,uri)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){vm.reportFileActionFailure(LocalAccessRules.safeErrorCode(e.message,"TIMETABLE_IMAGE_WRITE_FAILED"))}
    }}}
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{EntityChoice(stringResource(R.string.group),group,groups.map{it.id to it.name}){group=it;vm.selectGroup(it)}}
        item{OutlinedTextField(weekStart,{weekStart=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.week_start_hint))},textStyle=technicalTextStyle())}
        if("MANAGE_TIMETABLE" in permissions)item{BoxWithConstraints(Modifier.fillMaxWidth()){if(maxWidth<520.dp)Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Button({vm.createManualDraft(group,weekStart)},enabled=group.isNotBlank()&&weekStart.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.manual_entry))};OutlinedButton({launchFileActionSafely({fileLauncher.launch(arrayOf("text/*","text/csv","application/pdf","image/*"))},{contentFallback.launch("*/*")},vm::reportFileActionFailure)},enabled=group.isNotBlank()&&weekStart.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_file))};OutlinedButton({launchFileActionSafely({camera.launch(null)},null,vm::reportFileActionFailure)},enabled=group.isNotBlank()&&weekStart.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.camera_capture))}}else Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({vm.createManualDraft(group,weekStart)},enabled=group.isNotBlank()&&weekStart.isNotBlank()){Text(stringResource(R.string.manual_entry))};OutlinedButton({launchFileActionSafely({fileLauncher.launch(arrayOf("text/*","text/csv","application/pdf","image/*"))},{contentFallback.launch("*/*")},vm::reportFileActionFailure)},enabled=group.isNotBlank()&&weekStart.isNotBlank()){Text(stringResource(R.string.import_file))};OutlinedButton({launchFileActionSafely({camera.launch(null)},null,vm::reportFileActionFailure)},enabled=group.isNotBlank()&&weekStart.isNotBlank()){Text(stringResource(R.string.camera_capture))}}}}
        items(versions,key={it.id}){v->ListItem(headlineContent={Text("${v.weekStart} • v${v.versionNumber}")},supportingContent={Text("${localizedInternalLabel(v.status)} • ${localizedInternalLabel(v.sourceType)}")},modifier=Modifier.clickable{vm.loadReview(v.id)})}
        review?.let{r->item{HorizontalDivider();Text("${stringResource(R.string.review)} • ${localizedInternalLabel(r.version.status)}",style=MaterialTheme.typography.titleMedium);r.issues.forEach{Text(userMessageText(it.code),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
            items(editRows.size){index->val row=editRows[index];Card{Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(row.dayOfWeek.toString(),{v->editRows=editRows.toMutableList().also{it[index]=row.copy(dayOfWeek=v.toIntOrNull()?:0)}},Modifier.weight(1f),label={Text(stringResource(R.string.day))});OutlinedTextField(row.startTime,{v->editRows=editRows.toMutableList().also{it[index]=row.copy(startTime=v)}},Modifier.weight(1f),label={Text(stringResource(R.string.start_time))});OutlinedTextField(row.endTime,{v->editRows=editRows.toMutableList().also{it[index]=row.copy(endTime=v)}},Modifier.weight(1f),label={Text(stringResource(R.string.end_time))})};EntityChoice(stringResource(R.string.subject),row.subjectId,subjects.filter{it.groupId==r.version.groupId}.map{it.id to it.name}){v->editRows=editRows.toMutableList().also{it[index]=row.copy(subjectId=v)}};EntityChoice(stringResource(R.string.teacher),row.teacherId,teachers.map{it.id to it.fullName}){v->editRows=editRows.toMutableList().also{it[index]=row.copy(teacherId=v)}};OutlinedTextField(row.room.orEmpty(),{v->editRows=editRows.toMutableList().also{it[index]=row.copy(room=v)}},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.room))});TextButton({editRows=editRows.toMutableList().also{it.removeAt(index)}}){Text(stringResource(R.string.remove))}}}}
            if("MANAGE_TIMETABLE" in permissions){item{OutlinedButton(onClick={editRows=editRows+com.hammam.attendai.data.repository.TimetableRepository.DraftRow(1,subjects.firstOrNull{it.groupId==r.version.groupId}?.id.orEmpty(),teachers.firstOrNull()?.id.orEmpty(),"08:00","09:00",null)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_row))}};item{Button(onClick={vm.saveRows(r.version.id,editRows)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save_review))}};item{OutlinedTextField(approvalReason,{approvalReason=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.approval_reason))})};item{Button(onClick={vm.approve(r.version.id,approvalReason)},enabled=r.issues.isEmpty()&&!hasUnsavedReviewChanges&&approvalReason.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.approve_timetable))}}}
        }
    }
}

@Composable private fun AiProvidersPane(permissions:Set<String>,vm:AdminOperationsViewModel){
    val health by vm.providerHealth.collectAsState();val defaultProvider by vm.defaultAiProvider.collectAsState();val reportAssignment by vm.reportModelAssignment.collectAsState();val complexAssignment by vm.complexModelAssignment.collectAsState()
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text(stringResource(R.string.ai_provider_security_note),style=MaterialTheme.typography.bodySmall)}
        if("MANAGE_AI_PROVIDER" in permissions)item{ChoiceDropdown(stringResource(R.string.default_ai_provider),defaultProvider,AiProviderNames.all){vm.setDefaultAiProvider(it)}}
        items(health,key={it.provider}){h->AiProviderCard(h,"MANAGE_AI_PROVIDER" in permissions,vm)}
        if("MANAGE_AI_PROVIDER" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.ai_model_assignment),style=MaterialTheme.typography.titleMedium)}
            item{ChoiceDropdown(stringResource(R.string.report_summary_model),reportAssignment,listOf("USE_DEFAULT")+health.flatMap{h->h.cachedModels.map{"${h.provider}:$it"}},technical=true){vm.setAiAssignment("report",it)}}
            item{ChoiceDropdown(stringResource(R.string.complex_analysis_model),complexAssignment,listOf("USE_DEFAULT")+health.flatMap{h->h.cachedModels.map{"${h.provider}:$it"}},technical=true){vm.setAiAssignment("complex",it)}}
        }
    }
}

@Composable private fun AiProviderCard(h:ProviderHealth,editable:Boolean,vm:AdminOperationsViewModel){
    var key by rememberSaveable(h.provider){mutableStateOf("")};var manualModel by rememberSaveable(h.provider){mutableStateOf("")}
    Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        LtrText(h.provider)
        Text("${stringResource(R.string.provider_status)}: ${localizedInternalLabel(h.connectionStatus)}")
        Text("${stringResource(R.string.provider_configuration)}: ${if(h.configured)stringResource(R.string.configured) else stringResource(R.string.not_configured)}")
        Text("${stringResource(R.string.key_mode)}: ${localizedInternalLabel(h.mode.name)}")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("${stringResource(R.string.selected_model)}:",Modifier.weight(1f));if(h.selectedModel.isNullOrBlank())Text(stringResource(R.string.selected_model_no_value)) else LtrText(h.selectedModel)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("${stringResource(R.string.provider_endpoint)}:",Modifier.weight(1f));if(h.endpoint!=null)LtrText(h.endpoint) else Text(stringResource(R.string.uses_app_backend))}
        if(h.mode==AiKeyMode.LOCAL_BYOK)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("${stringResource(R.string.credential_state)}:",Modifier.weight(1f));h.maskedKey?.let{LtrText(it)}?:Text(stringResource(R.string.not_configured))}
        h.lastTest?.let{Text("${stringResource(R.string.last_test)}: ${formatTime(it)}",style=MaterialTheme.typography.bodySmall)}
        h.lastModelRefresh?.let{Text("${stringResource(R.string.last_model_refresh)}: ${formatTime(it)}",style=MaterialTheme.typography.bodySmall)}
        h.error?.let{Text(userMessageText(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        if(editable){
            ChoiceDropdown(stringResource(R.string.key_mode),h.mode.name,AiKeyMode.entries.map{it.name}){vm.setAiMode(h.provider,AiKeyMode.valueOf(it))}
            if(h.mode==AiKeyMode.LOCAL_BYOK){
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.api_key))},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick={vm.setAiKey(h.provider,key);key=""},enabled=key.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.add_replace_key))};OutlinedButton(onClick={vm.deleteAiKey(h.provider)},modifier=Modifier.weight(1f)){Text(stringResource(R.string.delete_key))}}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={vm.testAiProvider(h.provider)},enabled=h.configured&&!h.selectedModel.isNullOrBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.test_connection))};OutlinedButton(onClick={vm.refreshAiModels(h.provider)},enabled=h.configured,modifier=Modifier.weight(1f)){Text(stringResource(R.string.refresh_models))}}
            if(h.cachedModels.isNotEmpty())EntityChoice(stringResource(R.string.selected_model),h.selectedModel.orEmpty(),h.cachedModels.map{it to it},technical=true){vm.selectAiModel(h.provider,it)}
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){OutlinedTextField(manualModel,{manualModel=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.manual_model_id))},singleLine=true)}
            TextButton(onClick={vm.selectAiModel(h.provider,manualModel);manualModel=""},enabled=manualModel.isNotBlank()){Text(stringResource(R.string.use_manual_model))}
        }
    }}
}

@Composable private fun ReportSettingsPane(permissions:Set<String>,teachers:List<TeacherEntity>,subjects:List<SubjectEntity>,vm:AdminOperationsViewModel){
    val saved by vm.reportSettings.collectAsState()
    var teacher by rememberSaveable{mutableStateOf("")};var subject by rememberSaveable{mutableStateOf("")};var enabled by rememberSaveable{mutableStateOf(true)}
    var daily by rememberSaveable{mutableStateOf(true)};var weekly by rememberSaveable{mutableStateOf(false)};var monthly by rememberSaveable{mutableStateOf(false)};var semester by rememberSaveable{mutableStateOf(false)}
    var sendTime by rememberSaveable{mutableStateOf("18:00")};var weeklyDay by rememberSaveable{mutableStateOf("7")};var monthlyDay by rememberSaveable{mutableStateOf("28")};var timezone by rememberSaveable{mutableStateOf(java.time.ZoneId.systemDefault().id)};var channel by rememberSaveable{mutableStateOf("EMAIL")};var format by rememberSaveable{mutableStateOf("PDF")}
    var details by rememberSaveable{mutableStateOf(true)};var approval by rememberSaveable{mutableStateOf(true)};var ai by rememberSaveable{mutableStateOf(false)};var noLecture by rememberSaveable{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{EntityChoice(stringResource(R.string.teacher),teacher,teachers.map{it.id to it.fullName}){teacher=it;subject=""}}
        item{EntityChoice(stringResource(R.string.subject),subject,listOf("" to stringResource(R.string.all_subjects))+subjects.filter{teacher.isBlank()||it.teacherId==teacher}.map{it.id to it.name}){subject=it}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Switch(enabled,{enabled=it});Spacer(Modifier.width(8.dp));Text(stringResource(R.string.enabled))}}
        item{Text(stringResource(R.string.report_frequency),style=MaterialTheme.typography.titleMedium)}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(selected=daily,onClick={daily=!daily},label={Text(stringResource(R.string.daily))});FilterChip(selected=weekly,onClick={weekly=!weekly},label={Text(stringResource(R.string.weekly))});FilterChip(selected=monthly,onClick={monthly=!monthly},label={Text(stringResource(R.string.monthly))});FilterChip(selected=semester,onClick={semester=!semester},label={Text(stringResource(R.string.end_of_semester))})}}
        item{Text("${stringResource(R.string.custom)}: ${stringResource(R.string.custom_report_unavailable_note)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{OutlinedTextField(sendTime,{sendTime=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.send_time))},textStyle=technicalTextStyle())}
        if(weekly)item{OutlinedTextField(weeklyDay,{weeklyDay=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.weekly_day))})}
        if(monthly)item{OutlinedTextField(monthlyDay,{monthlyDay=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.monthly_day))})}
        item{OutlinedTextField(timezone,{timezone=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.timezone))},textStyle=technicalTextStyle())}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){ChoiceDropdown(stringResource(R.string.channel),channel,listOf("EMAIL","WHATSAPP")){channel=it}};Box(Modifier.weight(1f)){ChoiceDropdown(stringResource(R.string.format),format,listOf("PDF","CSV","SUMMARY")){format=it}}}}
        item{BooleanSetting(stringResource(R.string.include_student_details),details){details=it};BooleanSetting(stringResource(R.string.require_approval),approval){approval=it};BooleanSetting(stringResource(R.string.ai_summary),false,enabled=false){ai=false};Text(stringResource(R.string.ai_summary_unavailable_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);BooleanSetting(stringResource(R.string.send_if_no_lecture),noLecture){noLecture=it}}
        if("MANAGE_REPORT_SETTINGS" in permissions)item{Button(onClick={val f=buildSet{if(daily)add("DAILY");if(weekly)add("WEEKLY");if(monthly)add("MONTHLY");if(semester)add("END_OF_SEMESTER");/* CUSTOM is intentionally not persisted until executable scheduling exists. */};vm.saveReportSetting(teacher,subject.ifBlank{null},enabled,f,sendTime,weeklyDay.toIntOrNull(),monthlyDay.toIntOrNull(),timezone,channel,format,details,approval,ai,noLecture)},enabled=teacher.isNotBlank()&&(daily||weekly||monthly||semester),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.save))}}
        item{HorizontalDivider();Text(stringResource(R.string.saved_settings),style=MaterialTheme.typography.titleMedium)}
        items(saved,key={it.id}){r->val tn=teachers.firstOrNull{it.id==r.teacherId}?.fullName?:r.teacherId;val sn=r.subjectId?.let{id->subjects.firstOrNull{it.id==id}?.name}?:stringResource(R.string.all_subjects);ListItem(headlineContent={Text("$tn • $sn")},supportingContent={Text("${localizedInternalLabel(r.frequency)} • ${localizedInternalLabel(r.channel)} • ${localizedInternalLabel(r.reportFormat)} • ${localizedInternalLabel(if(r.requireApproval)"APPROVAL" else "AUTO")}")})}
    }
}

@Composable private fun BooleanSetting(label:String,value:Boolean,enabled:Boolean=true,onValue:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onCheckedChange=onValue,enabled=enabled)}}

@Composable private fun EntityChoice(label:String,value:String,options:List<Pair<String,String>>,technical:Boolean=false,onSelect:(String)->Unit){
    var expanded by remember{mutableStateOf(false)};val shown=options.firstOrNull{it.first==value}?.second?:stringResource(R.string.select_value)
    Box(Modifier.fillMaxWidth()){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text("$label:");Spacer(Modifier.width(6.dp));Box(Modifier.weight(1f)){if(technical)LtrText(shown) else Text(shown)};Icon(Icons.Default.ArrowDropDown,null)};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{(id,name)->DropdownMenuItem(text={if(technical)LtrText(name) else Text(name)},onClick={onSelect(id);expanded=false})}}}
}
@Composable private fun ChoiceDropdown(label:String,value:String,options:List<String>,technical:Boolean=false,onSelect:(String)->Unit){EntityChoice(label,value,options.map{it to localizedInternalLabel(it)},technical,onSelect)}


@Composable fun AiAssistantScreen(
    state:AiAssistantUiState,
    canConfigureProvider:Boolean,
    onInput:(String)->Unit,
    onSend:()->Unit,
    onClear:()->Unit,
    onRetry:(String)->Unit,
    onCloudSummary:(String)->Unit,
    onConfigureProvider:()->Unit,
){
    val listState=rememberLazyListState()
    val provider=state.provider
    val itemCount=state.messages.size+if(state.loading)1 else 0
    LaunchedEffect(itemCount){if(itemCount>0)listState.animateScrollToItem(itemCount-1)}
    Scaffold(
        topBar={TopAppBar(
            title={Column{
                Text(stringResource(R.string.ai_assistant),fontWeight=FontWeight.SemiBold)
                if(provider!=null){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){Text(provider.providerId,style=MaterialTheme.typography.labelSmall)}
                        Text("•",style=MaterialTheme.typography.labelSmall)
                        if(provider.modelId.isNullOrBlank())Text(stringResource(R.string.selected_model_no_value),style=MaterialTheme.typography.labelSmall)
                        else CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){Text(provider.modelId,style=MaterialTheme.typography.labelSmall)}
                    }
                    Text(localizedInternalLabel(provider.status),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }},
            actions={
                IconButton(onClick=onClear,enabled=state.messages.isNotEmpty()&&!state.loading){Icon(Icons.Default.DeleteSweep,stringResource(R.string.assistant_new_conversation))}
                if(canConfigureProvider)IconButton(onClick=onConfigureProvider){Icon(Icons.Default.Settings,stringResource(R.string.provider_configuration))}
            },
        )},
        bottomBar={Surface(tonalElevation=3.dp){Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=10.dp,vertical=8.dp),
            verticalAlignment=Alignment.Bottom,
            horizontalArrangement=Arrangement.spacedBy(8.dp),
        ){
            OutlinedTextField(
                value=state.input,onValueChange=onInput,modifier=Modifier.weight(1f),
                placeholder={Text(stringResource(R.string.ai_query_hint))},minLines=1,maxLines=5,
                enabled=!state.loading,
            )
            FilledIconButton(onClick=onSend,enabled=AssistantChatRules.canSend(state.input,state.loading)){Icon(Icons.Default.Send,stringResource(R.string.assistant_send))}
        }}}
    ){padding->
        LazyColumn(
            state=listState,
            modifier=Modifier.fillMaxSize().padding(padding),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp),
        ){
            if(state.messages.isEmpty()&&!state.loading){item{
                Column(Modifier.fillMaxWidth().padding(top=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Icon(Icons.Default.SmartToy,null,modifier=Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.assistant_empty_title),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                    Text(stringResource(R.string.assistant_empty_body),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(provider==null||!provider.configured)Text(stringResource(R.string.assistant_not_configured_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val suggestions=listOf(
                        stringResource(R.string.assistant_suggestion_student),
                        stringResource(R.string.assistant_suggestion_absent),
                        stringResource(R.string.assistant_suggestion_late),
                        stringResource(R.string.assistant_suggestion_review),
                    )
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        suggestions.forEach{suggestion->AssistChip(onClick={onInput(suggestion)},label={Text(suggestion)})}
                    }
                    Text(stringResource(R.string.ai_read_only_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }}
            items(state.messages,key={it.id}){message->AssistantChatMessageCard(message,onRetry,onCloudSummary)}
            if(state.loading)item(key="assistant-loading"){
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterStart){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text(stringResource(R.string.assistant_thinking))}}}
            }
        }
    }
}

@Composable
private fun AssistantChatMessageCard(message:AssistantChatMessage,onRetry:(String)->Unit,onCloudSummary:(String)->Unit){
    val isUser=message.role==AssistantMessageRole.USER
    val isError=message.role==AssistantMessageRole.ERROR
    val isSystem=message.role==AssistantMessageRole.SYSTEM
    val text=when{
        message.answer!=null->assistantAnswerText(message.answer)
        message.errorCode!=null->userMessageText(message.errorCode)
        else->message.text.orEmpty()
    }
    Box(Modifier.fillMaxWidth(),contentAlignment=if(isUser)Alignment.CenterEnd else Alignment.CenterStart){
        Card(
            modifier=Modifier.fillMaxWidth(if(isUser)0.82f else 0.9f),
            colors=CardDefaults.cardColors(containerColor=when{isUser->MaterialTheme.colorScheme.primaryContainer;isError->MaterialTheme.colorScheme.errorContainer;isSystem->MaterialTheme.colorScheme.secondaryContainer;else->MaterialTheme.colorScheme.surfaceVariant}),
        ){
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                SelectionContainer{Text(text,color=when{isError->MaterialTheme.colorScheme.onErrorContainer;isSystem->MaterialTheme.colorScheme.onSecondaryContainer;else->MaterialTheme.colorScheme.onSurface})}
                message.answer?.let{answer->
                    if(answer.periodStart!=null&&answer.periodEnd!=null)Text("${stringResource(R.string.grounded_period)}: ${formatTime(answer.periodStart)} – ${formatTime(answer.periodEnd)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(answer.groundedFacts.isNotEmpty())TextButton(onClick={onCloudSummary(message.id)}){Text(stringResource(R.string.cloud_summary))}
                }
                if(isError&&message.sourceQuery?.isNotBlank()==true)TextButton(onClick={onRetry(message.id)}){Text(stringResource(R.string.assistant_retry))}
            }
        }
    }
}

@Composable
private fun assistantAnswerText(answer:AssistantAnswer):String=when(answer.replyCode){
    AssistantReplyCode.STUDENT_NOT_FOUND->stringResource(R.string.assistant_student_not_found)
    AssistantReplyCode.STUDENT_AMBIGUOUS->stringResource(R.string.assistant_student_ambiguous)
    AssistantReplyCode.STUDENT_ATTENDANCE->stringResource(
        R.string.assistant_student_attendance,
        answer.values["studentName"].orEmpty(),answer.values["present"].orEmpty(),answer.values["absent"].orEmpty(),
        answer.values["late"].orEmpty(),answer.values["partial"].orEmpty(),answer.values["attendanceRate"].orEmpty(),
    )
    AssistantReplyCode.ABSENT_TODAY->stringResource(R.string.assistant_absent_today,answer.values["count"].orEmpty())
    AssistantReplyCode.LATE_TODAY->stringResource(R.string.assistant_late_today,answer.values["count"].orEmpty())
    AssistantReplyCode.NEEDS_REVIEW->stringResource(R.string.assistant_needs_review,answer.values["count"].orEmpty())
    AssistantReplyCode.SUBJECT_SELECTION_REQUIRED->stringResource(R.string.assistant_subject_selection_required)
    AssistantReplyCode.LOCAL_CAPABILITIES->stringResource(R.string.assistant_local_capabilities)
}



@Composable fun LocalSessionRecoveryScreen(
    status:LocalSessionStatus,
    ownerName:String?,
    adminName:String?,
    onRestoreOwner:()->Unit,
    onRestoreAdmin:()->Unit
){
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Default.Security,null,modifier=Modifier.size(48.dp));Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.local_session_unavailable),style=MaterialTheme.typography.headlineSmall)
        Text(when(status){LocalSessionStatus.MISSING_USER->stringResource(R.string.local_session_missing_user);LocalSessionStatus.DISABLED_USER->stringResource(R.string.local_session_disabled_user);LocalSessionStatus.MISSING_ROLE->stringResource(R.string.local_session_missing_role);else->stringResource(R.string.local_session_unavailable_note)},color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when{
            ownerName!=null->{Text(stringResource(R.string.local_session_owner_recovery_note,ownerName));Button(onClick=onRestoreOwner,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.restore_owner_session))}}
            adminName!=null->{Text(stringResource(R.string.local_session_admin_recovery_note,adminName));Button(onClick=onRestoreAdmin,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.restore_admin_session))}}
        }
    }
}

@Composable
private fun firstRunErrorText(code:String):String = when(code){
    "OWNER_NAME_REQUIRED" -> stringResource(R.string.first_run_error_owner_name)
    "INVALID_PIN" -> stringResource(R.string.first_run_error_pin)
    "ACADEMIC_YEAR_FIELDS_INCOMPLETE" -> stringResource(R.string.first_run_error_year_incomplete)
    "SEMESTER_FIELDS_INCOMPLETE" -> stringResource(R.string.first_run_error_semester_incomplete)
    "INVALID_DATE_FORMAT" -> stringResource(R.string.first_run_error_invalid_date)
    "INVALID_DATE_RANGE" -> stringResource(R.string.first_run_error_date_range)
    "SEMESTER_REQUIRES_ACADEMIC_YEAR" -> stringResource(R.string.first_run_error_semester_requires_year)
    "SEMESTER_OUTSIDE_ACADEMIC_YEAR" -> stringResource(R.string.first_run_error_semester_outside_year)
    "DATABASE_ALREADY_INITIALIZED" -> stringResource(R.string.first_run_error_existing_database)
    "EXISTING_DATABASE_NO_ADMIN" -> stringResource(R.string.first_run_error_existing_no_admin)
    "EXISTING_DATABASE_OWNER_UNAVAILABLE","SYSTEM_OWNER_ALREADY_EXISTS","INITIAL_OWNER_ALREADY_EXISTS" -> stringResource(R.string.first_run_error_owner_unavailable)
    "PIN_SETUP_FAILED" -> stringResource(R.string.first_run_error_pin_storage)
    else -> stringResource(R.string.first_run_error_generic)
}

@Composable fun FirstRunScreen(
    onComplete:(FirstRunSetup)->Unit,
    isSubmitting:Boolean=false,
    errorMessage:String?=null,
    recoveryAdminName:String?=null,
    onRecoverExistingOwner:()->Unit={},
){
    var displayName by rememberSaveable{mutableStateOf("")}
    var language by rememberSaveable{mutableStateOf("AR")}
    var pin by rememberSaveable{mutableStateOf("")}
    var yearName by rememberSaveable{mutableStateOf("")}
    var yearStart by rememberSaveable{mutableStateOf("")}
    var yearEnd by rememberSaveable{mutableStateOf("")}
    var semesterName by rememberSaveable{mutableStateOf("")}
    var semesterStart by rememberSaveable{mutableStateOf("")}
    var semesterEnd by rememberSaveable{mutableStateOf("")}
    var confirmRecovery by rememberSaveable{mutableStateOf(false)}
    val fieldStyle=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium)
    val fieldColors=OutlinedTextFieldDefaults.colors(
        focusedTextColor=MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor=MaterialTheme.colorScheme.onSurface,
        focusedLabelColor=MaterialTheme.colorScheme.onSurface,
        unfocusedLabelColor=MaterialTheme.colorScheme.onSurfaceVariant,
        focusedSupportingTextColor=MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedSupportingTextColor=MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if(confirmRecovery)AlertDialog(onDismissRequest={confirmRecovery=false},title={Text(stringResource(R.string.owner_upgrade_setup))},text={Text(stringResource(R.string.owner_upgrade_confirmation))},confirmButton={TextButton(onClick={confirmRecovery=false;onRecoverExistingOwner()}){Text(stringResource(R.string.confirm))}},dismissButton={TextButton(onClick={confirmRecovery=false}){Text(stringResource(R.string.cancel))}})
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(stringResource(R.string.first_run_title),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}
        item{Text(stringResource(R.string.first_run_owner_description),style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium)}
        errorMessage?.let{code->item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Text(firstRunErrorText(code),Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.onErrorContainer,fontWeight=FontWeight.Medium)}}}
        recoveryAdminName?.let{name->item{Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(stringResource(R.string.first_run_recovery_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(stringResource(R.string.first_run_recovery_note,name),fontWeight=FontWeight.Medium);Button(onClick={confirmRecovery=true},enabled=!isSubmitting,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.recover_existing_owner))}}}}}
        item{Text(stringResource(R.string.language),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=language=="AR",onClick={language="AR"},enabled=!isSubmitting,label={Text(stringResource(R.string.language_arabic),fontWeight=FontWeight.Medium)});FilterChip(selected=language=="EN",onClick={language="EN"},enabled=!isSubmitting,label={Text(stringResource(R.string.language_english),fontWeight=FontWeight.Medium)})}}
        item{OutlinedTextField(displayName,{displayName=it},Modifier.fillMaxWidth(),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.system_owner_name),fontWeight=FontWeight.Medium)},supportingText={Text(stringResource(R.string.system_owner_protection_note),fontWeight=FontWeight.Medium)})}
        item{OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},Modifier.fillMaxWidth(),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.pin_optional),fontWeight=FontWeight.Medium)},supportingText={Text(stringResource(R.string.pin_optional_note),fontWeight=FontWeight.Medium)},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))}
        item{HorizontalDivider();Text(stringResource(R.string.academic_setup_optional),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item{Text(stringResource(R.string.date_input_hint),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{OutlinedTextField(yearName,{yearName=it},Modifier.fillMaxWidth(),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.academic_year),fontWeight=FontWeight.Medium)})}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(yearStart,{yearStart=it},Modifier.weight(1f),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.start_date),fontWeight=FontWeight.Medium)});OutlinedTextField(yearEnd,{yearEnd=it},Modifier.weight(1f),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.end_date),fontWeight=FontWeight.Medium)})}}
        item{OutlinedTextField(semesterName,{semesterName=it},Modifier.fillMaxWidth(),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.semester),fontWeight=FontWeight.Medium)})}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(semesterStart,{semesterStart=it},Modifier.weight(1f),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.start_date),fontWeight=FontWeight.Medium)});OutlinedTextField(semesterEnd,{semesterEnd=it},Modifier.weight(1f),enabled=!isSubmitting,textStyle=fieldStyle,colors=fieldColors,label={Text(stringResource(R.string.end_date),fontWeight=FontWeight.Medium)})}}
        item{Text(stringResource(R.string.first_run_optional_note),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)}
        item{HorizontalDivider();Text(stringResource(R.string.device_readiness),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item{DeviceReadinessCard(DashboardState())}
        item{Button(onClick={onComplete(FirstRunSetup(displayName,language,pin.takeIf{it.isNotBlank()},yearName,yearStart,yearEnd,semesterName,semesterStart,semesterEnd))},enabled=!isSubmitting&&displayName.isNotBlank()&&(pin.isBlank()||pin.length in 4..12),modifier=Modifier.fillMaxWidth()){if(isSubmitting){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.setup_in_progress))}else Text(stringResource(R.string.finish_setup))}}
    }
}

@Composable private fun LtrText(value:String){CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr){Text(value)}}
@Composable private fun technicalTextStyle()=LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr)

private fun formatTime(epoch:Long):String=DateFormat.format("yyyy-MM-dd HH:mm",Date(epoch)).toString()
@Composable private fun roleDisplayName(role:String)=when(role){
    "Student"->stringResource(R.string.role_student)
    "Representative"->stringResource(R.string.role_representative)
    "Assistant Representative"->stringResource(R.string.role_assistant_representative)
    "Teacher"->stringResource(R.string.role_teacher)
    "Academic Supervisor"->stringResource(R.string.role_academic_supervisor)
    "Administrator"->stringResource(R.string.role_admin)
    else->role
}
