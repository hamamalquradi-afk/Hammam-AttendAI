package com.hammam.attendai.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.ble.BlePlatformReadiness
import com.hammam.attendai.ble.DeviceActiveInvariantRules
import com.hammam.attendai.ble.DeviceActiveState
import com.hammam.attendai.ble.StudentPresenceService
import com.hammam.attendai.ble.StudentDeviceSessionRules
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.DeviceStatus
import com.hammam.attendai.domain.model.StudentStatus
import com.hammam.attendai.security.LocalAccessRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudentModeViewModel(app:Application):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val dao=container.database.coreDao()
    private val appContext=app.applicationContext
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    val advertising=StudentPresenceService.active
    val presenceError=StudentPresenceService.error
    private val _pairingPayload=MutableStateFlow<String?>(null);val pairingPayload=_pairingPayload.asStateFlow()
    private val _qrPayload=MutableStateFlow<String?>(null);val qrPayload=_qrPayload.asStateFlow()
    private var pairingExpiryJob:Job?=null
    private var qrExpiryJob:Job?=null
    private var pairingStudentId:String?=null
    private var qrStudentId:String?=null
    private var qrLectureId:String?=null
    private var qrDeviceId:String?=null

    val student:StateFlow<StudentEntity?> = container.preferences.userId.flatMapLatest{id->
        if(id==null)flowOf(null) else combine(dao.observeUserById(id),dao.observeRoleNames(id),dao.observeActiveUserScopes(id)){user,roles,scopes->Triple(user,roles,scopes.size)}
            .mapLatest{(user,roles,_)->
                val linked=if(user?.isActive==true)dao.getStudentForUser(id) else null
                linked?.takeIf{StudentDeviceSessionRules.usable(user!=null,user?.isActive==true,LocalAccessRules.primaryRole(roles),true,it.status==StudentStatus.ACTIVE)}
            }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    val devices:StateFlow<List<StudentDeviceEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeStudentDevices(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val activeLecture:StateFlow<LectureEntity?> = student.flatMapLatest{s->s?.groupId?.let{dao.observeActiveLectureForGroup(it)}?:flowOf(null)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    val records:StateFlow<List<AttendanceRecordEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeStudentRecords(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val replacementRequests:StateFlow<List<DeviceReplacementRequestEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeDeviceReplacementRequests(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    private val bleEnabled=dao.observeFeatureFlags().map{rows->rows.firstOrNull{it.code=="BLE_ATTENDANCE"}?.enabled==true}.distinctUntilChanged()

    init{
        viewModelScope.launch{
            var previousStudentId:String?=null
            combine(student,activeLecture,devices,bleEnabled){s,lecture,deviceRows,enabled->DeviceUiContext(s,lecture,deviceRows,enabled)}.collect{ctx->
                val studentId=ctx.student?.id
                if(previousStudentId!=studentId){
                    clearPairingPayload()
                    clearQrPayload()
                    stopAdvertising(clearRuntimeError=true)
                }
                previousStudentId=studentId
                val activeDevice=ctx.student?.let{s->ctx.devices.firstOrNull{it.status==DeviceStatus.ACTIVE&&it.id==s.registeredDeviceId}}
                if(ctx.student==null || ctx.lecture==null || activeDevice==null || !ctx.bleEnabled){
                    clearQrPayload()
                    if(StudentPresenceService.active.value)stopAdvertising(clearRuntimeError=false)
                }
                if(pairingStudentId!=null && (pairingStudentId!=studentId || ctx.devices.any{it.status==DeviceStatus.ACTIVE}))clearPairingPayload()
                if(qrStudentId!=null && (qrStudentId!=studentId || qrLectureId!=ctx.lecture?.id || qrDeviceId!=activeDevice?.id))clearQrPayload()
            }
        }
    }

    fun enrollInitial(){viewModelScope.launch{
        runCatching{
            val (actor,s)=currentStudentContext()
            container.devices.createPairingOffer(s.id,actor)
        }.onSuccess{offer->
            pairingStudentId=offer.device.studentId
            _pairingPayload.value=offer.payload
            pairingExpiryJob?.cancel()
            pairingExpiryJob=viewModelScope.launch{delay((offer.expiresAt-System.currentTimeMillis()).coerceAtLeast(0));if(_pairingPayload.value==offer.payload)clearPairingPayload()}
            _message.value="DEVICE_PAIRING_READY"
        }.onFailure{_message.value=LocalAccessRules.safeErrorCode(it.message,"DEVICE_ENROLLMENT_FAILED")}
    }}

    fun requestReplacement(){viewModelScope.launch{
        runCatching{
            val (actor,s)=currentStudentContext()
            container.devices.requestReplacement(s.id,actor)
        }.onSuccess{_message.value="DEVICE_REPLACEMENT_REQUESTED"}
            .onFailure{_message.value=LocalAccessRules.safeErrorCode(it.message,"DEVICE_REPLACEMENT_FAILED")}
    }}

    fun generateQrFallback(){viewModelScope.launch{
        runCatching{
            if(dao.isFeatureEnabled("QR_ATTENDANCE")!=true)error("QR_ATTENDANCE_DISABLED")
            val (_,s)=currentStudentContext()
            val lectureSnapshot=activeLecture.value?:error("NO_ACTIVE_LECTURE")
            if(lectureSnapshot.groupId!=s.groupId)error("LECTURE_OUT_OF_SCOPE")
            val device=activeDeviceFor(s)
            if(dao.getAttendanceRecord(lectureSnapshot.id,s.id)==null)error("STUDENT_NOT_IN_ATTENDANCE_ROSTER")
            val token=container.devices.rotatingToken(device.id)?:error("BLE_TOKEN_UNAVAILABLE")
            QrSnapshot(s.id,lectureSnapshot.id,device.id,"HAD1|$token")
        }.onSuccess{snapshot->
            qrStudentId=snapshot.studentId;qrLectureId=snapshot.lectureId;qrDeviceId=snapshot.deviceId;_qrPayload.value=snapshot.payload
            qrExpiryJob?.cancel();qrExpiryJob=viewModelScope.launch{delay(30_000);if(_qrPayload.value==snapshot.payload)clearQrPayload()}
        }.onFailure{_message.value=LocalAccessRules.safeErrorCode(it.message,"QR_TOKEN_FAILED");clearQrPayload()}
    }}

    fun startAdvertising(){viewModelScope.launch{
        if(StudentPresenceService.active.value)return@launch
        runCatching{
            if(dao.isFeatureEnabled("BLE_ATTENDANCE")!=true)error("BLE_ATTENDANCE_DISABLED")
            val (_,s)=currentStudentContext()
            val lectureSnapshot=activeLecture.value?:error("NO_ACTIVE_LECTURE")
            if(lectureSnapshot.groupId!=s.groupId)error("LECTURE_OUT_OF_SCOPE")
            if(dao.getAttendanceRecord(lectureSnapshot.id,s.id)==null)error("STUDENT_NOT_IN_ATTENDANCE_ROSTER")
            val device=activeDeviceFor(s)
            BlePlatformReadiness.advertiseError(appContext)?.let{error(it)}
            Triple(s.id,device.id,lectureSnapshot.id)
        }.onSuccess{(studentId,deviceId,lectureId)->
            val intent=Intent(appContext,StudentPresenceService::class.java)
                .putExtra(StudentPresenceService.EXTRA_STUDENT_ID,studentId)
                .putExtra(StudentPresenceService.EXTRA_DEVICE_ID,deviceId)
                .putExtra(StudentPresenceService.EXTRA_LECTURE_ID,lectureId)
            if(runCatching{ContextCompat.startForegroundService(appContext,intent)}.isFailure)_message.value="PRESENCE_SERVICE_START_FAILED"
        }.onFailure{_message.value=LocalAccessRules.safeErrorCode(it.message,"PRESENCE_SERVICE_START_FAILED")}
    }}

    private suspend fun currentStudentContext():Pair<String,StudentEntity>{
        val actor=container.preferences.userId.first()?:error("LOCAL_SESSION_REQUIRED")
        val user=dao.getUserById(actor)?:error("LOCAL_SESSION_INVALID")
        val primaryRole=LocalAccessRules.primaryRole(dao.getRoleNames(actor))
        val linked=if(user.isActive)dao.getStudentForUser(actor) else null
        if(!user.isActive)error("LOCAL_SESSION_INVALID")
        if(primaryRole!="Student")error("STUDENT_ROLE_REQUIRED")
        if(linked==null)error("STUDENT_PROFILE_NOT_LINKED")
        if(!StudentDeviceSessionRules.usable(true,true,primaryRole,true,linked.status==StudentStatus.ACTIVE))error("STUDENT_NOT_ACTIVE")
        return actor to linked
    }

    private suspend fun activeDeviceFor(student:StudentEntity):StudentDeviceEntity{
        val active=dao.getActiveDevicesForStudent(student.id)
        val id=DeviceActiveInvariantRules.activeDeviceIdOrNull(DeviceActiveState(active.map{it.id},student.registeredDeviceId))?:error("ACTIVE_DEVICE_STATE_INVALID")
        return active.single{it.id==id}
    }

    private fun clearPairingPayload(){pairingExpiryJob?.cancel();pairingExpiryJob=null;pairingStudentId=null;_pairingPayload.value=null}
    private fun clearQrPayload(){qrExpiryJob?.cancel();qrExpiryJob=null;qrStudentId=null;qrLectureId=null;qrDeviceId=null;_qrPayload.value=null}
    private fun stopAdvertising(clearRuntimeError:Boolean){appContext.stopService(Intent(appContext,StudentPresenceService::class.java));if(clearRuntimeError)StudentPresenceService.clearRuntimeError()}
    fun stopAdvertising(){stopAdvertising(clearRuntimeError=true)}
    fun clearMessage(){_message.value=null}

    private data class DeviceUiContext(val student:StudentEntity?,val lecture:LectureEntity?,val devices:List<StudentDeviceEntity>,val bleEnabled:Boolean)
    private data class QrSnapshot(val studentId:String,val lectureId:String,val deviceId:String,val payload:String)
}
