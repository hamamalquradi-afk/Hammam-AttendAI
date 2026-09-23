package com.hammam.attendai.sync

import android.content.Context
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hammam.attendai.R
import com.hammam.attendai.data.local.entity.NotificationEntity
import com.hammam.attendai.domain.model.QueueStatus
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hammam.attendai.HammamAttendAiApplication

class SyncWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result = guardedWorkerResult{(applicationContext as HammamAttendAiApplication).container.syncProcessor.process()}
}
class NotificationWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result = guardedWorkerResult{(applicationContext as HammamAttendAiApplication).container.notificationProcessor.process()}
}
class ReportWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result = guardedWorkerResult{(applicationContext as HammamAttendAiApplication).container.reportProcessor.processDueJobs()}
}
class LectureMaterializationWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result = runCatching{
        (applicationContext as HammamAttendAiApplication).container.lectureScheduler.materializeNextDays()
        Result.success()
    }.getOrElse{Result.retry()}
}
class TimetableReminderWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result = runCatching{
        val container=(applicationContext as HammamAttendAiApplication).container
        val userId=container.preferences.userId.first() ?: return Result.success()
        val dao=container.database.coreDao()
        val today=LocalDate.now()
        val configuredDay=runCatching{DayOfWeek.valueOf(container.preferences.academicWeekStart.first())}.getOrDefault(DayOfWeek.MONDAY)
        val nextWeekStart=today.with(TemporalAdjusters.next(configuredDay))
        val week=nextWeekStart.toString()
        var inserted=0
        for(groupId in dao.getActiveGroupIds()){
            if(!container.authorization.hasScopedPermission(userId,"MANAGE_TIMETABLE","GROUP",groupId))continue
            if(dao.countApprovedWeeklyVersion(groupId,week)>0)continue
            val dedup="TIMETABLE_REMINDER|$groupId|$week"
            val payload=container.cipher.encrypt("{\"groupId\":\"$groupId\",\"weekStart\":\"$week\"}")
            val row=NotificationEntity(UUID.randomUUID().toString(),"USER",userId,"IN_APP","TIMETABLE_REMINDER",payload,QueueStatus.SENT,System.currentTimeMillis(),System.currentTimeMillis(),0,null,dedup,1)
            if(dao.enqueueNotification(row)>0){inserted++;showTimetableReminder()}
        }
        Result.success()
    }.getOrElse{Result.retry()}

    private fun showTimetableReminder(){
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val nm=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel="timetable_reminders"
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(channel,applicationContext.getString(R.string.timetable_reminder_channel),NotificationManager.IMPORTANCE_DEFAULT))
        val n=NotificationCompat.Builder(applicationContext,channel).setSmallIcon(R.drawable.ic_attendance).setContentTitle(applicationContext.getString(R.string.timetable_reminder_title)).setContentText(applicationContext.getString(R.string.timetable_reminder_text)).setAutoCancel(true).build()
        nm.notify((System.currentTimeMillis()/86_400_000L).toInt(),n)
    }
}
private suspend fun guardedWorkerResult(block:suspend()->Boolean):androidx.work.ListenableWorker.Result=try{
    if(block()) androidx.work.ListenableWorker.Result.success() else androidx.work.ListenableWorker.Result.retry()
}catch(e:CancellationException){throw e
}catch(e:IOException){androidx.work.ListenableWorker.Result.retry()
}catch(e:SecurityException){androidx.work.ListenableWorker.Result.failure()
}catch(e:IllegalArgumentException){androidx.work.ListenableWorker.Result.failure()
}catch(e:IllegalStateException){androidx.work.ListenableWorker.Result.failure()
}catch(e:Exception){androidx.work.ListenableWorker.Result.failure()}
