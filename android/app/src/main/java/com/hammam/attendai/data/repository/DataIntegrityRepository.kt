package com.hammam.attendai.data.repository

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.security.AuthorizationRepository

class DataIntegrityRepository(private val db:HammamDatabase,private val authorization:AuthorizationRepository){
    data class Issue(val code:String,val count:Int,val suggestedAction:String)
    data class Result(val healthy:Boolean,val issues:List<Issue>)
    private val dao=db.coreDao()

    suspend fun run(actorId:String):Result{
        require(authorization.hasPermission(actorId,"RUN_DATA_INTEGRITY")){"RUN_DATA_INTEGRITY_PERMISSION_REQUIRED"}
        val issues=mutableListOf<Issue>()
        fun add(code:String,count:Int,action:String){if(count>0)issues+=Issue(code,count,action)}
        add("STUDENT_INVALID_ACADEMIC_SCOPE",dao.countStudentsWithInvalidScope(),"INTEGRITY_ACTION_REVIEW_STUDENT_SCOPE")
        add("SUBJECT_WITHOUT_TEACHER",dao.countSubjectsWithoutTeacher(),"INTEGRITY_ACTION_ASSIGN_TEACHER_OR_ARCHIVE_SUBJECT")
        add("SUBJECT_INVALID_ATTENDANCE_POLICY",dao.countSubjectsWithInvalidAttendancePolicy(),"INTEGRITY_ACTION_ASSIGN_ATTENDANCE_POLICY")
        add("SUBJECT_INVALID_TEACHER",dao.countSubjectsWithInvalidTeacher(),"INTEGRITY_ACTION_ASSIGN_ACTIVE_TEACHER")
        add("BROKEN_TEACHER_SUBJECT_RELATION",dao.countBrokenTeacherSubjectRelations(),"INTEGRITY_ACTION_REVIEW_TEACHER_SUBJECT_LINKS")
        add("TIMETABLE_ARCHIVED_SUBJECT",dao.countTimetablesUsingArchivedSubject(),"INTEGRITY_ACTION_REVIEW_TIMETABLE_ROWS")
        add("ORPHAN_ATTENDANCE",dao.countOrphanAttendance(),"INTEGRITY_ACTION_REVIEW_DATABASE_REFERENCES")
        add("ORPHAN_APPEAL",dao.countOrphanAppeals(),"INTEGRITY_ACTION_REVIEW_APPEAL_REFERENCES")
        add("MULTIPLE_ACTIVE_DEVICES",dao.multipleActiveDeviceGroups().size,"INTEGRITY_ACTION_REVIEW_DEVICE_ENROLLMENT")
        add("REPORT_JOB_INCONSISTENT",dao.countInconsistentReportJobs(),"INTEGRITY_ACTION_REVIEW_REPORT_JOBS")
        add("SYNC_REPEATED_FAILURE",dao.countRepeatedlyFailedSync(),"INTEGRITY_ACTION_REVIEW_SYNC_FAILURES")
        add("STUCK_ACTIVE_LECTURE",dao.countStuckActiveLectures(System.currentTimeMillis()-12*60*60*1000L),"INTEGRITY_ACTION_REVIEW_ACTIVE_LECTURE")
        add("INVALID_SEMESTER_DATES",dao.countInvalidSemesterDates(),"INTEGRITY_ACTION_CORRECT_SEMESTER_DATES")
        add("DUPLICATE_UNIVERSITY_NUMBER",dao.duplicateUniversityNumberGroups().size,"INTEGRITY_ACTION_REVIEW_DUPLICATE_UNIVERSITY_NUMBERS")
        val invalidTeacherContacts=dao.getTeachersOnce().count{it.archivedAt==null&&AcademicValidationRules.validateContact(it.phone,it.whatsapp,it.email)!=null}
        add("TEACHER_CONTACT_INVALID",invalidTeacherContacts,"INTEGRITY_ACTION_REVIEW_TEACHER_CONTACTS")
        var invalidSubjectScope=0
        dao.getSubjectsOnce().filter{it.archivedAt==null&&it.status!="ARCHIVED"}.forEach{subject->
            val group=dao.getActiveGroupById(subject.groupId);val section=group?.let{dao.getActiveSectionById(it.sectionId)};val batch=section?.let{dao.getActiveBatchById(it.batchId)};val level=dao.getActiveLevelById(subject.levelId);val semester=dao.getSemesterById(subject.semesterId)
            if(group==null||section==null||batch==null||level==null||semester==null||batch.levelId!=level.id||semester.academicYearId!=batch.academicYearId)invalidSubjectScope++
        }
        add("SUBJECT_ACADEMIC_SCOPE_MISMATCH",invalidSubjectScope,"INTEGRITY_ACTION_REVIEW_SUBJECT_SCOPE")
        val rows=dao.getActiveTimetables();var overlaps=0
        rows.groupBy{it.groupId to it.dayOfWeek}.values.forEach{list->overlaps+=countOverlaps(list.map{it.startTime to it.endTime})}
        rows.groupBy{it.teacherId to it.dayOfWeek}.values.forEach{list->overlaps+=countOverlaps(list.map{it.startTime to it.endTime})}
        add("TIMETABLE_OVERLAP",overlaps,"INTEGRITY_ACTION_REVIEW_TIMETABLE_CONFLICTS")
        return Result(issues.isEmpty(),issues)
    }
    private fun countOverlaps(ranges:List<Pair<String,String>>):Int{
        val sorted=ranges.sortedBy{it.first};var n=0
        for(i in 1 until sorted.size)if(sorted[i].first<sorted[i-1].second)n++
        return n
    }
}
