package com.hammam.attendai.data.repository

import com.hammam.attendai.data.local.HammamDatabase

class DataIntegrityRepository(private val db:HammamDatabase){
    data class Issue(val code:String,val count:Int,val suggestedAction:String)
    data class Result(val healthy:Boolean,val issues:List<Issue>)
    private val dao=db.coreDao()

    suspend fun run():Result{
        val issues=mutableListOf<Issue>()
        fun add(code:String,count:Int,action:String){if(count>0)issues+=Issue(code,count,action)}
        add("STUDENT_INVALID_ACADEMIC_SCOPE",dao.countStudentsWithInvalidScope(),"Review student level/batch/section/group assignments")
        add("SUBJECT_WITHOUT_TEACHER",dao.countSubjectsWithoutTeacher(),"Assign a teacher or archive the subject")
        add("SUBJECT_INVALID_TEACHER",dao.countSubjectsWithInvalidTeacher(),"Assign an active teacher")
        add("BROKEN_TEACHER_SUBJECT_RELATION",dao.countBrokenTeacherSubjectRelations(),"Review teacher-subject links")
        add("TIMETABLE_ARCHIVED_SUBJECT",dao.countTimetablesUsingArchivedSubject(),"Review or archive affected timetable rows")
        add("ORPHAN_ATTENDANCE",dao.countOrphanAttendance(),"Review database integrity before editing")
        add("ORPHAN_APPEAL",dao.countOrphanAppeals(),"Review appeal references")
        add("MULTIPLE_ACTIVE_DEVICES",dao.multipleActiveDeviceGroups().size,"Review device enrollment policy")
        add("REPORT_JOB_INCONSISTENT",dao.countInconsistentReportJobs(),"Review report job status/timestamps")
        add("SYNC_REPEATED_FAILURE",dao.countRepeatedlyFailedSync(),"Inspect sanitized sync error and retry manually")
        add("STUCK_ACTIVE_LECTURE",dao.countStuckActiveLectures(System.currentTimeMillis()-12*60*60*1000L),"Review active lecture and finalize or cancel it")
        add("INVALID_SEMESTER_DATES",dao.countInvalidSemesterDates(),"Correct semester start/end dates")
        add("DUPLICATE_UNIVERSITY_NUMBER",dao.duplicateUniversityNumberGroups().size,"Review duplicate university numbers")
        val rows=dao.getActiveTimetables();var overlaps=0
        rows.groupBy{it.groupId to it.dayOfWeek}.values.forEach{list->overlaps+=countOverlaps(list.map{it.startTime to it.endTime})}
        rows.groupBy{it.teacherId to it.dayOfWeek}.values.forEach{list->overlaps+=countOverlaps(list.map{it.startTime to it.endTime})}
        add("TIMETABLE_OVERLAP",overlaps,"Review active timetable conflicts")
        return Result(issues.isEmpty(),issues)
    }
    private fun countOverlaps(ranges:List<Pair<String,String>>):Int{
        val sorted=ranges.sortedBy{it.first};var n=0
        for(i in 1 until sorted.size)if(sorted[i].first<sorted[i-1].second)n++
        return n
    }
}
