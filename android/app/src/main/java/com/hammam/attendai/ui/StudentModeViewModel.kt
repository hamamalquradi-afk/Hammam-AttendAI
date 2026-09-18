package com.hammam.attendai.ui

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.ble.StudentPresenceService
import com.hammam.attendai.data.local.entity.*
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

    val student:StateFlow<StudentEntity?> = container.preferences.userId.flatMapLatest{id->flow{emit(id?.let{dao.getStudentForUser(it)})}}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    val devices:StateFlow<List<StudentDeviceEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeStudentDevices(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val activeLecture:StateFlow<LectureEntity?> = student.flatMapLatest{s->s?.groupId?.let{dao.observeActiveLectureForGroup(it)}?:flowOf(null)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    val records:StateFlow<List<AttendanceRecordEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeStudentRecords(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val replacementRequests:StateFlow<List<DeviceReplacementRequestEntity>> = student.flatMapLatest{s->if(s==null)flowOf(emptyList()) else dao.observeDeviceReplacementRequests(s.id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())

    init{viewModelScope.launch{activeLecture.collect{if(it==null&&StudentPresenceService.active.value)stopAdvertising()}}}

    fun enrollInitial(){viewModelScope.launch{
        val s=student.value?:run{_message.value="STUDENT_PROFILE_NOT_LINKED";return@launch};val actor=container.preferences.userId.first()?:return@launch
        runCatching{container.devices.createPairingOffer(s.id,actor)}.onSuccess{offer->_pairingPayload.value=offer.payload;_message.value="DEVICE_PAIRING_READY"}.onFailure{_message.value=it.message?:"DEVICE_ENROLLMENT_FAILED"}
    }}
    fun requestReplacement(){viewModelScope.launch{
        val s=student.value?:run{_message.value="STUDENT_PROFILE_NOT_LINKED";return@launch};val actor=container.preferences.userId.first()?:return@launch
        runCatching{container.devices.requestReplacement(s.id,actor)}.onSuccess{_message.value="DEVICE_REPLACEMENT_REQUESTED"}.onFailure{_message.value=it.message?:"DEVICE_REPLACEMENT_FAILED"}
    }}
    fun generateQrFallback(){viewModelScope.launch{
        val s=student.value?:run{_message.value="STUDENT_PROFILE_NOT_LINKED";return@launch};if(activeLecture.value?.groupId!=s.groupId){_message.value="NO_ACTIVE_LECTURE";return@launch}
        val device=devices.value.firstOrNull{it.status.name=="ACTIVE"}?:run{_message.value="ACTIVE_DEVICE_REQUIRED";return@launch}
        val token=container.devices.rotatingToken(device.id)?:run{_message.value="QR_TOKEN_FAILED";return@launch};_qrPayload.value="HAD1|$token"
    }}

    fun startAdvertising(){
        if(StudentPresenceService.active.value)return
        val s=student.value?:run{_message.value="STUDENT_PROFILE_NOT_LINKED";return}
        val lecture=activeLecture.value?:run{_message.value="NO_ACTIVE_LECTURE";return}
        if(lecture.groupId!=s.groupId){_message.value="LECTURE_OUT_OF_SCOPE";return}
        val device=devices.value.firstOrNull{it.status.name=="ACTIVE"}?:run{_message.value="ACTIVE_DEVICE_REQUIRED";return}
        if(Build.VERSION.SDK_INT>=31){
            if(ContextCompat.checkSelfPermission(appContext,Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(appContext,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){_message.value="BLE_PERMISSION_REQUIRED";return}
        }
        val ready=runCatching{val adapter=(appContext.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter;adapter!=null&&adapter.isEnabled&&adapter.bluetoothLeAdvertiser!=null}.getOrDefault(false)
        if(!ready){_message.value="BLE_ADVERTISE_UNAVAILABLE";return}
        val intent=Intent(appContext,StudentPresenceService::class.java)
            .putExtra(StudentPresenceService.EXTRA_STUDENT_ID,s.id)
            .putExtra(StudentPresenceService.EXTRA_DEVICE_ID,device.id)
            .putExtra(StudentPresenceService.EXTRA_LECTURE_ID,lecture.id)
        if(runCatching{ContextCompat.startForegroundService(appContext,intent)}.isFailure)_message.value="PRESENCE_SERVICE_START_FAILED"
    }
    fun stopAdvertising(){appContext.stopService(Intent(appContext,StudentPresenceService::class.java))}
    fun clearMessage(){_message.value=null}
}
