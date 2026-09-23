package com.hammam.attendai.sync

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AttendancePolicyEntity
import com.hammam.attendai.data.local.entity.SyncQueueEntity
import com.hammam.attendai.data.local.entity.TeacherEntity
import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.security.KeystoreCipher
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** PHASE 5E outbox for root/reference entities only. No security/session data is serialized. */
class AcademicReferenceSyncOutbox(
    private val db:HammamDatabase,
    private val encrypt:(String)->String,
){
    constructor(db:HammamDatabase,cipher:KeystoreCipher):this(db,cipher::encrypt)
    private val dao=db.coreDao()

    suspend fun enqueueTeacherIfEnabled(v:TeacherEntity,now:Long=v.updatedAt){
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        val payload=JSONObject()
            .put("id",v.id).put("fullName",v.fullName).put("normalizedName",v.normalizedName)
            .put("phone",v.phone?:JSONObject.NULL).put("whatsapp",v.whatsapp?:JSONObject.NULL).put("email",v.email?:JSONObject.NULL)
            .put("preferredNotificationChannel",v.preferredNotificationChannel?:JSONObject.NULL).put("notificationsEnabled",v.notificationsEnabled)
            .put("createdAt",v.createdAt).put("updatedAt",v.updatedAt).put("archivedAt",v.archivedAt?:JSONObject.NULL).put("version",v.version)
        enqueue("Teacher",v.id,v.version,payload.toString(),now)
    }

    suspend fun enqueueAttendancePolicyIfEnabled(v:AttendancePolicyEntity,now:Long=v.updatedAt){
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        // 5E intentionally syncs only root GLOBAL policies; scoped policies require their scope entities first.
        if(v.scopeType!="GLOBAL"||v.scopeId!=null)return
        val payload=JSONObject()
            .put("id",v.id).put("name",v.name).put("scopeType",v.scopeType).put("scopeId",JSONObject.NULL)
            .put("fullAttendanceThreshold",v.fullAttendanceThreshold).put("partialAttendanceThreshold",v.partialAttendanceThreshold)
            .put("lateAfterMinutes",v.lateAfterMinutes).put("earlyLeaveThresholdMinutes",v.earlyLeaveThresholdMinutes)
            .put("absenceThreshold",v.absenceThreshold).put("temporaryMissingGraceSeconds",v.temporaryMissingGraceSeconds)
            .put("minimumPresenceVerificationSeconds",v.minimumPresenceVerificationSeconds).put("confidenceThreshold",v.confidenceThreshold)
            .put("createdAt",v.createdAt).put("updatedAt",v.updatedAt).put("version",v.version)
        enqueue("AttendancePolicy",v.id,v.version,payload.toString(),now)
    }

    suspend fun backfillExistingIfEnabled(){
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        dao.getTeachersOnce().forEach{enqueueTeacherIfEnabled(it,it.updatedAt)}
        dao.getAttendancePolicies().filter{it.scopeType=="GLOBAL"&&it.scopeId==null}.forEach{enqueueAttendancePolicyIfEnabled(it,it.updatedAt)}
    }

    private suspend fun enqueue(entityType:String,entityId:String,entityVersion:Long,payload:String,now:Long){
        val key="${entityType.lowercase()}:$entityId:$entityVersion"
        val queueId=UUID.nameUUIDFromBytes("sync:$key".toByteArray(StandardCharsets.UTF_8)).toString()
        dao.enqueueSync(SyncQueueEntity(
            id=queueId,entityType=entityType,entityId=entityId,operation="UPSERT",
            payloadCiphertext=encrypt(payload),createdAt=now,lastAttempt=null,retryCount=0,status=QueueStatus.PENDING,error=null,
            idempotencyKey=key,version=1
        ))
    }
}
