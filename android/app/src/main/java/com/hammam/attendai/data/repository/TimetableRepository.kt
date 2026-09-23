package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.domain.setup.DateInputNormalizer
import com.hammam.attendai.importexport.TimetableCsvFormat
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicGraphSyncOutbox
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

class TimetableRepository(
    private val db:HammamDatabase,
    private val authorization:AuthorizationRepository,
    private val lectureScheduler:LectureSchedulerRepository,
    cipher:KeystoreCipher?=null,
){
    private val dao=db.coreDao();private val graphSyncOutbox=cipher?.let{AcademicGraphSyncOutbox(db,it)}
    data class DraftRow(val dayOfWeek:Int,val subjectId:String,val teacherId:String,val startTime:String,val endTime:String,val room:String?,val lectureType:String="THEORY",val scheduleKind:String="REGULAR")
    data class ValidationIssue(val code:String,val message:String,val rowIndex:Int?=null)
    data class DraftReview(val version:WeeklyTimetableVersionEntity,val rows:List<TimetableEntity>,val issues:List<ValidationIssue>)

    fun observeVersions(groupId:String):Flow<List<WeeklyTimetableVersionEntity>> = dao.observeWeeklyTimetableVersions(groupId)

    suspend fun createDraft(groupId:String,weekStart:String,sourceType:String,sourceUri:String?,actorId:String):String{
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val start=DateInputNormalizer.parse(weekStart);val canonicalWeekStart=start.toString();val end=start.plusDays(6);val now=System.currentTimeMillis()
        return db.withTransaction{
            val version=dao.maxWeeklyTimetableVersion(groupId,canonicalWeekStart)+1;val id=UUID.randomUUID().toString()
            dao.insertWeeklyTimetableVersion(WeeklyTimetableVersionEntity(id,groupId,canonicalWeekStart,end.toString(),version,"DRAFT",sourceType,sourceUri,actorId,null,now,now,null,null))
            audit(actorId,"TIMETABLE_DRAFT_CREATED",id,"{\"groupId\":\"$groupId\",\"weekStart\":\"$canonicalWeekStart\",\"source\":\"${safe(sourceType)}\"}",null)
            id
        }
    }

    suspend fun replaceDraftRows(scheduleId:String,rows:List<DraftRow>,actorId:String):List<ValidationIssue>{
        val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        require(header.status in setOf("DRAFT","NEEDS_REVIEW")){"TIMETABLE_NOT_EDITABLE"}
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",header.groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val issues=validate(header,rows)
        db.withTransaction{
            dao.deleteInactiveTimetablesForWeeklySchedule(scheduleId)
            val now=System.currentTimeMillis()
            rows.forEach{r->dao.upsertTimetable(TimetableEntity(UUID.randomUUID().toString(),r.dayOfWeek,r.startTime,r.endTime,r.room.blankToNull(),r.subjectId,r.teacherId,header.groupId,r.lectureType,r.scheduleKind,false,now,now,1,scheduleId))}
            dao.updateWeeklyTimetableVersion(header.copy(status=if(issues.isEmpty())"DRAFT" else "NEEDS_REVIEW",updatedAt=now))
            audit(actorId,"TIMETABLE_DRAFT_UPDATED",scheduleId,"{\"rows\":${rows.size},\"issues\":${issues.size}}",null)
        }
        return issues
    }

    suspend fun markNeedsReview(scheduleId:String,actorId:String,reason:String){
        val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",header.groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val now=System.currentTimeMillis();db.withTransaction{dao.updateWeeklyTimetableVersion(header.copy(status="NEEDS_REVIEW",updatedAt=now,reason=reason));audit(actorId,"TIMETABLE_REVIEW_REQUIRED",scheduleId,"{\"reason\":\"${safe(reason)}\"}",reason)}
    }

    suspend fun review(scheduleId:String,actorId:String):DraftReview{
        val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",header.groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val rows=dao.getTimetablesForWeeklySchedule(scheduleId)
        val issues=validate(header,rows.map{DraftRow(it.dayOfWeek,it.subjectId,it.teacherId,it.startTime,it.endTime,it.room,it.lectureType,it.scheduleKind)})
        return DraftReview(header,rows,issues)
    }

    suspend fun approve(scheduleId:String,actorId:String,reason:String):DraftReview{
        require(reason.isNotBlank()){"REASON_REQUIRED"}
        val initial=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        require(initial.status in setOf("DRAFT","NEEDS_REVIEW")){"TIMETABLE_NOT_APPROVABLE"}
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",initial.groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val zone=ZoneId.systemDefault();val start=LocalDate.parse(initial.weekStart);val from=start.atStartOfDay(zone).toInstant().toEpochMilli();val until=start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        db.withTransaction{
            // Re-read rows and validate inside the approval transaction so review-time state cannot go stale.
            val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
            require(header.status in setOf("DRAFT","NEEDS_REVIEW")){"TIMETABLE_NOT_APPROVABLE"}
            val rows=dao.getTimetablesForWeeklySchedule(scheduleId)
            val issues=validate(header,rows.map{DraftRow(it.dayOfWeek,it.subjectId,it.teacherId,it.startTime,it.endTime,it.room,it.lectureType,it.scheduleKind)})
            require(issues.isEmpty()){"TIMETABLE_NEEDS_REVIEW"}
            val now=System.currentTimeMillis();val old=dao.getActiveWeeklyVersions(header.groupId,header.weekStart)
            old.filter{it.id!=header.id}.forEach{dao.updateWeeklyTimetableVersion(it.copy(status="ARCHIVED",updatedAt=now,reason=reason))}
            dao.deactivateTimetablesForWeek(header.groupId,header.weekStart,now)
            rows.forEach{dao.updateTimetable(it.copy(isActive=true,updatedAt=now,version=it.version+1))}
            val cancelledLectures=dao.getFutureScheduledLectures(header.groupId,from,until)
            dao.cancelFutureScheduledLectures(header.groupId,from,until,now)
            cancelledLectures.forEach{graphSyncOutbox?.recordLecture(it.copy(status=com.hammam.attendai.domain.model.LectureStatus.CANCELLED,updatedAt=now,version=it.version+1),now)}
            dao.updateWeeklyTimetableVersion(header.copy(status="ACTIVE",approvedBy=actorId,approvedAt=now,updatedAt=now,reason=reason))
            // Nested Room transaction: materialization failure rolls back ACTIVE status and row activation together.
            lectureScheduler.materializeWeeklySchedule(header.id,now,zone)
            val previous=old.joinToString(prefix="[",postfix="]"){"\"${it.id}\""}
            audit(actorId,"TIMETABLE_APPROVED",header.id,"{\"previous\":$previous,\"rows\":${rows.size}}",reason)
        }
        return review(scheduleId,actorId)
    }

    suspend fun saveManualCsv(scheduleId:String,text:String,actorId:String):List<ValidationIssue>{
        val parsed=TimetableCsvFormat.parse(text)
        if(parsed.issues.isNotEmpty())return parsed.issues.map{ValidationIssue(it.code,it.code,it.line)}
        val rows=mutableListOf<DraftRow>();val issues=mutableListOf<ValidationIssue>()
        parsed.rows.forEach{raw->
            val subject=dao.getSubjectByCode(raw.subjectRef)?:dao.getSubjectById(raw.subjectRef)
            if(subject==null||subject.archivedAt!=null){issues+=ValidationIssue("UNKNOWN_SUBJECT","UNKNOWN_SUBJECT",raw.line);return@forEach}
            val teacher=if(raw.teacherRef.isBlank())subject.teacherId?.let{dao.getTeacherById(it)} else dao.getTeacherByNormalizedName(ArabicNormalizer.normalize(raw.teacherRef))?:dao.getTeacherById(raw.teacherRef)
            if(teacher==null||teacher.archivedAt!=null){issues+=ValidationIssue("UNKNOWN_TEACHER","UNKNOWN_TEACHER",raw.line);return@forEach}
            rows+=DraftRow(raw.dayOfWeek,subject.id,teacher.id,raw.startTime,raw.endTime,raw.room,raw.lectureType,raw.scheduleKind)
        }
        if(issues.isNotEmpty())return issues
        return replaceDraftRows(scheduleId,rows,actorId)
    }

    private suspend fun validate(header:WeeklyTimetableVersionEntity,rows:List<DraftRow>):List<ValidationIssue>{
        val issues=mutableListOf<ValidationIssue>()
        if(rows.isEmpty())issues+=ValidationIssue("EMPTY_TIMETABLE","Timetable must contain at least one row")
        else if(rows.none{!it.scheduleKind.equals("CANCELLED",true)})issues+=ValidationIssue("NO_MATERIALIZABLE_ROWS","Timetable must contain at least one schedulable row")
        if(dao.getActiveGroupById(header.groupId)==null)issues+=ValidationIssue("INVALID_GROUP","Group is missing or archived")
        runCatching{LocalDate.parse(header.weekStart)}.onFailure{issues+=ValidationIssue("INVALID_WEEK_START","Invalid week start")}
        runCatching{LocalDate.parse(header.weekEnd)}.onFailure{issues+=ValidationIssue("INVALID_WEEK_END","Invalid week end")}
        rows.forEachIndexed{i,r->
            if(r.dayOfWeek !in 1..7)issues+=ValidationIssue("INVALID_DAY","Day must be 1..7",i)
            val st=runCatching{LocalTime.parse(r.startTime)}.getOrNull();val et=runCatching{LocalTime.parse(r.endTime)}.getOrNull();if(st==null||et==null||!et.isAfter(st))issues+=ValidationIssue("INVALID_TIME","End time must be after start time",i)
            val subject=dao.getSubjectById(r.subjectId);if(subject==null||subject.archivedAt!=null)issues+=ValidationIssue("UNKNOWN_SUBJECT","Subject is missing or archived",i) else if(subject.groupId!=header.groupId)issues+=ValidationIssue("SUBJECT_SCOPE_MISMATCH","Subject is outside group",i) else {val semester=dao.getSemesterById(subject.semesterId);val ws=runCatching{LocalDate.parse(header.weekStart)}.getOrNull();val we=runCatching{LocalDate.parse(header.weekEnd)}.getOrNull();val ss=semester?.let{runCatching{LocalDate.parse(it.startDate)}.getOrNull()};val se=semester?.let{runCatching{LocalDate.parse(it.endDate)}.getOrNull()};if(semester==null||semester.status in setOf(com.hammam.attendai.domain.model.SemesterStatus.COMPLETED,com.hammam.attendai.domain.model.SemesterStatus.ARCHIVED)||ws==null||we==null||ss==null||se==null||ws.isBefore(ss)||we.isAfter(se))issues+=ValidationIssue("INVALID_SEMESTER_SCOPE","Subject semester is not valid for this timetable week",i)}
            val teacher=dao.getTeacherById(r.teacherId);if(teacher==null||teacher.archivedAt!=null)issues+=ValidationIssue("UNKNOWN_TEACHER","Teacher is missing or archived",i)
            if(subject!=null && subject.teacherId!=r.teacherId && !dao.isTeacherLinkedToSubject(r.teacherId,r.subjectId))issues+=ValidationIssue("TEACHER_SUBJECT_MISMATCH","Teacher is not linked to subject",i)
        }
        rows.forEachIndexed{i,a->rows.drop(i+1).forEachIndexed{off,b->val j=i+1+off;if(a.dayOfWeek==b.dayOfWeek){val asT=runCatching{LocalTime.parse(a.startTime)}.getOrNull();val ae=runCatching{LocalTime.parse(a.endTime)}.getOrNull();val bs=runCatching{LocalTime.parse(b.startTime)}.getOrNull();val be=runCatching{LocalTime.parse(b.endTime)}.getOrNull();if(asT!=null&&ae!=null&&bs!=null&&be!=null&&asT<be&&bs<ae){issues+=ValidationIssue("GROUP_OVERLAP","Group lectures overlap",j);if(a.teacherId==b.teacherId)issues+=ValidationIssue("TEACHER_OVERLAP","Teacher lectures overlap",j)};if(a.subjectId==b.subjectId&&a.startTime==b.startTime)issues+=ValidationIssue("DUPLICATE_LECTURE","Duplicate lecture",j)}}}
        return issues.distinctBy{"${it.code}|${it.rowIndex}"}
    }

    private suspend fun audit(actorId:String,action:String,id:String,newData:String?,reason:String?){dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,action,"WeeklyTimetable",id,null,newData,reason,System.currentTimeMillis(),authorization.roleNames(actorId).firstOrNull()))}
    private fun safe(v:String)=v.replace("\\","\\\\").replace("\"","'")
    private fun String?.blankToNull()=this?.trim()?.ifBlank{null}
}
