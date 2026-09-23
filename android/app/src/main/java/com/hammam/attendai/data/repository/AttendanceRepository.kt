package com.hammam.attendai.data.repository

import android.os.SystemClock
import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.attendance.AttendanceEngine
import com.hammam.attendai.domain.attendance.AntiFraudEngine
import com.hammam.attendai.domain.attendance.FraudContext
import com.hammam.attendai.domain.attendance.AttendancePolicySnapshotCodec
import com.hammam.attendai.domain.attendance.ConfidenceEngine
import com.hammam.attendai.domain.attendance.SessionClockGuard
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.NotificationDeliveryRules
import com.hammam.attendai.sync.AcademicGraphSyncOutbox
import java.util.UUID
import kotlin.math.max

sealed interface LectureActionResult {
    data class Success(val lectureId:String):LectureActionResult
    data class Failure(val reason:String):LectureActionResult
}

internal object AttendanceLifecycleRules {
    fun canCreateSession(activeSessionExists:Boolean):Boolean = !activeSessionExists

    fun isStartableLecture(lecture:LectureEntity,now:Long):Boolean =
        lecture.status in setOf(LectureStatus.READY,LectureStatus.SCHEDULED) &&
            lecture.scheduledEnd>lecture.scheduledStart && now>=lecture.scheduledStart && now<lecture.scheduledEnd

    fun recordsReadyForApproval(records:List<AttendanceRecordEntity>):Boolean =
        records.isNotEmpty() && records.none{it.finalStatus==FinalAttendanceStatus.MANUAL_REVIEW}

    fun endLectureStatus():LectureStatus = LectureStatus.NEEDS_REVIEW
    fun approvedLectureStatus(freeze:Boolean):LectureStatus = if(freeze)LectureStatus.FROZEN else LectureStatus.COMPLETED
    fun requiresManualReview(policyAvailable:Boolean,forceReview:Boolean):Boolean = !policyAvailable || forceReview
}

class AttendanceRepository(private val db:HammamDatabase,private val authorization:AuthorizationRepository?=null,private val cipher:KeystoreCipher?=null){
    private val dao=db.coreDao();private val engine=AttendanceEngine();private val confidenceEngine=ConfidenceEngine();private val antiFraud=AntiFraudEngine();private val clockGuard=SessionClockGuard({SystemClock.elapsedRealtime()});private val graphSyncOutbox=cipher?.let{AcademicGraphSyncOutbox(db,it)}

    suspend fun startNextLecture(actorId:String="local"):LectureActionResult {
        var startedSessionId:String?=null
        val result=db.withTransaction {
            if(!AttendanceLifecycleRules.canCreateSession(dao.countActiveSessions()>0))return@withTransaction LectureActionResult.Failure("ACTIVE_SESSION_EXISTS")
            val now=System.currentTimeMillis()
            val lecture=(if(authorization!=null)dao.getStartableLectureForUser(actorId,now) else dao.getStartableLecture(now))?:return@withTransaction LectureActionResult.Failure("NO_READY_LECTURE")
            if(authorization!=null && !authorization.hasScopedPermission(actorId,"START_LECTURE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("LECTURE_SCOPE_PERMISSION_REQUIRED")
            if(!AttendanceLifecycleRules.isStartableLecture(lecture,now))return@withTransaction LectureActionResult.Failure("LECTURE_OUTSIDE_START_WINDOW")
            val subject=dao.getSubjectById(lecture.subjectId)?:return@withTransaction LectureActionResult.Failure("SUBJECT_NOT_FOUND")
            if(subject.archivedAt!=null || subject.status!="ACTIVE")return@withTransaction LectureActionResult.Failure("SUBJECT_NOT_ACTIVE")
            if(subject.groupId!=lecture.groupId || subject.semesterId!=lecture.semesterId)return@withTransaction LectureActionResult.Failure("LECTURE_ACADEMIC_SCOPE_MISMATCH")
            val group=dao.getActiveGroupById(lecture.groupId)?:return@withTransaction LectureActionResult.Failure("GROUP_NOT_ACTIVE")
            val semester=dao.getSemesterById(lecture.semesterId)?:return@withTransaction LectureActionResult.Failure("SEMESTER_NOT_FOUND")
            if(semester.status!=SemesterStatus.ACTIVE)return@withTransaction LectureActionResult.Failure("SEMESTER_NOT_ACTIVE")
            val teacher=dao.getTeacherById(lecture.teacherId)?:return@withTransaction LectureActionResult.Failure("TEACHER_NOT_FOUND")
            if(teacher.archivedAt!=null)return@withTransaction LectureActionResult.Failure("TEACHER_ARCHIVED")
            val policyId=subject.attendancePolicyId?:return@withTransaction LectureActionResult.Failure("ATTENDANCE_POLICY_REQUIRED")
            val policy=dao.getAttendancePolicyById(policyId)?:return@withTransaction LectureActionResult.Failure("ATTENDANCE_POLICY_NOT_FOUND")
            val students=dao.getActiveStudentsForGroup(group.id)
            if(students.isEmpty())return@withTransaction LectureActionResult.Failure("NO_ACTIVE_STUDENTS_FOR_GROUP")
            val duration=((lecture.scheduledEnd-lecture.scheduledStart)/1000L)
            if(duration<=0)return@withTransaction LectureActionResult.Failure("LECTURE_DURATION_INVALID")
            val snapshot=AttendancePolicySnapshotCodec.encode(policy)
            val session=AttendanceSessionEntity(UUID.randomUUID().toString(),lecture.id,actorId,now,null,"ACTIVE","BLE",false,1)
            dao.insertSession(session)
            students.forEach { student ->
                if(dao.getAttendanceRecord(lecture.id,student.id)==null){
                    upsertSyncedRecord(AttendanceRecordEntity(
                        id=UUID.randomUUID().toString(),lectureId=lecture.id,studentId=student.id,firstSeenAt=null,lastSeenAt=null,
                        verifiedPresenceSeconds=0,lectureDurationSeconds=duration,attendancePercentage=0.0,lateMinutes=0,earlyLeaveMinutes=0,
                        confidenceScore=0.0,finalStatus=FinalAttendanceStatus.MANUAL_REVIEW,approvalStatus=ApprovalStatus.DRAFT,
                        source=PresenceSource.BLE,notes=null,createdAt=now,updatedAt=now,version=1
                    ))
                }
            }
            updateSyncedLecture(lecture.copy(status=LectureStatus.ACTIVE,actualStart=now,attendancePolicySnapshotJson=snapshot,updatedAt=now,version=lecture.version+1))
            dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"LECTURE_STARTED","Lecture",lecture.id,null,"{\"sessionId\":\"${session.id}\"}",null,now,authorization?.roleNames(actorId)?.firstOrNull()))
            startedSessionId=session.id
            LectureActionResult.Success(lecture.id)
        }
        if(result is LectureActionResult.Success)startedSessionId?.let{clockGuard.start(it,System.currentTimeMillis())}
        return result
    }

    suspend fun recordBleDetection(sessionId:String,studentId:String,deviceId:String,rssi:Int?,trusted:Boolean,timestamp:Long=System.currentTimeMillis()):Boolean = db.withTransaction {
        val session=dao.getSessionById(sessionId)?:return@withTransaction false
        if(session.status!="ACTIVE")return@withTransaction false
        val lecture=dao.getLectureById(session.lectureId)?.takeIf{it.status==LectureStatus.ACTIVE}?:return@withTransaction false
        val student=dao.getStudentById(studentId)?:return@withTransaction false
        if(student.status!=StudentStatus.ACTIVE || student.groupId!=lecture.groupId)return@withTransaction false
        val device=dao.getStudentDevice(deviceId)?:return@withTransaction false
        if(device.studentId!=studentId || device.status!=DeviceStatus.ACTIVE || student.registeredDeviceId!=device.id)return@withTransaction false
        val record=dao.getAttendanceRecord(lecture.id,studentId)?:return@withTransaction false
        val clockAnomaly=clockGuard.observe(sessionId,timestamp)
        val lastEvent=dao.getLastPresenceEvent(sessionId,studentId)
        if(clockAnomaly){
            dao.insertPresenceEvent(PresenceEventEntity(UUID.randomUUID().toString(),session.id,studentId,deviceId,PresenceEventType.DETECTED,lastEvent?.timestamp?:timestamp,PresenceSource.BLE,rssi,0.0,"{\"clockAnomaly\":true}",System.currentTimeMillis()))
            upsertSyncedRecord(record.copy(confidenceScore=0.0,finalStatus=FinalAttendanceStatus.MANUAL_REVIEW,notes=listOfNotNull(record.notes,"CLOCK_ANOMALY_NEEDS_REVIEW").distinct().joinToString(" | "),updatedAt=System.currentTimeMillis(),version=record.version+1))
            return@withTransaction true
        }
        if(lastEvent!=null && timestamp-lastEvent.timestamp<2_500L && lastEvent.eventType in setOf(PresenceEventType.DETECTED,PresenceEventType.REDETECTED)){
            upsertSyncedRecord(record.copy(lastSeenAt=timestamp,updatedAt=timestamp,confidenceScore=max(record.confidenceScore,if(trusted)0.75 else 0.45),version=record.version+1));return@withTransaction true
        }
        val open=dao.getOpenInterval(record.id);val prior=dao.getIntervals(record.id)
        val eventType=if(open==null && prior.isNotEmpty())PresenceEventType.REDETECTED else PresenceEventType.DETECTED
        if(open==null)dao.upsertPresenceInterval(PresenceIntervalEntity(UUID.randomUUID().toString(),record.id,session.id,studentId,timestamp,null,PresenceSource.BLE,if(trusted)0.8 else 0.45,timestamp))
        dao.insertPresenceEvent(PresenceEventEntity(UUID.randomUUID().toString(),session.id,studentId,deviceId,eventType,timestamp,PresenceSource.BLE,rssi,if(trusted)0.8 else 0.45,"{\"trustedDevice\":$trusted}",timestamp))
        upsertSyncedRecord(record.copy(firstSeenAt=record.firstSeenAt?:timestamp,lastSeenAt=timestamp,confidenceScore=max(record.confidenceScore,if(trusted)0.75 else 0.45),updatedAt=timestamp,version=record.version+1))
        true
    }

    suspend fun markDetectorIssue(code:String):Boolean = db.withTransaction {
        val session=dao.getActiveSession()?:return@withTransaction false
        if(session.status!="ACTIVE")return@withTransaction false
        val safeCode=code.replace(Regex("[^A-Z0-9_:-]"),"_").take(64)
        dao.updateSession(session.copy(activeDetector="BLE_ERROR:$safeCode",version=session.version+1))
        true
    }

    suspend fun closeExpiredPresence(sessionId:String,now:Long=System.currentTimeMillis()):Int = db.withTransaction {
        val session=dao.getSessionById(sessionId)?:return@withTransaction 0
        if(session.status!="ACTIVE")return@withTransaction 0
        val lecture=dao.getLectureById(session.lectureId)?:return@withTransaction 0
        if(clockGuard.observe(sessionId,now))return@withTransaction 0
        val policy=AttendancePolicySnapshotCodec.decode(lecture.attendancePolicySnapshotJson)?:return@withTransaction 0
        var closed=0
        dao.getLectureRecords(lecture.id).forEach { record ->
            val last=record.lastSeenAt?:return@forEach
            if(now-last < policy.temporaryMissingGraceSeconds*1000L)return@forEach
            val open=dao.getOpenInterval(record.id)?:return@forEach
            dao.upsertPresenceInterval(open.copy(endAt=last))
            dao.insertPresenceEvent(PresenceEventEntity(UUID.randomUUID().toString(),session.id,record.studentId,null,PresenceEventType.LOST,last,PresenceSource.BLE,null,record.confidenceScore,"{\"graceSeconds\":${policy.temporaryMissingGraceSeconds}}",now))
            closed++
        }
        closed
    }

    suspend fun endActiveLecture(actorId:String="local"):LectureActionResult = db.withTransaction {
        if(dao.countActiveSessions()>1)return@withTransaction LectureActionResult.Failure("ACTIVE_SESSION_CONFLICT")
        val session=dao.getActiveSession()?:return@withTransaction LectureActionResult.Failure("NO_ACTIVE_SESSION")
        val lecture=dao.getLectureById(session.lectureId)?:return@withTransaction LectureActionResult.Failure("LECTURE_NOT_FOUND")
        if(lecture.status!=LectureStatus.ACTIVE)return@withTransaction LectureActionResult.Failure("LECTURE_NOT_ACTIVE")
        if(authorization!=null && !authorization.hasScopedPermission(actorId,"END_LECTURE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("LECTURE_SCOPE_PERMISSION_REQUIRED")
        if(session.startedBy!=actorId && authorization!=null && !authorization.hasScopedPermission(actorId,"TAKE_OVER_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("NOT_ACTIVE_ATTENDANCE_HOST")
        val now=System.currentTimeMillis()
        clockGuard.observe(session.id,now);val clockAnomaly=clockGuard.isAnomalous(session.id)
        val detectorIssue=session.activeDetector.startsWith("BLE_ERROR:")
        val policy=AttendancePolicySnapshotCodec.decode(lecture.attendancePolicySnapshotJson)
        val startMs=lecture.actualStart?:return@withTransaction LectureActionResult.Failure("LECTURE_ACTUAL_START_MISSING")
        if(now<=startMs)return@withTransaction LectureActionResult.Failure("LECTURE_DURATION_INVALID")
        val endMs=now
        for(record in dao.getLectureRecords(lecture.id)){
            dao.getOpenIntervals(record.id).forEach{open->
                val closeAt=(record.lastSeenAt?:now).coerceAtMost(now).coerceAtLeast(open.startAt)
                dao.upsertPresenceInterval(open.copy(endAt=closeAt))
            }
            val dbIntervals=dao.getIntervals(record.id)
            val intervals=dbIntervals.mapNotNull{row->row.endAt?.let{end->PresenceInterval(row.startAt/1000L,end/1000L)}}
            val events=dao.getPresenceEvents(session.id,record.studentId).map{row->PresenceEvent(row.timestamp/1000L,row.eventType,row.source,row.signalStrength,row.confidence)}
            val trusted=events.any{it.confidence>=0.70};val interruption=interruptionSeconds(intervals)
            val confidence=confidenceEngine.score(events,intervals.sumOf{it.durationSeconds},interruption,trusted)
            val result=if(policy==null)null else engine.compute(AttendanceComputationInput(startMs/1000L,endMs/1000L,intervals,confidence),policy)
            val duplicateEvents=events.groupBy{Triple(it.timestampEpochSeconds,it.type,it.source)}.values.sumOf{(it.size-1).coerceAtLeast(0)}
            val overlapCount=dao.countOverlappingAttendance(record.studentId,lecture.id,startMs,endMs)
            val recentDevices=dao.countRecentDeviceRegistrations(record.studentId,now-30L*24*60*60*1000)
            val fraudFlags=antiFraud.evaluate(FraudContext(overlappingActiveLectures=overlapCount,duplicateEvents=duplicateEvents,suspiciousDeviceChanges30Days=recentDevices,impossibleTimeConflict=overlapCount>0))
            val forceReview=clockAnomaly || detectorIssue || fraudFlags.any{it.severity>=3}
            val fraudNote=fraudFlags.takeIf{it.isNotEmpty()}?.joinToString(prefix="Suspicious/Needs Review: ",separator=","){it.code}
            val clockNote=if(clockAnomaly)"CLOCK_ANOMALY_NEEDS_REVIEW" else null
            val detectorNote=if(detectorIssue)"BLE_TECHNICAL_ISSUE_NEEDS_REVIEW" else null
            val policyNote=if(policy==null)"ATTENDANCE_POLICY_SNAPSHOT_INVALID_NEEDS_REVIEW" else null
            val reviewNote=listOfNotNull(fraudNote,clockNote,detectorNote,policyNote).joinToString(" | ").ifBlank{null}
            val updated=if(result==null)record.copy(
                lectureDurationSeconds=((endMs-startMs)/1000L).coerceAtLeast(1),confidenceScore=confidence,finalStatus=FinalAttendanceStatus.MANUAL_REVIEW,notes=listOfNotNull(record.notes,reviewNote).joinToString(" | ").ifBlank{null},updatedAt=now,version=record.version+1
            ) else record.copy(
                firstSeenAt=dbIntervals.minOfOrNull{it.startAt},lastSeenAt=dbIntervals.mapNotNull{it.endAt}.maxOrNull(),
                verifiedPresenceSeconds=result.verifiedPresenceSeconds,lectureDurationSeconds=result.lectureDurationSeconds,
                attendancePercentage=result.attendancePercentage,lateMinutes=result.lateMinutes,earlyLeaveMinutes=result.earlyLeaveMinutes,
                confidenceScore=confidence,finalStatus=if(AttendanceLifecycleRules.requiresManualReview(true,forceReview))FinalAttendanceStatus.MANUAL_REVIEW else result.finalStatus,notes=listOfNotNull(record.notes,reviewNote).joinToString(" | ").ifBlank{null},updatedAt=now,version=record.version+1
            )
            upsertSyncedRecord(updated)
        }
        dao.updateSession(session.copy(status="COMPLETED",endedAt=now,version=session.version+1))
        updateSyncedLecture(lecture.copy(status=AttendanceLifecycleRules.endLectureStatus(),actualEnd=now,updatedAt=now,version=lecture.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"LECTURE_ENDED","Lecture",lecture.id,null,"{\"status\":\"NEEDS_REVIEW\"}",when{policy==null->"ATTENDANCE_POLICY_SNAPSHOT_INVALID_NEEDS_REVIEW";clockAnomaly->"CLOCK_ANOMALY_NEEDS_REVIEW";detectorIssue->"BLE_TECHNICAL_ISSUE_NEEDS_REVIEW";else->null},now,authorization?.roleNames(actorId)?.firstOrNull()))
        clockGuard.clear(session.id)
        LectureActionResult.Success(lecture.id)
    }

    suspend fun approveLecture(lectureId:String,actorId:String,freeze:Boolean=false):LectureActionResult=db.withTransaction{
        val lecture=dao.getLectureById(lectureId)?:return@withTransaction LectureActionResult.Failure("LECTURE_NOT_FOUND")
        if(authorization!=null && !authorization.hasScopedPermission(actorId,"APPROVE_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("LECTURE_SCOPE_PERMISSION_REQUIRED")
        if(freeze && authorization!=null && !authorization.hasScopedPermission(actorId,"EDIT_FROZEN_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("EDIT_FROZEN_ATTENDANCE_PERMISSION_REQUIRED")
        val existing=dao.getLectureRecords(lecture.id)
        if(lecture.status in setOf(LectureStatus.COMPLETED,LectureStatus.FROZEN) && existing.all{it.approvalStatus in setOf(ApprovalStatus.APPROVED,ApprovalStatus.FROZEN)})return@withTransaction LectureActionResult.Success(lecture.id)
        if(lecture.status!=LectureStatus.NEEDS_REVIEW)return@withTransaction LectureActionResult.Failure("LECTURE_NOT_REVIEWABLE")
        if(existing.isEmpty())return@withTransaction LectureActionResult.Failure("ATTENDANCE_RECORDS_REQUIRED")
        if(!AttendanceLifecycleRules.recordsReadyForApproval(existing))return@withTransaction LectureActionResult.Failure("ATTENDANCE_REVIEW_INCOMPLETE")
        val now=System.currentTimeMillis();val approval=if(freeze)ApprovalStatus.FROZEN else ApprovalStatus.APPROVED
        val approvedRecords=existing.map{it.copy(approvalStatus=approval,updatedAt=now,version=it.version+1)}
        approvedRecords.forEach{upsertSyncedRecord(it)}
        updateSyncedLecture(lecture.copy(status=AttendanceLifecycleRules.approvedLectureStatus(freeze),updatedAt=now,version=lecture.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,if(freeze)"LECTURE_FROZEN" else "ATTENDANCE_APPROVED","Lecture",lecture.id,null,"{\"status\":\"${if(freeze)"FROZEN" else "COMPLETED"}\"}",null,now,authorization?.roleNames(actorId)?.firstOrNull()))
        approvedRecords.filter{it.finalStatus==FinalAttendanceStatus.ABSENT}.forEach{enqueueAbsenceNotifications(it,lecture,now)}
        LectureActionResult.Success(lecture.id)
    }

    suspend fun editAttendanceRecord(recordId:String,actorId:String,status:FinalAttendanceStatus,attendancePercentage:Double,reason:String):Boolean=db.withTransaction{
        require(reason.isNotBlank()){"REASON_REQUIRED"}
        require(attendancePercentage in 0.0..1.0){"ATTENDANCE_PERCENTAGE_INVALID"}
        require(status!=FinalAttendanceStatus.MANUAL_REVIEW){"FINAL_STATUS_REQUIRED"}
        val record=dao.getAttendanceRecord(recordId)?:return@withTransaction false
        val lecture=dao.getLectureById(record.lectureId)?:return@withTransaction false
        if(authorization!=null && !authorization.hasScopedPermission(actorId,"EDIT_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction false
        if((record.approvalStatus==ApprovalStatus.FROZEN || lecture.status==LectureStatus.FROZEN) && authorization!=null && !authorization.hasScopedPermission(actorId,"EDIT_FROZEN_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction false
        if(lecture.status !in setOf(LectureStatus.NEEDS_REVIEW,LectureStatus.COMPLETED,LectureStatus.FROZEN))return@withTransaction false
        val now=System.currentTimeMillis();val pct=attendancePercentage.coerceIn(0.0,1.0)
        val updated=record.copy(
            verifiedPresenceSeconds=(record.lectureDurationSeconds*pct).toLong(),attendancePercentage=pct,finalStatus=status,
            approvalStatus=ApprovalStatus.DRAFT,source=PresenceSource.MANUAL,
            notes=listOfNotNull(record.notes,"MANUAL_OVERRIDE: ${reason.trim()}").joinToString(" | "),updatedAt=now,version=record.version+1
        )
        upsertSyncedRecord(updated)
        if(lecture.status in setOf(LectureStatus.COMPLETED,LectureStatus.FROZEN)){
            updateSyncedLecture(lecture.copy(status=LectureStatus.NEEDS_REVIEW,updatedAt=now,version=lecture.version+1))
            dao.markReportJobsStaleForLecture(lecture.teacherId,lecture.subjectId,lecture.scheduledStart,"REPORT_STALE_AFTER_ATTENDANCE_CHANGE")
        }
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"ATTENDANCE_EDITED","AttendanceRecord",record.id,"{\"status\":\"${record.finalStatus}\",\"percentage\":${record.attendancePercentage},\"approvalStatus\":\"${record.approvalStatus}\"}","{\"status\":\"$status\",\"percentage\":$pct,\"approvalStatus\":\"DRAFT\",\"lectureStatus\":\"${if(lecture.status in setOf(LectureStatus.COMPLETED,LectureStatus.FROZEN)) LectureStatus.NEEDS_REVIEW else lecture.status}\"}",reason.trim(),now,authorization?.roleNames(actorId)?.firstOrNull()))
        true
    }

    suspend fun takeOverActiveLecture(actorId:String,reason:String?=null):LectureActionResult=db.withTransaction{
        if(dao.countActiveSessions()>1)return@withTransaction LectureActionResult.Failure("ACTIVE_SESSION_CONFLICT")
        val session=dao.getActiveSession()?:return@withTransaction LectureActionResult.Failure("NO_ACTIVE_SESSION")
        if(session.status!="ACTIVE")return@withTransaction LectureActionResult.Failure("NO_ACTIVE_SESSION")
        val lecture=dao.getLectureById(session.lectureId)?:return@withTransaction LectureActionResult.Failure("LECTURE_NOT_FOUND")
        if(lecture.status!=LectureStatus.ACTIVE)return@withTransaction LectureActionResult.Failure("LECTURE_NOT_ACTIVE")
        if(session.startedBy==actorId)return@withTransaction LectureActionResult.Success(lecture.id)
        if(authorization!=null && !authorization.hasScopedPermission(actorId,"TAKE_OVER_ATTENDANCE","GROUP",lecture.groupId))return@withTransaction LectureActionResult.Failure("TAKE_OVER_PERMISSION_REQUIRED")
        val now=System.currentTimeMillis();val oldHost=session.startedBy
        dao.updateSession(session.copy(startedBy=actorId,version=session.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"HANDOVER_ATTENDANCE_HOST","AttendanceSession",session.id,"{\"host\":\"$oldHost\"}","{\"host\":\"$actorId\",\"lectureId\":\"${lecture.id}\"}",reason,now,authorization?.roleNames(actorId)?.firstOrNull()))
        LectureActionResult.Success(lecture.id)
    }

    suspend fun recordQrVerification(sessionId:String,studentId:String,deviceId:String,actorId:String,timestamp:Long=System.currentTimeMillis()):Boolean=db.withTransaction{
        val session=dao.getSessionById(sessionId)?:return@withTransaction false
        if(session.status!="ACTIVE")return@withTransaction false
        val lecture=dao.getLectureById(session.lectureId)?.takeIf{it.status==LectureStatus.ACTIVE}?:return@withTransaction false
        if(authorization!=null && !authorization.canAccessStudent(actorId,studentId))return@withTransaction false
        val student=dao.getStudentById(studentId)?:return@withTransaction false
        if(student.status!=StudentStatus.ACTIVE || student.groupId!=lecture.groupId)return@withTransaction false
        val device=dao.getStudentDevice(deviceId)?:return@withTransaction false
        if(device.studentId!=studentId || device.status!=DeviceStatus.ACTIVE || student.registeredDeviceId!=device.id)return@withTransaction false
        val record=dao.getAttendanceRecord(lecture.id,studentId)?:return@withTransaction false
        // A QR scan proves presence at one instant only. It must not create an open interval
        // that would otherwise be counted as continuous presence until lecture end.
        dao.insertPresenceEvent(PresenceEventEntity(UUID.randomUUID().toString(),session.id,studentId,deviceId,if(record.firstSeenAt==null)PresenceEventType.QR_VERIFIED else PresenceEventType.REDETECTED,timestamp,PresenceSource.QR,null,0.95,"{\"timeBound\":true,\"pointVerification\":true}",timestamp))
        upsertSyncedRecord(record.copy(firstSeenAt=record.firstSeenAt?:timestamp,lastSeenAt=timestamp,source=PresenceSource.QR,confidenceScore=max(record.confidenceScore,0.95),updatedAt=timestamp,version=record.version+1))
        true
    }

    private suspend fun upsertSyncedRecord(v:AttendanceRecordEntity){dao.upsertRecord(v);graphSyncOutbox?.recordAttendanceRecord(v,v.updatedAt)}
    private suspend fun updateSyncedLecture(v:LectureEntity){dao.updateLecture(v);graphSyncOutbox?.recordLecture(v,v.updatedAt)}

    private suspend fun enqueueAbsenceNotifications(record:AttendanceRecordEntity,lecture:LectureEntity,now:Long){
        val c=cipher?:return
        val payload="""{"studentId":"${record.studentId}","lectureId":"${lecture.id}","subjectId":"${lecture.subjectId}","status":"ABSENT"}"""
        dao.enqueueNotification(NotificationEntity(UUID.randomUUID().toString(),"STUDENT",record.studentId,"IN_APP","ATTENDANCE_ABSENT",c.encrypt(payload),QueueStatus.SENT,now,now,0,null,"absence-inapp:${record.studentId}:${lecture.id}",1))
        if(dao.isFeatureEnabled("WHATSAPP")==true){
            val student=dao.getStudentById(record.studentId)
            val recipient=student?.takeIf{it.archivedAt==null && it.status==StudentStatus.ACTIVE}?.let{NotificationDeliveryRules.normalizePhone(it.whatsappNumber?:it.phoneNumber)}
            if(recipient!=null)dao.enqueueNotification(NotificationEntity(UUID.randomUUID().toString(),"STUDENT",record.studentId,"WHATSAPP","ATTENDANCE_ABSENT",c.encrypt(payload),QueueStatus.PENDING,now,null,0,null,"absence-wa:${record.studentId}:${lecture.id}",1))
            else dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"NOTIFICATION_RECIPIENT_MISSING","AttendanceRecord",record.id,null,"{\"channel\":\"WHATSAPP\"}","RECIPIENT_NOT_FOUND",now))
        }
    }

    private fun interruptionSeconds(intervals:List<PresenceInterval>):Long{
        if(intervals.size<2)return 0L
        val sorted=intervals.sortedBy{it.startEpochSeconds};var total=0L
        for(i in 1 until sorted.size)total+=(sorted[i].startEpochSeconds-sorted[i-1].endEpochSeconds).coerceAtLeast(0)
        return total
    }
}

internal object PresenceAdmissionRules {
    fun allowed(studentActive:Boolean,sameGroup:Boolean,deviceActive:Boolean,deviceMatchesStudent:Boolean,registeredDeviceMatches:Boolean,rosterRecordExists:Boolean):Boolean =
        studentActive && sameGroup && deviceActive && deviceMatchesStudent && registeredDeviceMatches && rosterRecordExists
}
