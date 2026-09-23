package com.hammam.attendai.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkOrchestrator {
    fun ensure(context:Context){
        val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val sync=PeriodicWorkRequestBuilder<SyncWorker>(1,TimeUnit.HOURS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
        val notifications=PeriodicWorkRequestBuilder<NotificationWorker>(1,TimeUnit.HOURS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
        val reports=PeriodicWorkRequestBuilder<ReportWorker>(1,TimeUnit.HOURS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
        val lectures=PeriodicWorkRequestBuilder<LectureMaterializationWorker>(6,TimeUnit.HOURS).build()
        val timetableReminder=PeriodicWorkRequestBuilder<TimetableReminderWorker>(24,TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hammam_sync",ExistingPeriodicWorkPolicy.KEEP,sync)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hammam_notifications",ExistingPeriodicWorkPolicy.KEEP,notifications)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hammam_reports",ExistingPeriodicWorkPolicy.KEEP,reports)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hammam_lecture_materialization",ExistingPeriodicWorkPolicy.KEEP,lectures)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("hammam_timetable_reminder",ExistingPeriodicWorkPolicy.KEEP,timetableReminder)
    }
    fun kickNetworkQueues(context:Context){
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val sync=OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        val notifications=OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("hammam_sync_now",ExistingWorkPolicy.KEEP,sync)
        WorkManager.getInstance(context).enqueueUniqueWork("hammam_notifications_now",ExistingWorkPolicy.KEEP,notifications)
    }
    fun kickReports(context:Context){
        // Local report generation must remain available offline; delivery failures are retried by ReportProcessor.
        val req=OneTimeWorkRequestBuilder<ReportWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("hammam_reports_now",ExistingWorkPolicy.KEEP,req)
    }
}
