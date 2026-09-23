package com.hammam.attendai.appeals

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hammam.attendai.R

class AppealLocalNotifier(private val context:Context){
    companion object { private const val CHANNEL_ID="attendance_appeals" }

    fun notifyReviewed(appealId:String,accepted:Boolean):Boolean{
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false
        val nm=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,context.getString(R.string.attendance_appeals),NotificationManager.IMPORTANCE_DEFAULT))
        val text=context.getString(if(accepted)R.string.appeal_notification_accepted else R.string.appeal_notification_rejected)
        val n=NotificationCompat.Builder(context,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_attendance)
            .setContentTitle(context.getString(R.string.attendance_appeal))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(appealId.hashCode(),n)
        return true
    }
}
