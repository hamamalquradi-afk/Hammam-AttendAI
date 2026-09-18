package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.security.AuthorizationRepository
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

class TimetableRepository(
    private val db:HammamDatabase,
    private val authorization:AuthorizationRepository,
    private val lectureScheduler:LectureSchedulerRepository,
){
    private val dao=db.coreDao()
    data class DraftRow(val dayOfWeek:Int,val subjectId:String,val teacherId:String,val startTime:String,val endTime:String,val room:String?,val lectureType:String="THEORY",val scheduleKind:String="REGULAR")
    data class ValidationIssue(val code:String,val message:String,val rowIndex:Int?=null)
    data class DraftReview(val version:WeeklyTimetableVersionEntity,val rows:List<TimetableEntity>,val issues:List<ValidationIssue>)

    fun observeVersions(groupId:String):Flow<List<WeeklyTimetableVersionEntity>> = dao.observeWeeklyTimetableVersions(groupId)

    suspend fun createDraft(groupId:String,weekStart:String,sourceType:String,sourceUri:String?,actorId:String):String{
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val start=LocalDate.parse(weekStart);val end=start.plusDays(6);val now=System.currentTimeMillis()
        return db.withTransaction{
            val version=dao.maxWeeklyTimetableVersion(groupId,weekStart)+1;val id=UUID.randomUUID().toString()
            dao.insertWeeklyTimetableVersion(WeeklyTimetableVersionEntity(id,groupId,start.toString(),end.toString(),version,"DRAFT",sourceType,sourceUri,actorId,null,now,now,null,null))
            audit(actorId,"TIMETABLE_DRAFT_CREATED",id,"{\"groupId\":\"$groupId\",\"weekStart\":\"$weekStart\",\"source\":\"${safe(sourceType)}\"}",null)
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

    suspend fun review(scheduleId:String):DraftReview{
        val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        val rows=dao.getTimetablesForWeeklySchedule(scheduleId)
        val issues=validate(header,rows.map{DraftRow(it.dayOfWeek,it.subjectId,it.teacherId,it.startTime,it.endTime,it.room,it.lectureType,it.scheduleKind)})
        return DraftReview(header,rows,issues)
    }

    suspend fun approve(scheduleId:String,actorId:String,reason:String):DraftReview{
        require(reason.isNotBlank()){"REASON_REQUIRED"}
        val review=review(scheduleId);val header=review.version
        require(review.issues.isEmpty()){"TIMETABLE_NEEDS_REVIEW"}
        require(authorization.hasScopedPermission(actorId,"MANAGE_TIMETABLE","GROUP",header.groupId)){"MANAGE_TIMETABLE_PERMISSION_REQUIRED"}
        val zone=ZoneId.systemDefault();val start=LocalDate.parse(header.weekStart);val from=start.atStartOfDay(zone).toInstant().toEpochMilli();val until=start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        db.withTransaction{
            val now=System.currentTimeMillis();val old=dao.getActiveWeeklyVersions(header.groupId,header.weekStart)
            old.filter{it.id!=header.id}.forEach{dao.updateWeeklyTimetableVersion(it.copy(status="ARCHIVED",updatedAt=now,reason=reason))}
            dao.deactivateTimetablesForWeek(header.groupId,header.weekStart,now)
            review.rows.forEach{dao.updateTimetable(it.copy(isActive=true,updatedAt=now,version=it.version+1))}
            dao.cancelFutureScheduledLectures(header.groupId,from,until,now)
            dao.updateWeeklyTimetableVersion(header.copy(status="ACTIVE",approvedBy=actorId,approvedAt=now,updatedAt=now,reason=reason))
            val previous=old.joinToString(prefix="[",postfix="]"){"\"${it.id}\""}
            audit(actorId,"TIMETABLE_APPROVED",header.id,"{\"previous\":$previous,\"rows\":${review.rows.size}}",reason)
        }
        // Materialization is intentionally after the atomic approval. Existing completed lectures are never touched.
        lectureScheduler.materializeNextDays(14)
        return review(scheduleId)
    }

    suspend fun saveManualCsv(scheduleId:String,text:String,actorId:String):List<ValidationIssue>{
        val rows=mutableListOf<DraftRow>()
        text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()&&!it.startsWith("#")}.forEachIndexed{index,line->
            val c=line.split(',').map{it.trim()};if(index==0&&c.firstOrNull()?.equals("day",true)==true)return@forEachIndexed
            if(c.size>=5){val day=c[0].toIntOrNull()?:0;val subject=dao.getSubjectByCode(c[1])?:dao.getSubjectById(c[1]);val teacher=if(c[2].isBlank())subject?.teacherId?.let{dao.getTeacherById(it)} else dao.getTeacherByNormalizedName(ArabicNormalizer.normalize(c[2]))?:dao.getTeacherById(c[2]);if(subject!=null&&teacher!=null)rows+=DraftRow(day,subject.id,teacher.id,c[3],c[4],c.getOrNull(5),c.getOrNull(6)?.ifBlank{"THEORY"}?:"THEORY",c.getOrNull(7)?.ifBlank{"REGULAR"}?:"REGULAR")}
        }
        return replaceDraftRows(scheduleId,rows,actorId)
    }

    private suspend fun validate(header:WeeklyTimetableVersionEntity,rows:List<DraftRow>):List<ValidationIssue>{
        val issues=mutableListOf<ValidationIssue>()
        runCatching{LocalDate.parse(header.weekStart)}.onFailure{issues+=ValidationIssue("INVALID_WEEK_START","Invalid week start")}
        runCatching{LocalDate.parse(header.weekEnd)}.onFailure{issues+=ValidationIssue("INVALID_WEEK_END","Invalid week end")}
        rows.forEachIndexed{i,r->
            if(r.dayOfWeek !in 1..7)issues+=ValidationIssue("INVALID_DAY","Day must be 1..7",i)
            val st=runCatching{LocalTime.parse(r.startTime)}.getOrNull();val et=runCatching{LocalTime.parse(r.endTime)}.getOrNull();if(st==null||et==null||!et.isAfter(st))issues+=ValidationIssue("INVALID_TIME","End time must be after start time",i)
            val subject=dao.getSubjectById(r.subjectId);if(subject==null||subject.archivedAt!=null)issues+=ValidationIssue("UNKNOWN_SUBJECT","Subject is missing or archived",i) else if(subject.groupId!=header.groupId)issues+=ValidationIssue("SUBJECT_SCOPE_MISMATCH","Subject is outside group",i)
            if(dao.getTeacherById(r.teacherId)==null)issues+=ValidationIssue("UNKNOWN_TEACHER","Teacher not found",i)
            if(subject!=null && subject.teacherId!=r.teacherId && !dao.isTeacherLinkedToSubject(r.teacherId,r.subjectId))issues+=ValidationIssue("TEACHER_SUBJECT_MISMATCH","Teacher is not linked to subject",i)
        }
        rows.forEachIndexed{i,a->rows.drop(i+1).forEachIndexed{off,b->val j=i+1+off;if(a.dayOfWeek==b.dayOfWeek){val asT=runCatching{LocalTime.parse(a.startTime)}.getOrNull();val ae=runCatching{LocalTime.parse(a.endTime)}.getOrNull();val bs=runCatching{LocalTime.parse(b.startTime)}.getOrNull();val be=runCatching{LocalTime.parse(b.endTime)}.getOrNull();if(asT!=null&&ae!=null&&bs!=null&&be!=null&&asT<be&&bs<ae){issues+=ValidationIssue("GROUP_OVERLAP","Group lectures overlap",j);if(a.teacherId==b.teacherId)issues+=ValidationIssue("TEACHER_OVERLAP","Teacher lectures overlap",j)};if(a.subjectId==b.subjectId&&a.startTime==b.startTime)issues+=ValidationIssue("DUPLICATE_LECTURE","Duplicate lecture",j)}}}
        return issues.distinctBy{"${it.code}|${it.rowIndex}"}
    }

    private suspend fun audit(actorId:String,action:String,id:String,newData:String?,reason:String?){dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,action,"WeeklyTimetable",id,null,newData,reason,System.currentTimeMillis(),authorization.roleNames(actorId).firstOrNull()))}
    private fun safe(v:String)=v.replace("\\","\\\\").replace("\"","'")
    private fun String?.blankToNull()=this?.trim()?.ifBlank{null}
}
