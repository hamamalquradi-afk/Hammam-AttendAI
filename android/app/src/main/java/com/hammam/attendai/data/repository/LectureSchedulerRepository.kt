package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.LectureEntity
import com.hammam.attendai.data.local.entity.TimetableEntity
import com.hammam.attendai.domain.model.LectureStatus
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicGraphSyncOutbox
import java.nio.charset.StandardCharsets
import java.time.*
import java.util.UUID

/** Materializes weekly timetable rows into durable local lecture instances. */
class LectureSchedulerRepository(private val db:HammamDatabase,cipher:KeystoreCipher?=null){
    private val dao=db.coreDao();private val syncOutbox=cipher?.let{AcademicGraphSyncOutbox(db,it)}

    suspend fun materializeNextDays(days:Int=14,nowMillis:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):Int=db.withTransaction{
        require(days in 1..45)
        val today=Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        var created=0
        for(timetable in dao.getActiveTimetables()){
            val header=timetable.weeklyScheduleId?.let{dao.getWeeklyTimetableVersion(it)}
            if(header!=null&&header.status !in setOf("APPROVED","ACTIVE"))continue
            val weekStart=header?.let{runCatching{LocalDate.parse(it.weekStart)}.getOrNull()}
            val weekEnd=header?.let{runCatching{LocalDate.parse(it.weekEnd)}.getOrNull()}
            val from=if(weekStart!=null)maxOf(today,weekStart) else today
            val to=if(weekEnd!=null)minOf(today.plusDays((days-1).toLong()),weekEnd) else today.plusDays((days-1).toLong())
            if(to.isBefore(from))continue
            created+=materializeRow(timetable,header?.id?:"LEGACY:${timetable.id}",from,to,nowMillis,zone,strict=false)
        }
        created
    }

    /** Strict materialization used by timetable approval. Any invalid approved row aborts the approval transaction. */
    suspend fun materializeWeeklySchedule(scheduleId:String,nowMillis:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):Int=db.withTransaction{
        val header=dao.getWeeklyTimetableVersion(scheduleId)?:error("TIMETABLE_VERSION_NOT_FOUND")
        require(header.status in setOf("APPROVED","ACTIVE")){"TIMETABLE_NOT_ACTIVE"}
        val weekStart=LocalDate.parse(header.weekStart);val weekEnd=LocalDate.parse(header.weekEnd)
        var created=0
        val rows=dao.getTimetablesForWeeklySchedule(scheduleId).filter{it.isActive}
        require(rows.isNotEmpty()){"TIMETABLE_HAS_NO_ACTIVE_ROWS"}
        for(row in rows)created+=materializeRow(row,scheduleId,weekStart,weekEnd,nowMillis,zone,strict=true)
        created
    }

    private suspend fun materializeRow(timetable:TimetableEntity,scheduleId:String,from:LocalDate,to:LocalDate,nowMillis:Long,zone:ZoneId,strict:Boolean):Int{
        if(timetable.scheduleKind.equals("CANCELLED",true))return 0
        val subject=dao.getSubjectById(timetable.subjectId)
        val teacher=dao.getTeacherById(timetable.teacherId)
        val group=dao.getActiveGroupById(timetable.groupId)
        if(subject==null||subject.archivedAt!=null||subject.groupId!=timetable.groupId||teacher==null||teacher.archivedAt!=null||group==null){if(strict)error("TIMETABLE_MATERIALIZATION_SCOPE_INVALID");return 0}
        val semester=dao.getSemesterById(subject.semesterId)
        if(semester==null||semester.status in setOf(SemesterStatus.COMPLETED,SemesterStatus.ARCHIVED)){if(strict)error("TIMETABLE_MATERIALIZATION_SCOPE_INVALID");return 0}
        val semesterStart=runCatching{LocalDate.parse(semester.startDate)}.getOrNull()
        val semesterEnd=runCatching{LocalDate.parse(semester.endDate)}.getOrNull()
        val startTime=runCatching{LocalTime.parse(timetable.startTime)}.getOrNull()
        val endTime=runCatching{LocalTime.parse(timetable.endTime)}.getOrNull()
        if(semesterStart==null||semesterEnd==null||startTime==null||endTime==null){if(strict)error("TIMETABLE_MATERIALIZATION_TIME_INVALID");return 0}
        var created=0;var date=from
        while(!date.isAfter(to)){
            if(!date.isBefore(semesterStart)&&!date.isAfter(semesterEnd)&&date.dayOfWeek.value==timetable.dayOfWeek){
                val start=date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                var end=date.atTime(endTime).atZone(zone).toInstant().toEpochMilli();if(end<=start)end=date.plusDays(1).atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                val id=lectureId(scheduleId,timetable.subjectId,timetable.groupId,start)
                val identityExists=dao.getLectureById(id)!=null
                val nonCancelledAtSlot=dao.countNonCancelledLectureAt(timetable.subjectId,timetable.groupId,start)
                if(shouldInsert(identityExists,nonCancelledAtSlot)){
                    val lecture=LectureEntity(id,timetable.subjectId,timetable.teacherId,subject.semesterId,timetable.groupId,start,end,null,null,timetable.room,LectureStatus.SCHEDULED,"",nowMillis,nowMillis,1);dao.insertLecture(lecture);syncOutbox?.recordLecture(lecture,nowMillis);created++
                }
            }
            date=date.plusDays(1)
        }
        return created
    }

    companion object{
        internal fun lectureId(scheduleId:String,subjectId:String,groupId:String,scheduledStart:Long):String=
            UUID.nameUUIDFromBytes("$scheduleId|$subjectId|$groupId|$scheduledStart".toByteArray(StandardCharsets.UTF_8)).toString()
        internal fun shouldInsert(identityExists:Boolean,nonCancelledAtSlot:Int):Boolean=!identityExists&&nonCancelledAtSlot==0
    }
}
