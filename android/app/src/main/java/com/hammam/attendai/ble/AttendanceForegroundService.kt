package com.hammam.attendai.ble

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.MainActivity
import com.hammam.attendai.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

class AttendanceForegroundService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var sessionJob:Job?=null

    override fun onCreate(){
        super.onCreate()
        if(android.os.Build.VERSION.SDK_INT>=26){
            val channel=NotificationChannel("attendance_active",getString(R.string.attendance),NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=NotificationCompat.Builder(this,"attendance_active").setSmallIcon(R.drawable.ic_attendance).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.presence_active)).setContentIntent(pi).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        if(runCatching{startForeground(4101,n)}.isFailure){
            scope.launch{(application as HammamAttendAiApplication).container.attendance.markDetectorIssue("START_FOREGROUND_FAILED")}
            stopSelf();return START_NOT_STICKY
        }
        sessionJob?.cancel()
        sessionJob=scope.launch{runAttendanceLoop(restored=intent==null || intent.getBooleanExtra(EXTRA_RESTORED,false))}
        return START_STICKY
    }

    private suspend fun runAttendanceLoop(restored:Boolean)=coroutineScope {
        val container=(application as HammamAttendAiApplication).container
        val session=container.database.coreDao().getActiveSession() ?: run{stopSelf();return@coroutineScope}
        if(restored && !session.restoredAfterCrash) container.database.coreDao().updateSession(session.copy(restoredAfterCrash=true,version=session.version+1))
        container.bleDetector.start(session.id)
        when(val initialState=container.bleDetector.state.first()){
            is DetectorState.Error->container.attendance.markDetectorIssue(initialState.code)
            DetectorState.Active->{
                val current=container.database.coreDao().getSessionById(session.id)
                if(current?.activeDetector?.startsWith("BLE_ERROR:")==true) container.database.coreDao().updateSession(current.copy(activeDetector="BLE",version=current.version+1))
            }
            else->{ }
        }
        val stateCollector=launch{
            container.bleDetector.state.collect { detectorState ->
                if(detectorState is DetectorState.Error) container.attendance.markDetectorIssue(detectorState.code)
            }
        }
        val collector=launch{
            container.bleDetector.observations.collect { observation ->
                val resolved=container.presenceTokenResolver.resolve(observation.rotatingId,observation.timestampMillis) ?: return@collect
                container.attendance.recordBleDetection(session.id,resolved.studentId,resolved.deviceId,observation.rssi,resolved.trusted,observation.timestampMillis)
            }
        }
        val maintenance=launch{
            while(isActive){
                delay(10_000)
                val current=container.database.coreDao().getSessionById(session.id)
                if(current?.status!="ACTIVE"){stopSelf();break}
                container.attendance.closeExpiredPresence(session.id)
            }
        }
        try{joinAll(stateCollector,collector,maintenance)}finally{container.bleDetector.stop()}
    }

    override fun onDestroy(){sessionJob?.cancel();scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null

    companion object { const val EXTRA_RESTORED="restored" }
}
