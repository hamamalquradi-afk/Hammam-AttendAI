package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.LectureEntity
import com.hammam.attendai.domain.model.LectureStatus
import java.nio.charset.StandardCharsets
import java.time.*
import java.util.UUID

/** Materializes weekly timetable rows into durable local lecture instances. */
class LectureSchedulerRepository(private val db:HammamDatabase){
    private val dao=db.coreDao()

    suspend fun materializeNextDays(days:Int=14,nowMillis:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):Int=db.withTransaction{
        require(days in 1..45)
        val today=Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        var created=0
        for(timetable in dao.getActiveTimetables()){
            if(timetable.scheduleKind.equals("CANCELLED",true))continue
            val subject=dao.getSubjectById(timetable.subjectId)?:continue
            val semester=dao.getSemesterById(subject.semesterId)?:continue
            val semesterStart=runCatching{LocalDate.parse(semester.startDate)}.getOrNull()?:continue
            val semesterEnd=runCatching{LocalDate.parse(semester.endDate)}.getOrNull()?:continue
            val weeklyBounds=timetable.weeklyScheduleId?.let{dao.getWeeklyTimetableVersion(it)}?.let{header->
                val weekStart=runCatching{LocalDate.parse(header.weekStart)}.getOrNull()
                val weekEnd=runCatching{LocalDate.parse(header.weekEnd)}.getOrNull()
                if(weekStart!=null&&weekEnd!=null)weekStart to weekEnd else null
            }
            val startTime=runCatching{LocalTime.parse(timetable.startTime)}.getOrNull()?:continue
            val endTime=runCatching{LocalTime.parse(timetable.endTime)}.getOrNull()?:continue
            for(offset in 0 until days){
                val date=today.plusDays(offset.toLong())
                if(date.isBefore(semesterStart)||date.isAfter(semesterEnd)||date.dayOfWeek.value!=timetable.dayOfWeek)continue
                if(weeklyBounds!=null&&(date.isBefore(weeklyBounds.first)||date.isAfter(weeklyBounds.second)))continue
                val start=date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                var end=date.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                if(end<=start)end=date.plusDays(1).atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                if(dao.countLectureAt(timetable.subjectId,timetable.groupId,start)>0)continue
                val id=UUID.nameUUIDFromBytes("${timetable.id}|$date|$start".toByteArray(StandardCharsets.UTF_8)).toString()
                dao.insertLecture(LectureEntity(
                    id=id,subjectId=timetable.subjectId,teacherId=timetable.teacherId,semesterId=subject.semesterId,groupId=timetable.groupId,
                    scheduledStart=start,scheduledEnd=end,actualStart=null,actualEnd=null,room=timetable.room,status=LectureStatus.SCHEDULED,
                    attendancePolicySnapshotJson="",createdAt=nowMillis,updatedAt=nowMillis,version=1
                ))
                created++
            }
        }
        created
    }
}
