package com.hammam.attendai.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.MainActivity
import com.hammam.attendai.R
import com.hammam.attendai.domain.model.DeviceStatus
import com.hammam.attendai.domain.model.LectureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StudentPresenceService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var presenceJob:Job?=null
    private val advertiser by lazy{StudentBleAdvertiser(this)}

    override fun onCreate(){
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26){
            val channel=NotificationChannel(CHANNEL_ID,getString(R.string.app_name),NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopPresence();return START_NOT_STICKY}
        if(presenceJob?.isActive==true)return START_NOT_STICKY
        val studentId=intent?.getStringExtra(EXTRA_STUDENT_ID)
        val deviceId=intent?.getStringExtra(EXTRA_DEVICE_ID)
        val lectureId=intent?.getStringExtra(EXTRA_LECTURE_ID)
        if(studentId.isNullOrBlank()||deviceId.isNullOrBlank()||lectureId.isNullOrBlank()){
            reportError("PRESENCE_START_CONTEXT_MISSING");stopSelf();return START_NOT_STICKY
        }
        if(!startForegroundSafely()){
            reportError("PRESENCE_FOREGROUND_START_FAILED");stopSelf();return START_NOT_STICKY
        }
        presenceJob?.cancel()
        _error.value=null
        presenceJob=scope.launch{runPresenceLoop(studentId,deviceId,lectureId)}
        return START_NOT_STICKY
    }

    private fun startForegroundSafely():Boolean{
        val openApp=PendingIntent.getActivity(this,4202,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent=Intent(this,StudentPresenceService::class.java).setAction(ACTION_STOP)
        val stop=PendingIntent.getService(this,4203,stopIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_attendance)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.presence_detection_active))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0,getString(R.string.stop_presence_detection),stop)
            .build()
        val type=if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        return runCatching{ServiceCompat.startForeground(this,NOTIFICATION_ID,notification,type);true}.getOrDefault(false)
    }

    private suspend fun runPresenceLoop(studentId:String,deviceId:String,lectureId:String){
        val container=(application as HammamAttendAiApplication).container
        val dao=container.database.coreDao()
        try{
            while(scope.isActive){
                val student=dao.getStudentById(studentId)?:return reportError("STUDENT_PROFILE_NOT_LINKED")
                val device=dao.getStudentDevice(deviceId)?.takeIf{it.studentId==studentId&&it.status==DeviceStatus.ACTIVE}?:return reportError("ACTIVE_DEVICE_REQUIRED")
                val lecture=dao.getLectureById(lectureId)?.takeIf{it.status==LectureStatus.ACTIVE&&it.groupId==student.groupId}?:return reportError("NO_ACTIVE_LECTURE")
                if(!bluetoothReady())return reportError("BLE_ADVERTISE_UNAVAILABLE")
                val token=container.devices.rotatingToken(device.id)?:return reportError("BLE_TOKEN_UNAVAILABLE")
                if(!advertiser.start(token))return reportError("BLE_ADVERTISE_UNAVAILABLE")
                _active.value=true
                delay(20_000)
            }
        }finally{
            advertiser.stop();_active.value=false
            ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun bluetoothReady():Boolean{
        if(Build.VERSION.SDK_INT>=31){
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)return false
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return false
        }
        return runCatching{
            val adapter=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?:return@runCatching false
            adapter.isEnabled && adapter.bluetoothLeAdvertiser!=null
        }.getOrDefault(false)
    }

    private fun reportError(code:String){_error.value=code;_active.value=false}
    private fun stopPresence(){presenceJob?.cancel();presenceJob=null;advertiser.stop();_active.value=false;ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){presenceJob?.cancel();advertiser.stop();_active.value=false;scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null

    companion object{
        const val ACTION_STOP="com.hammam.attendai.action.STOP_STUDENT_PRESENCE"
        const val EXTRA_STUDENT_ID="student_id"
        const val EXTRA_DEVICE_ID="device_id"
        const val EXTRA_LECTURE_ID="lecture_id"
        private const val CHANNEL_ID="student_presence_active"
        private const val NOTIFICATION_ID=4202
        private val _active=MutableStateFlow(false);val active=_active.asStateFlow()
        private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    }
}
