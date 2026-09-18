package com.hammam.attendai.sync

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.domain.model.AppealSyncStatus

interface BackendClient { suspend fun post(path:String, payload:String, idempotencyKey:String):BackendResult }
data class BackendResult(val ok:Boolean,val providerMessageId:String?=null,val retryable:Boolean=true,val error:String?=null,val responseBody:String?=null)

private fun decryptOrFail(ciphertext:String,decrypt:(String)->String):Result<String> = runCatching{decrypt(ciphertext)}

class SyncProcessor(private val db:HammamDatabase,private val backend:BackendClient,private val decrypt:(String)->String){
    suspend fun process():Boolean{
        var all=true
        for(job in db.coreDao().pendingSync()){
            val payload=decryptOrFail(job.payloadCiphertext,decrypt).getOrNull()
            if(payload==null){
                db.coreDao().updateSync(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="LOCAL_DECRYPT_FAILED"));all=false;continue
            }
            val r=backend.post("/api/v1/sync/push",payload,job.idempotencyKey)
            val attemptedAt=System.currentTimeMillis();val attempts=job.retryCount+if(r.ok)0 else 1
            val status=when{r.ok->QueueStatus.SENT;!r.retryable||attempts>=5->QueueStatus.NEEDS_MANUAL_REVIEW;else->QueueStatus.FAILED}
            db.coreDao().updateSync(job.copy(status=status,retryCount=attempts,lastAttempt=attemptedAt,error=r.error))
            if(job.entityType=="AttendanceAppeal") db.coreDao().updateAppealSyncStatus(job.entityId,if(r.ok)AppealSyncStatus.SYNCED else AppealSyncStatus.SYNC_FAILED,attemptedAt)
            if(!r.ok&&r.retryable&&attempts<5)all=false
        }
        return all
    }
}

class NotificationProcessor(private val db:HammamDatabase,private val backend:BackendClient,private val decrypt:(String)->String){
    suspend fun process():Boolean{
        var all=true
        val now=System.currentTimeMillis()
        for(job in db.coreDao().pendingNotifications(now)){
            val path=when(job.channel.uppercase()){ "WHATSAPP"->"/api/v1/notifications/whatsapp"; "EMAIL"->"/api/v1/notifications/email"; else->null }
            if(path==null){db.coreDao().updateNotification(job.copy(status=QueueStatus.CANCELLED,error="Unsupported remote channel"));continue}
            val payload=decryptOrFail(job.payloadCiphertext,decrypt).getOrNull()
            if(payload==null){
                db.coreDao().updateNotification(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="LOCAL_DECRYPT_FAILED"));all=false;continue
            }
            val r=backend.post(path,payload,job.deduplicationKey);val attempts=job.retryCount+if(r.ok)0 else 1
            val status=when{r.ok->QueueStatus.SENT;!r.retryable||attempts>=5->QueueStatus.NEEDS_MANUAL_REVIEW;else->QueueStatus.FAILED}
            db.coreDao().updateNotification(job.copy(status=status,sentAt=if(r.ok)now else null,retryCount=attempts,error=r.error))
            if(!r.ok&&r.retryable&&attempts<5)all=false
        }
        return all
    }
}
