package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicReferenceSyncOutbox
import com.hammam.attendai.domain.setup.DateInputNormalizer
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Mutations for the existing academic structure. Authorization and audit live here, not only in UI. */
class AcademicManagementRepository(
    private val db:HammamDatabase,
    private val authorization:AuthorizationRepository,
    cipher:KeystoreCipher?=null,
){
    private val dao=db.coreDao()
    private val syncOutbox=cipher?.let{AcademicReferenceSyncOutbox(db,it)}
    private val hierarchySyncOutbox=cipher?.let{com.hammam.attendai.sync.AcademicHierarchySyncOutbox(db,it)}
    private val graphSyncOutbox=cipher?.let{com.hammam.attendai.sync.AcademicGraphSyncOutbox(db,it)}

    val universities:Flow<List<UniversityEntity>> = dao.observeUniversities()
    val faculties:Flow<List<FacultyEntity>> = dao.observeFaculties()
    val departments:Flow<List<DepartmentEntity>> = dao.observeDepartments()
    val academicYears:Flow<List<AcademicYearEntity>> = dao.observeAcademicYears()
    val semesters:Flow<List<SemesterEntity>> = dao.observeSemesters()
    val levels:Flow<List<LevelEntity>> = dao.observeLevels()
    val batches:Flow<List<BatchEntity>> = dao.observeBatches()
    val sections:Flow<List<SectionEntity>> = dao.observeSections()
    val groups:Flow<List<GroupEntity>> = dao.observeGroups()
    val teachers:Flow<List<TeacherEntity>> = dao.observeTeachers()
    val subjects:Flow<List<SubjectEntity>> = dao.observeSubjects()
    val attendancePolicies:Flow<List<AttendancePolicyEntity>> = dao.observeAttendancePolicies()

    private suspend fun requireAcademic(actorId:String){
        require(authorization.hasPermission(actorId,"MANAGE_ACADEMIC_STRUCTURE")){"MANAGE_ACADEMIC_STRUCTURE_PERMISSION_REQUIRED"}
    }
    private suspend fun audit(actorId:String,action:String,type:String,id:String,newData:String?,reason:String?=null){
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,action,type,id,null,newData,reason,System.currentTimeMillis(),authorization.roleNames(actorId).firstOrNull()))
    }

    suspend fun addUniversity(name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"UNIVERSITY_NAME_REQUIRED"};return db.withTransaction{
        val now=System.currentTimeMillis();val v=UniversityEntity(UUID.randomUUID().toString(),name.trim());dao.insertUniversity(v);hierarchySyncOutbox?.recordUniversity(v,now);audit(actorId,"UNIVERSITY_CREATED","University",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addFaculty(universityId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"FACULTY_NAME_REQUIRED"};val parent=dao.getUniversityById(universityId)?:error("UNIVERSITY_NOT_FOUND");require(parent.archivedAt==null){"UNIVERSITY_ARCHIVED"};return db.withTransaction{
        val now=System.currentTimeMillis();val v=FacultyEntity(UUID.randomUUID().toString(),universityId,name.trim());dao.insertFaculty(v);hierarchySyncOutbox?.recordFaculty(v,now);audit(actorId,"FACULTY_CREATED","Faculty",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addDepartment(facultyId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"DEPARTMENT_NAME_REQUIRED"};val parent=dao.getFacultyById(facultyId)?:error("FACULTY_NOT_FOUND");require(parent.archivedAt==null){"FACULTY_ARCHIVED"};return db.withTransaction{
        val now=System.currentTimeMillis();val v=DepartmentEntity(UUID.randomUUID().toString(),facultyId,name.trim());dao.insertDepartment(v);hierarchySyncOutbox?.recordDepartment(v,now);audit(actorId,"DEPARTMENT_CREATED","Department",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addAcademicYear(name:String,startDate:String,endDate:String,actorId:String):String{
        requireAcademic(actorId);require(name.isNotBlank()){"ACADEMIC_YEAR_NAME_REQUIRED"}
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        return db.withTransaction{
            val now=System.currentTimeMillis();val v=AcademicYearEntity(UUID.randomUUID().toString(),name.trim(),canonicalStart,canonicalEnd,true);dao.insertAcademicYear(v);hierarchySyncOutbox?.recordAcademicYear(v,now);audit(actorId,"ACADEMIC_YEAR_CREATED","AcademicYear",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
        }
    }
    suspend fun addSemester(name:String,academicYearId:String,startDate:String,endDate:String,actorId:String,status:SemesterStatus=SemesterStatus.UPCOMING):String{
        requireAcademic(actorId);require(name.isNotBlank()){"SEMESTER_NAME_REQUIRED"}
        val year=dao.getAcademicYearById(academicYearId)?:error("ACADEMIC_YEAR_NOT_FOUND");require(year.isActive){"ACADEMIC_YEAR_INACTIVE"}
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        val semesterStart=java.time.LocalDate.parse(canonicalStart);val semesterEnd=java.time.LocalDate.parse(canonicalEnd)
        val yearStart=java.time.LocalDate.parse(year.startDate);val yearEnd=java.time.LocalDate.parse(year.endDate)
        require(!semesterStart.isBefore(yearStart)&&!semesterEnd.isAfter(yearEnd)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"}
        require(dao.getSemestersOnce().none{it.academicYearId==academicYearId&&it.name.equals(name.trim(),true)&&it.startDate==canonicalStart&&it.endDate==canonicalEnd}){"SEMESTER_DUPLICATE"}
        return db.withTransaction{
            require(dao.getSemestersOnce().none{it.academicYearId==academicYearId&&it.name.equals(name.trim(),true)&&it.startDate==canonicalStart&&it.endDate==canonicalEnd}){"SEMESTER_DUPLICATE"}
            val now=System.currentTimeMillis();val v=SemesterEntity(UUID.randomUUID().toString(),name.trim(),academicYearId,canonicalStart,canonicalEnd,status,now,now);dao.insertSemester(v);hierarchySyncOutbox?.recordSemester(v,now);audit(actorId,"SEMESTER_CREATED","Semester",v.id,"{\"name\":\"${safe(v.name)}\",\"status\":\"${v.status}\"}");v.id
        }
    }
    suspend fun addLevel(departmentId:String,name:String,order:Int,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"LEVEL_NAME_REQUIRED"};require(order>0){"LEVEL_ORDER_INVALID"};val parent=dao.getDepartmentById(departmentId)?:error("DEPARTMENT_NOT_FOUND");require(parent.archivedAt==null){"DEPARTMENT_ARCHIVED"};return db.withTransaction{
        val now=System.currentTimeMillis();val v=LevelEntity(UUID.randomUUID().toString(),departmentId,name.trim(),order);dao.insertLevel(v);hierarchySyncOutbox?.recordLevel(v,now);audit(actorId,"LEVEL_CREATED","Level",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addBatch(levelId:String,name:String,academicYearId:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"BATCH_NAME_REQUIRED"};val level=dao.getActiveLevelById(levelId)?:error("LEVEL_NOT_FOUND");val year=dao.getAcademicYearById(academicYearId)?:error("ACADEMIC_YEAR_NOT_FOUND");require(year.isActive){"ACADEMIC_YEAR_INACTIVE"};return db.withTransaction{
        val now=System.currentTimeMillis();val v=BatchEntity(UUID.randomUUID().toString(),level.id,name.trim(),year.id);dao.insertBatch(v);hierarchySyncOutbox?.recordBatch(v,now);audit(actorId,"BATCH_CREATED","Batch",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addSection(batchId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"SECTION_NAME_REQUIRED"};val batch=dao.getActiveBatchById(batchId)?:error("BATCH_NOT_FOUND");return db.withTransaction{
        val now=System.currentTimeMillis();val v=SectionEntity(UUID.randomUUID().toString(),batch.id,name.trim());dao.insertSection(v);hierarchySyncOutbox?.recordSection(v,now);audit(actorId,"SECTION_CREATED","Section",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addGroup(sectionId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank()){"GROUP_NAME_REQUIRED"};val section=dao.getActiveSectionById(sectionId)?:error("SECTION_NOT_FOUND");return db.withTransaction{
        val now=System.currentTimeMillis();val v=GroupEntity(UUID.randomUUID().toString(),section.id,name.trim());dao.insertGroup(v);hierarchySyncOutbox?.recordGroup(v,now);audit(actorId,"GROUP_CREATED","Group",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}

    suspend fun addTeacher(name:String,phone:String?,whatsapp:String?,email:String?,actorId:String):String{
        require(authorization.hasPermission(actorId,"MANAGE_TEACHERS")){"MANAGE_TEACHERS_PERMISSION_REQUIRED"};require(name.isNotBlank()){"TEACHER_NAME_REQUIRED"};AcademicValidationRules.validateContact(phone,whatsapp,email)?.let{error(it)}
        return db.withTransaction{val now=System.currentTimeMillis();val v=TeacherEntity(UUID.randomUUID().toString(),name.trim(),ArabicNormalizer.normalize(name),phone.blankToNull(),whatsapp.blankToNull(),email.blankToNull(),null,true,now,now);dao.insertTeacher(v);audit(actorId,"TEACHER_CREATED","Teacher",v.id,"{\"name\":\"${safe(v.fullName)}\"}");syncOutbox?.enqueueTeacherIfEnabled(v,now);v.id}
    }
    suspend fun archiveTeacher(id:String,actorId:String,reason:String){
        require(authorization.hasPermission(actorId,"MANAGE_TEACHERS")){"MANAGE_TEACHERS_PERMISSION_REQUIRED"};require(reason.isNotBlank())
        db.withTransaction{val v=dao.getTeacherById(id)?:error("TEACHER_NOT_FOUND");require(v.archivedAt==null){"TEACHER_ALREADY_ARCHIVED"};require(dao.countActiveSubjectsForTeacher(id)==0){"TEACHER_HAS_ACTIVE_SUBJECTS"};val now=System.currentTimeMillis();val updated=v.copy(archivedAt=now,updatedAt=now,version=v.version+1);dao.updateTeacher(updated);audit(actorId,"TEACHER_ARCHIVED","Teacher",id,"{\"archived\":true}",reason);syncOutbox?.enqueueTeacherIfEnabled(updated,now)}
    }
    suspend fun addAttendancePolicy(name:String,full:Double,partial:Double,lateMinutes:Int,earlyLeaveMinutes:Int,absence:Double,missingGraceSeconds:Long,minimumVerificationSeconds:Long,confidence:Double,actorId:String):String{
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"}
        require(name.isNotBlank()){"ATTENDANCE_POLICY_NAME_REQUIRED"};validatePolicyValues(full,partial,lateMinutes,earlyLeaveMinutes,absence,missingGraceSeconds,minimumVerificationSeconds,confidence)
        return db.withTransaction{val now=System.currentTimeMillis();val v=AttendancePolicyEntity(UUID.randomUUID().toString(),name.trim(),"GLOBAL",null,full,partial,lateMinutes,earlyLeaveMinutes,absence,missingGraceSeconds,minimumVerificationSeconds,confidence,now,now);dao.upsertAttendancePolicy(v);audit(actorId,"ATTENDANCE_POLICY_CREATED","AttendancePolicy",v.id,"{\"name\":\"${safe(v.name)}\"}");syncOutbox?.enqueueAttendancePolicyIfEnabled(v,now);v.id}
    }
    suspend fun addSubject(code:String,name:String,teacherId:String,levelId:String,semesterId:String,groupId:String,policyId:String?,actorId:String):String{
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"};require(code.isNotBlank()&&name.isNotBlank());require(authorization.canAccessGroup(actorId,groupId)){"OUTSIDE_ACADEMIC_SCOPE"}
        val cleanCode=code.trim();require(dao.getSubjectByCode(cleanCode)==null){"SUBJECT_CODE_DUPLICATE"}
        val teacher=dao.getTeacherById(teacherId)?:error("TEACHER_NOT_FOUND");require(teacher.archivedAt==null){"TEACHER_ARCHIVED"}
        val level=dao.getActiveLevelById(levelId)?:error("LEVEL_NOT_FOUND");val group=dao.getActiveGroupById(groupId)?:error("GROUP_NOT_FOUND")
        val section=dao.getActiveSectionById(group.sectionId)?:error("SECTION_NOT_FOUND");val batch=dao.getActiveBatchById(section.batchId)?:error("BATCH_NOT_FOUND");require(batch.levelId==level.id){"SUBJECT_GROUP_LEVEL_MISMATCH"}
        val semester=dao.getSemesterById(semesterId)?:error("SEMESTER_NOT_FOUND");require(semester.status !in setOf(SemesterStatus.COMPLETED,SemesterStatus.ARCHIVED)){"SEMESTER_NOT_ACTIVE_FOR_SUBJECT"};require(semester.academicYearId==batch.academicYearId){"SUBJECT_SEMESTER_BATCH_YEAR_MISMATCH"}
        val validPolicyId=policyId?.takeIf{it.isNotBlank()}?:error("ATTENDANCE_POLICY_REQUIRED");require(dao.getAttendancePolicyById(validPolicyId)!=null){"ATTENDANCE_POLICY_NOT_FOUND"}
        return db.withTransaction{require(dao.getSubjectByCode(cleanCode)==null){"SUBJECT_CODE_DUPLICATE"};val now=System.currentTimeMillis();val v=SubjectEntity(UUID.randomUUID().toString(),cleanCode,name.trim(),teacher.id,level.id,semester.id,group.id,validPolicyId,"ACTIVE");dao.insertSubject(v);dao.insertTeacherSubject(TeacherSubjectEntity(teacherId,v.id));graphSyncOutbox?.recordSubject(v,now);audit(actorId,"SUBJECT_CREATED","Subject",v.id,"{\"code\":\"${safe(v.code)}\",\"name\":\"${safe(v.name)}\",\"attendancePolicyId\":\"$validPolicyId\"}");v.id}
    }
    suspend fun updateSubjectAttendancePolicy(subjectId:String,policyId:String,actorId:String){
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"};require(policyId.isNotBlank()){"ATTENDANCE_POLICY_REQUIRED"}
        db.withTransaction{val subject=dao.getSubjectById(subjectId)?:error("SUBJECT_NOT_FOUND");require(subject.archivedAt==null&&subject.status!="ARCHIVED"){"SUBJECT_ARCHIVED"};require(authorization.canAccessGroup(actorId,subject.groupId)){"OUTSIDE_ACADEMIC_SCOPE"};require(dao.getAttendancePolicyById(policyId)!=null){"ATTENDANCE_POLICY_NOT_FOUND"};val now=System.currentTimeMillis();val updated=subject.copy(attendancePolicyId=policyId,version=subject.version+1);dao.updateSubject(updated);graphSyncOutbox?.recordSubject(updated,now);audit(actorId,"SUBJECT_ATTENDANCE_POLICY_UPDATED","Subject",subject.id,"{\"attendancePolicyId\":\"$policyId\"}")}
    }
    suspend fun archiveSubject(id:String,actorId:String,reason:String){
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"};require(reason.isNotBlank())
        db.withTransaction{val v=dao.getSubjectById(id)?:error("SUBJECT_NOT_FOUND");require(v.archivedAt==null&&v.status!="ARCHIVED"){"SUBJECT_ALREADY_ARCHIVED"};require(authorization.canAccessGroup(actorId,v.groupId)){"OUTSIDE_ACADEMIC_SCOPE"};require(dao.countActiveTimetablesForSubject(id)==0){"SUBJECT_HAS_ACTIVE_TIMETABLE"};val now=System.currentTimeMillis();val updated=v.copy(status="ARCHIVED",archivedAt=now,version=v.version+1);dao.updateSubject(updated);graphSyncOutbox?.recordSubject(updated,now);audit(actorId,"SUBJECT_ARCHIVED","Subject",id,"{\"archived\":true}",reason)}
    }

    data class RolloverOptions(val copySubjects:Boolean=true,val copyTimetableTemplate:Boolean=false)
    data class RolloverResult(val newSemesterId:String,val copiedSubjects:Int,val copiedTimetableRows:Int)
    suspend fun closeSemesterAndStartNew(oldSemesterId:String,newName:String,newAcademicYearId:String,startDate:String,endDate:String,actorId:String,options:RolloverOptions=RolloverOptions()):RolloverResult{
        requireAcademic(actorId);require(newName.isNotBlank()){"SEMESTER_NAME_REQUIRED"}
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        val year=dao.getAcademicYearById(newAcademicYearId)?:error("ACADEMIC_YEAR_NOT_FOUND");require(year.isActive){"ACADEMIC_YEAR_INACTIVE"}
        val semesterStart=java.time.LocalDate.parse(canonicalStart);val semesterEnd=java.time.LocalDate.parse(canonicalEnd)
        val yearStart=java.time.LocalDate.parse(year.startDate);val yearEnd=java.time.LocalDate.parse(year.endDate)
        require(!semesterStart.isBefore(yearStart)&&!semesterEnd.isAfter(yearEnd)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"}
        return db.withTransaction{
            require(dao.countActiveLecturesInSemester(oldSemesterId)==0){"ACTIVE_LECTURES_EXIST"}
            require(dao.countPendingReviewsInSemester(oldSemesterId)==0){"PENDING_ATTENDANCE_REVIEWS_EXIST"}
            val old=dao.getSemesterById(oldSemesterId)?:error("SEMESTER_NOT_FOUND");require(old.status !in setOf(SemesterStatus.COMPLETED,SemesterStatus.ARCHIVED)){"SEMESTER_ALREADY_CLOSED"};require(dao.getSemestersOnce().none{it.id!=old.id&&it.academicYearId==newAcademicYearId&&it.name.equals(newName.trim(),true)&&it.startDate==canonicalStart&&it.endDate==canonicalEnd}){"SEMESTER_ROLLOVER_DUPLICATE"}
            val now=System.currentTimeMillis();val completed=old.copy(status=SemesterStatus.COMPLETED,updatedAt=now);dao.updateSemester(completed);hierarchySyncOutbox?.recordSemester(completed,now)
            val newId=UUID.randomUUID().toString();val newSemester=SemesterEntity(newId,newName.trim(),newAcademicYearId,canonicalStart,canonicalEnd,SemesterStatus.UPCOMING,now,now);dao.insertSemester(newSemester);hierarchySyncOutbox?.recordSemester(newSemester,now)
            var copied=0;var timetableRows=0
            if(options.copySubjects){
                val sourceSubjects=dao.getSubjectsForSemester(oldSemesterId)
                sourceSubjects.forEach{subject->
                    val group=dao.getActiveGroupById(subject.groupId)?:error("GROUP_NOT_FOUND")
                    val section=dao.getActiveSectionById(group.sectionId)?:error("SECTION_NOT_FOUND")
                    val batch=dao.getActiveBatchById(section.batchId)?:error("BATCH_NOT_FOUND")
                    require(batch.academicYearId==newAcademicYearId){"ROLLOVER_TARGET_GROUP_YEAR_MISMATCH"}
                }
                val mapping=mutableMapOf<String,String>()
                for(subject in sourceSubjects){
                    val newSubjectId=UUID.randomUUID().toString();mapping[subject.id]=newSubjectId
                    val copiedSubject=subject.copy(id=newSubjectId,semesterId=newId,version=1,archivedAt=null,status="ACTIVE");dao.insertSubject(copiedSubject);subject.teacherId?.let{dao.insertTeacherSubject(TeacherSubjectEntity(it,newSubjectId))};graphSyncOutbox?.recordSubject(copiedSubject,now);copied++
                }
                if(options.copyTimetableTemplate){
                    for((oldSubject,newSubject) in mapping)for(row in dao.getTimetablesForSubject(oldSubject)){
                        dao.upsertTimetable(row.copy(id=UUID.randomUUID().toString(),subjectId=newSubject,isActive=false,createdAt=now,updatedAt=now,version=1,weeklyScheduleId=null));timetableRows++
                    }
                }
            }
            audit(actorId,"SEMESTER_ROLLOVER","Semester",newId,"{\"from\":\"$oldSemesterId\",\"subjects\":$copied,\"timetableTemplates\":$timetableRows}")
            RolloverResult(newId,copied,timetableRows)
        }
    }

    private fun validatePolicyValues(full:Double,partial:Double,lateMinutes:Int,earlyLeaveMinutes:Int,absence:Double,missingGraceSeconds:Long,minimumVerificationSeconds:Long,confidence:Double){
        require(full in 0.0..1.0&&partial in 0.0..1.0&&absence in 0.0..1.0&&confidence in 0.0..1.0){"ATTENDANCE_POLICY_THRESHOLD_OUT_OF_RANGE"}
        require(full>=partial&&partial>=absence){"ATTENDANCE_POLICY_THRESHOLD_ORDER_INVALID"}
        require(lateMinutes>=0&&earlyLeaveMinutes>=0&&missingGraceSeconds>=0&&minimumVerificationSeconds>=0){"ATTENDANCE_POLICY_DURATION_INVALID"}
    }

    private fun safe(v:String)=v.replace("\\","\\\\").replace("\"","'")
    private fun String?.blankToNull()=this?.trim()?.ifBlank{null}
}
