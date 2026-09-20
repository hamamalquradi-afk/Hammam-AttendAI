package com.hammam.attendai.sync

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.security.KeystoreCipher
import org.json.JSONObject

/** PHASE 5G outbox for academic graph entities. Runtime/security-only fields are excluded. */
class AcademicGraphSyncOutbox(
    private val db:HammamDatabase,
    private val encrypt:(String)->String,
){
    constructor(db:HammamDatabase,cipher:KeystoreCipher):this(db,cipher::encrypt)
    private val dao=db.coreDao()

    suspend fun backfillExistingIfEnabled(){
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        dao.getStudentsOnce().forEach{recordStudent(it,legacy=true)}
        dao.getSubjectsOnce().forEach{recordSubject(it,legacy=true)}
        dao.getLecturesOnce().forEach{recordLecture(it,legacy=true)}
        dao.getAttendanceRecordsOnce().forEach{recordAttendanceRecord(it,legacy=true)}
    }

    suspend fun recordStudent(v:StudentEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=
        record("Student",v.id,AcademicGraphSyncCodec.student(v),now,legacy,v.createdAt,if(legacy)v.createdAt else now)

    suspend fun recordSubject(v:SubjectEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=
        record("Subject",v.id,AcademicGraphSyncCodec.subject(v),now,legacy,if(legacy)1L else now,if(legacy)1L else now)

    suspend fun recordLecture(v:LectureEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=
        record("Lecture",v.id,AcademicGraphSyncCodec.lecture(v),now,legacy,v.createdAt,if(legacy)v.createdAt else now)

    suspend fun recordAttendanceRecord(v:AttendanceRecordEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=
        record("AttendanceRecord",v.id,AcademicGraphSyncCodec.attendanceRecord(v),now,legacy,v.createdAt,if(legacy)v.createdAt else now)

    suspend fun acceptRemote(entityType:String,entityId:String,remoteVersion:Long,remoteUpdatedAt:Long,payload:JSONObject,serverVersion:Long){
        val fingerprint=HierarchySyncCodec.fingerprintPayload(payload)
        val existing=dao.getSyncEntityMetadata(entityType,entityId)
        val firstSeen=existing?.firstSeenAt ?: remoteUpdatedAt
        val metadata=SyncEntityMetadataEntity(entityType,entityId,remoteVersion,firstSeen,remoteUpdatedAt,fingerprint,serverVersion)
        if(existing==null)dao.insertSyncEntityMetadata(metadata) else dao.updateSyncEntityMetadata(metadata)
    }

    private suspend fun record(entityType:String,entityId:String,content:JSONObject,now:Long,legacy:Boolean,sourceFirstSeenAt:Long?=null,sourceUpdatedAt:Long?=null){
        val fingerprint=HierarchySyncCodec.fingerprintPayload(content)
        val existing=dao.getSyncEntityMetadata(entityType,entityId)
        val metadata=AcademicHierarchyMetadataRules.evolve(existing,entityType,entityId,fingerprint,now,legacy,sourceFirstSeenAt,sourceUpdatedAt)
        if(existing==null)dao.insertSyncEntityMetadata(metadata) else if(metadata!=existing)dao.updateSyncEntityMetadata(metadata)
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        val payload=HierarchySyncCodec.withMetadata(content,metadata.localVersion,metadata.updatedAt)
        val key=AcademicHierarchyMetadataRules.idempotencyKey(entityType,entityId,metadata.localVersion)
        dao.enqueueSync(SyncQueueEntity(
            id=AcademicHierarchyMetadataRules.queueId(key),entityType=entityType,entityId=entityId,operation="UPSERT",
            payloadCiphertext=encrypt(payload.toString()),createdAt=metadata.updatedAt,lastAttempt=null,retryCount=0,status=QueueStatus.PENDING,error=null,
            idempotencyKey=key,version=1
        ))
    }
}

internal object AcademicGraphSyncCodec {
    fun student(v:StudentEntity)=JSONObject()
        .put("id",v.id).put("universityNumber",v.universityNumber?:JSONObject.NULL)
        .put("fullName",v.fullName).put("normalizedName",v.normalizedName)
        .put("phoneNumber",v.phoneNumber?:JSONObject.NULL).put("whatsappNumber",v.whatsappNumber?:JSONObject.NULL)
        .put("levelId",v.levelId?:JSONObject.NULL).put("batchId",v.batchId?:JSONObject.NULL)
        .put("sectionId",v.sectionId?:JSONObject.NULL).put("groupId",v.groupId?:JSONObject.NULL)
        .put("status",v.status.name).put("createdAt",v.createdAt).put("archivedAt",v.archivedAt?:JSONObject.NULL)

    fun subject(v:SubjectEntity)=JSONObject()
        .put("id",v.id).put("code",v.code).put("name",v.name)
        .put("teacherId",v.teacherId?:JSONObject.NULL).put("levelId",v.levelId).put("semesterId",v.semesterId).put("groupId",v.groupId)
        .put("attendancePolicyId",v.attendancePolicyId?:JSONObject.NULL).put("status",v.status).put("archivedAt",v.archivedAt?:JSONObject.NULL)

    fun lecture(v:LectureEntity)=JSONObject()
        .put("id",v.id).put("subjectId",v.subjectId).put("teacherId",v.teacherId).put("semesterId",v.semesterId).put("groupId",v.groupId)
        .put("scheduledStart",v.scheduledStart).put("scheduledEnd",v.scheduledEnd).put("actualStart",v.actualStart?:JSONObject.NULL).put("actualEnd",v.actualEnd?:JSONObject.NULL)
        .put("room",v.room?:JSONObject.NULL).put("status",v.status.name).put("attendancePolicySnapshotJson",v.attendancePolicySnapshotJson).put("createdAt",v.createdAt)

    fun attendanceRecord(v:AttendanceRecordEntity)=JSONObject()
        .put("id",v.id).put("lectureId",v.lectureId).put("studentId",v.studentId).put("firstSeenAt",v.firstSeenAt?:JSONObject.NULL).put("lastSeenAt",v.lastSeenAt?:JSONObject.NULL)
        .put("verifiedPresenceSeconds",v.verifiedPresenceSeconds).put("lectureDurationSeconds",v.lectureDurationSeconds).put("attendancePercentage",v.attendancePercentage)
        .put("lateMinutes",v.lateMinutes).put("earlyLeaveMinutes",v.earlyLeaveMinutes).put("confidenceScore",v.confidenceScore).put("finalStatus",v.finalStatus.name)
        .put("approvalStatus",v.approvalStatus.name).put("source",v.source.name).put("notes",v.notes?:JSONObject.NULL).put("createdAt",v.createdAt)
}
