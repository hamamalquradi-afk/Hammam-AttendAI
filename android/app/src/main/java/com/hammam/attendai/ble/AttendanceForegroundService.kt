package com.hammam.attendai.ble

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.MainActivity
import com.hammam.attendai.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

class AttendanceForegroundService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var sessionJob:Job?=null
    private var restartJob:Job?=null

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
            scope.launch{runCatching{(application as HammamAttendAiApplication).container.attendance.markDetectorIssue("START_FOREGROUND_FAILED")}}
            stopSelf();return START_NOT_STICKY
        }
        val restored=intent==null || intent.getBooleanExtra(EXTRA_RESTORED,false)
        restartJob?.cancel()
        restartJob=scope.launch{
            val previous=sessionJob
            previous?.cancelAndJoin()
            if(!isActive)return@launch
            val job=launch{
                try{runAttendanceLoop(restored=restored)}
                catch(e:CancellationException){throw e}
                catch(e:Exception){
                    android.util.Log.e("AttendanceForegroundService","ATTENDANCE_LOOP_FAILED:${e.javaClass.simpleName}")
                    runCatching{(application as HammamAttendAiApplication).container.attendance.markDetectorIssue("BLE_RUNTIME_FAILED")}
                    runCatching{(application as HammamAttendAiApplication).container.bleDetector.fail("BLE_RUNTIME_FAILED")}
                    stopSelf()
                }
            }
            sessionJob=job
        }
        return START_STICKY
    }

    private suspend fun runAttendanceLoop(restored:Boolean)=coroutineScope {
        val operationScope=this
        val container=(application as HammamAttendAiApplication).container
        val dao=container.database.coreDao()
        val session=dao.getActiveSession() ?: run{stopSelf();return@coroutineScope}
        if(dao.isFeatureEnabled("BLE_ATTENDANCE")!=true){container.attendance.markDetectorIssue("BLE_ATTENDANCE_DISABLED");stopSelf();return@coroutineScope}
        if(restored && !session.restoredAfterCrash) dao.updateSession(session.copy(restoredAfterCrash=true,version=session.version+1))
        container.bleDetector.start(session.id)
        when(val initialState=container.bleDetector.state.first()){
            is DetectorState.Error->{
                container.attendance.markDetectorIssue(initialState.code)
                container.bleDetector.stop()
                stopSelf()
                return@coroutineScope
            }
            DetectorState.Active->{
                val current=dao.getSessionById(session.id)
                if(current?.activeDetector?.startsWith("BLE_ERROR:")==true) dao.updateSession(current.copy(activeDetector="BLE",version=current.version+1))
            }
            else->{ }
        }
        val stateCollector=launch{
            container.bleDetector.state.collect { detectorState ->
                if(detectorState is DetectorState.Error){
                    container.attendance.markDetectorIssue(detectorState.code)
                    stopSelf()
                    operationScope.cancel(CancellationException("BLE_DETECTOR_ERROR"))
                }
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
                val current=dao.getSessionById(session.id)
                if(current?.status!="ACTIVE"){stopSelf();operationScope.cancel();break}
                if(dao.isFeatureEnabled("BLE_ATTENDANCE")!=true){
                    container.attendance.markDetectorIssue("BLE_ATTENDANCE_DISABLED")
                    container.bleDetector.fail("BLE_ATTENDANCE_DISABLED")
                    continue
                }
                val readiness=BlePlatformReadiness.scanError(this@AttendanceForegroundService)
                if(readiness!=null){
                    container.attendance.markDetectorIssue(readiness)
                    container.bleDetector.fail(readiness)
                    continue
                }
                container.attendance.closeExpiredPresence(session.id)
            }
        }
        try{joinAll(stateCollector,collector,maintenance)}finally{withContext(NonCancellable){container.bleDetector.stop()}}
    }

    override fun onDestroy(){restartJob?.cancel();sessionJob?.cancel();ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null

    companion object { const val EXTRA_RESTORED="restored" }
}
