package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.local.dao.AppealReviewRow
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.security.AuthorizationRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class AttendanceAppealContext(
    val student: StudentEntity,
    val attendanceRecord: AttendanceRecordEntity,
    val lecture: LectureEntity,
    val subject: SubjectEntity,
    val teacher: TeacherEntity?,
)

sealed interface AppealOperationResult {
    data class Success(val appealId:String):AppealOperationResult
    data class Failure(val code:String):AppealOperationResult
}

class AttendanceAppealRepository(
    private val db:HammamDatabase,
    private val cipher:KeystoreCipher,
    private val authorization:AuthorizationRepository?=null,
) {
    private val dao=db.coreDao()

    fun observeForStudent(studentId:String):Flow<List<AttendanceAppealEntity>> = dao.observeStudentAppeals(studentId)
    fun observeByStatus(status:AppealStatus?):Flow<List<AttendanceAppealEntity>> = dao.observeAppeals(status)
    fun observeReviewRows(status:AppealStatus?):Flow<List<AppealReviewRow>> = dao.observeAppealReviewRows(status)
    fun observeReviewRows(userId:String,status:AppealStatus?):Flow<List<AppealReviewRow>> = dao.observeScopedAppealReviewRows(userId,status)
    fun observeForRecord(recordId:String):Flow<List<AttendanceAppealEntity>> = dao.observeAppealsForRecord(recordId)
    fun observeStudentRecords(studentId:String):Flow<List<AttendanceRecordEntity>> = dao.observeStudentRecords(studentId)

    suspend fun loadContext(recordId:String):AttendanceAppealContext? {
        val record=dao.getAttendanceRecord(recordId)?:return null
        val student=dao.getStudentById(record.studentId)?:return null
        val lecture=dao.getLectureById(record.lectureId)?:return null
        val subject=dao.getSubjectById(lecture.subjectId)?:return null
        val teacher=dao.getTeacherById(lecture.teacherId)
        return AttendanceAppealContext(student,record,lecture,subject,teacher)
    }

    suspend fun submit(
        attendanceRecordId:String,
        reasonType:String,
        description:String,
        attachmentLocalUri:String?,
        actorId:String?,
    ):AppealOperationResult = db.withTransaction {
        if(description.isBlank()) return@withTransaction AppealOperationResult.Failure("DESCRIPTION_REQUIRED")
        val context=loadContext(attendanceRecordId)?:return@withTransaction AppealOperationResult.Failure("ATTENDANCE_CONTEXT_NOT_FOUND")
        if(actorId!=null && authorization!=null && (!authorization.hasPermission(actorId,"SUBMIT_APPEAL") || !authorization.canAccessStudent(actorId,context.student.id))) return@withTransaction AppealOperationResult.Failure("APPEAL_SCOPE_PERMISSION_REQUIRED")
        val now=System.currentTimeMillis()
        val cloudSync=dao.isFeatureEnabled("CLOUD_SYNC") == true
        val appeal=AttendanceAppealEntity(
            id=UUID.randomUUID().toString(),
            studentId=context.student.id,
            attendanceRecordId=context.attendanceRecord.id,
            lectureId=context.lecture.id,
            subjectId=context.subject.id,
            reasonType=reasonType.trim().ifBlank{"OTHER"},
            description=description.trim(),
            attachmentLocalUri=attachmentLocalUri,
            attachmentRemoteUrl=null,
            status=AppealStatus.PENDING,
            submittedAt=now,
            updatedAt=now,
            reviewedBy=null,
            reviewedAt=null,
            decisionNote=null,
            syncStatus=if(cloudSync) AppealSyncStatus.PENDING_SYNC else AppealSyncStatus.LOCAL_ONLY,
            version=1,
        )
        dao.insertAppeal(appeal)
        dao.insertAudit(AuditLogEntity(
            id=UUID.randomUUID().toString(), actorId=actorId ?: context.student.id,
            action="ATTENDANCE_APPEAL_SUBMITTED", entityType="AttendanceAppeal", entityId=appeal.id,
            oldData=null, newData="{\"status\":\"PENDING\",\"attendanceRecordId\":\"${appeal.attendanceRecordId}\"}",
            reason=appeal.description.take(500), timestamp=now
        ))
        if(cloudSync) enqueueAppealSync(appeal,"UPSERT",now)
        AppealOperationResult.Success(appeal.id)
    }

    suspend fun review(
        appealId:String,
        accept:Boolean,
        decisionNote:String,
        reviewerId:String,
        newStatus:FinalAttendanceStatus?,
        newAttendancePercentage:Double?,
        canEditFrozen:Boolean,
    ):AppealOperationResult = db.withTransaction {
        if(decisionNote.isBlank()) return@withTransaction AppealOperationResult.Failure("DECISION_NOTE_REQUIRED")
        val appeal=dao.getAppeal(appealId)?:return@withTransaction AppealOperationResult.Failure("APPEAL_NOT_FOUND")
        if(appeal.status != AppealStatus.PENDING) return@withTransaction AppealOperationResult.Failure("APPEAL_ALREADY_REVIEWED")
        val record=dao.getAttendanceRecord(appeal.attendanceRecordId)?:return@withTransaction AppealOperationResult.Failure("ATTENDANCE_RECORD_NOT_FOUND")
        val lecture=dao.getLectureById(appeal.lectureId)?:return@withTransaction AppealOperationResult.Failure("LECTURE_NOT_FOUND")
        if(authorization!=null && (!authorization.hasPermission(reviewerId,"REVIEW_APPEALS") || !authorization.canAccessStudent(reviewerId,appeal.studentId))) return@withTransaction AppealOperationResult.Failure("APPEAL_SCOPE_PERMISSION_REQUIRED")
        if(lecture.status == LectureStatus.FROZEN && !canEditFrozen) return@withTransaction AppealOperationResult.Failure("FROZEN_PERMISSION_REQUIRED")
        if(accept && newStatus==null) return@withTransaction AppealOperationResult.Failure("NEW_STATUS_REQUIRED")
        if(newAttendancePercentage!=null && newAttendancePercentage !in 0.0..1.0) return@withTransaction AppealOperationResult.Failure("INVALID_PERCENTAGE")

        val now=System.currentTimeMillis()
        val cloudSync=dao.isFeatureEnabled("CLOUD_SYNC") == true
        val reviewed=appeal.copy(
            status=if(accept)AppealStatus.ACCEPTED else AppealStatus.REJECTED,
            updatedAt=now, reviewedBy=reviewerId, reviewedAt=now, decisionNote=decisionNote.trim(),
            syncStatus=if(cloudSync)AppealSyncStatus.PENDING_SYNC else AppealSyncStatus.LOCAL_ONLY,
            version=appeal.version+1,
        )
        dao.updateAppeal(reviewed)

        if(accept) {
            val updatedRecord=record.copy(
                finalStatus=newStatus!!,
                attendancePercentage=newAttendancePercentage ?: record.attendancePercentage,
                notes=listOfNotNull(record.notes,"Appeal ${appeal.id} accepted: ${decisionNote.trim()}").joinToString("\n"),
                updatedAt=now,
                version=record.version+1,
            )
            dao.upsertRecord(updatedRecord)
            dao.insertAudit(AuditLogEntity(
                id=UUID.randomUUID().toString(), actorId=reviewerId,
                action="ATTENDANCE_CHANGED_AFTER_APPEAL", entityType="AttendanceRecord", entityId=record.id,
                oldData="{\"status\":\"${record.finalStatus.name}\",\"percentage\":${record.attendancePercentage}}",
                newData="{\"status\":\"${updatedRecord.finalStatus.name}\",\"percentage\":${updatedRecord.attendancePercentage},\"appealId\":\"${appeal.id}\"}",
                reason=decisionNote.trim(), timestamp=now
            ))
        }
        dao.insertAudit(AuditLogEntity(
            id=UUID.randomUUID().toString(), actorId=reviewerId,
            action=if(accept)"ATTENDANCE_APPEAL_ACCEPTED" else "ATTENDANCE_APPEAL_REJECTED",
            entityType="AttendanceAppeal", entityId=appeal.id,
            oldData="{\"status\":\"${appeal.status.name}\"}", newData="{\"status\":\"${reviewed.status.name}\"}",
            reason=decisionNote.trim(), timestamp=now
        ))

        enqueueReviewNotification(reviewed, now)
        if(cloudSync) enqueueAppealSync(reviewed,"UPSERT",now)
        AppealOperationResult.Success(appeal.id)
    }

    suspend fun cancel(appealId:String,studentId:String):AppealOperationResult = db.withTransaction {
        val appeal=dao.getAppeal(appealId)?:return@withTransaction AppealOperationResult.Failure("APPEAL_NOT_FOUND")
        if(appeal.studentId!=studentId || appeal.status!=AppealStatus.PENDING) return@withTransaction AppealOperationResult.Failure("CANNOT_CANCEL")
        val now=System.currentTimeMillis(); val cloudSync=dao.isFeatureEnabled("CLOUD_SYNC") == true
        val updated=appeal.copy(status=AppealStatus.CANCELLED,updatedAt=now,syncStatus=if(cloudSync)AppealSyncStatus.PENDING_SYNC else AppealSyncStatus.LOCAL_ONLY,version=appeal.version+1)
        dao.updateAppeal(updated)
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),studentId,"ATTENDANCE_APPEAL_CANCELLED","AttendanceAppeal",appeal.id,null,"{\"status\":\"CANCELLED\"}",null,now))
        if(cloudSync)enqueueAppealSync(updated,"UPSERT",now)
        AppealOperationResult.Success(appeal.id)
    }

    private suspend fun enqueueAppealSync(appeal:AttendanceAppealEntity,operation:String,now:Long){
        val payload="""{"id":"${appeal.id}","studentId":"${appeal.studentId}","attendanceRecordId":"${appeal.attendanceRecordId}","status":"${appeal.status.name}","version":${appeal.version}}"""
        dao.enqueueSync(SyncQueueEntity(
            id=UUID.randomUUID().toString(), entityType="AttendanceAppeal", entityId=appeal.id, operation=operation,
            payloadCiphertext=cipher.encrypt(payload), createdAt=now,lastAttempt=null,retryCount=0,status=QueueStatus.PENDING,error=null,
            idempotencyKey="appeal:${appeal.id}:${appeal.version}",version=1
        ))
    }

    private suspend fun enqueueReviewNotification(appeal:AttendanceAppealEntity,now:Long){
        val payload="""{"appealId":"${appeal.id}","status":"${appeal.status.name}"}"""
        dao.enqueueNotification(NotificationEntity(
            id=UUID.randomUUID().toString(),recipientType="STUDENT",recipientId=appeal.studentId,channel="IN_APP",
            template="ATTENDANCE_APPEAL_REVIEWED",payloadCiphertext=cipher.encrypt(payload),status=QueueStatus.SENT,
            scheduledAt=now,sentAt=now,retryCount=0,error=null,deduplicationKey="appeal-review-inapp:${appeal.id}:${appeal.version}",version=1
        ))
        if(dao.isFeatureEnabled("WHATSAPP")==true){
            dao.enqueueNotification(NotificationEntity(
                id=UUID.randomUUID().toString(),recipientType="STUDENT",recipientId=appeal.studentId,channel="WHATSAPP",
                template="ATTENDANCE_APPEAL_REVIEWED",payloadCiphertext=cipher.encrypt(payload),status=QueueStatus.PENDING,
                scheduledAt=now,sentAt=null,retryCount=0,error=null,deduplicationKey="appeal-review-wa:${appeal.id}:${appeal.version}",version=1
            ))
        }
    }
}
