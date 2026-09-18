package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.domain.setup.DateInputNormalizer
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Mutations for the existing academic structure. Authorization and audit live here, not only in UI. */
class AcademicManagementRepository(
    private val db:HammamDatabase,
    private val authorization:AuthorizationRepository,
){
    private val dao=db.coreDao()

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

    private suspend fun requireAcademic(actorId:String){
        require(authorization.hasPermission(actorId,"MANAGE_ACADEMIC_STRUCTURE")){"MANAGE_ACADEMIC_STRUCTURE_PERMISSION_REQUIRED"}
    }
    private suspend fun audit(actorId:String,action:String,type:String,id:String,newData:String?,reason:String?=null){
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,action,type,id,null,newData,reason,System.currentTimeMillis(),authorization.roleNames(actorId).firstOrNull()))
    }

    suspend fun addUniversity(name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=UniversityEntity(UUID.randomUUID().toString(),name.trim());dao.insertUniversity(v);audit(actorId,"UNIVERSITY_CREATED","University",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addFaculty(universityId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=FacultyEntity(UUID.randomUUID().toString(),universityId,name.trim());dao.insertFaculty(v);audit(actorId,"FACULTY_CREATED","Faculty",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addDepartment(facultyId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=DepartmentEntity(UUID.randomUUID().toString(),facultyId,name.trim());dao.insertDepartment(v);audit(actorId,"DEPARTMENT_CREATED","Department",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addAcademicYear(name:String,startDate:String,endDate:String,actorId:String):String{
        requireAcademic(actorId);require(name.isNotBlank()){"ACADEMIC_YEAR_NAME_REQUIRED"}
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        return db.withTransaction{
            val v=AcademicYearEntity(UUID.randomUUID().toString(),name.trim(),canonicalStart,canonicalEnd,true);dao.insertAcademicYear(v);audit(actorId,"ACADEMIC_YEAR_CREATED","AcademicYear",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
        }
    }
    suspend fun addSemester(name:String,academicYearId:String,startDate:String,endDate:String,actorId:String,status:SemesterStatus=SemesterStatus.UPCOMING):String{
        requireAcademic(actorId);require(name.isNotBlank()){"SEMESTER_NAME_REQUIRED"}
        val year=dao.getAcademicYearById(academicYearId)?:error("ACADEMIC_YEAR_NOT_FOUND")
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        val semesterStart=java.time.LocalDate.parse(canonicalStart);val semesterEnd=java.time.LocalDate.parse(canonicalEnd)
        val yearStart=java.time.LocalDate.parse(year.startDate);val yearEnd=java.time.LocalDate.parse(year.endDate)
        require(!semesterStart.isBefore(yearStart)&&!semesterEnd.isAfter(yearEnd)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"}
        return db.withTransaction{
            val now=System.currentTimeMillis();val v=SemesterEntity(UUID.randomUUID().toString(),name.trim(),academicYearId,canonicalStart,canonicalEnd,status,now,now);dao.insertSemester(v);audit(actorId,"SEMESTER_CREATED","Semester",v.id,"{\"name\":\"${safe(v.name)}\",\"status\":\"${v.status}\"}");v.id
        }
    }
    suspend fun addLevel(departmentId:String,name:String,order:Int,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=LevelEntity(UUID.randomUUID().toString(),departmentId,name.trim(),order);dao.insertLevel(v);audit(actorId,"LEVEL_CREATED","Level",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addBatch(levelId:String,name:String,academicYearId:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=BatchEntity(UUID.randomUUID().toString(),levelId,name.trim(),academicYearId);dao.insertBatch(v);audit(actorId,"BATCH_CREATED","Batch",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addSection(batchId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=SectionEntity(UUID.randomUUID().toString(),batchId,name.trim());dao.insertSection(v);audit(actorId,"SECTION_CREATED","Section",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}
    suspend fun addGroup(sectionId:String,name:String,actorId:String):String{ requireAcademic(actorId);require(name.isNotBlank());return db.withTransaction{
        val v=GroupEntity(UUID.randomUUID().toString(),sectionId,name.trim());dao.insertGroup(v);audit(actorId,"GROUP_CREATED","Group",v.id,"{\"name\":\"${safe(v.name)}\"}");v.id
    }}

    suspend fun addTeacher(name:String,phone:String?,whatsapp:String?,email:String?,actorId:String):String{
        require(authorization.hasPermission(actorId,"MANAGE_TEACHERS")){"MANAGE_TEACHERS_PERMISSION_REQUIRED"};require(name.isNotBlank())
        return db.withTransaction{val now=System.currentTimeMillis();val v=TeacherEntity(UUID.randomUUID().toString(),name.trim(),ArabicNormalizer.normalize(name),phone.blankToNull(),whatsapp.blankToNull(),email.blankToNull(),null,true,now,now);dao.insertTeacher(v);audit(actorId,"TEACHER_CREATED","Teacher",v.id,"{\"name\":\"${safe(v.fullName)}\"}");v.id}
    }
    suspend fun archiveTeacher(id:String,actorId:String,reason:String){
        require(authorization.hasPermission(actorId,"MANAGE_TEACHERS")){"MANAGE_TEACHERS_PERMISSION_REQUIRED"};require(reason.isNotBlank())
        db.withTransaction{val v=dao.getTeacherById(id)?:error("TEACHER_NOT_FOUND");val now=System.currentTimeMillis();dao.updateTeacher(v.copy(archivedAt=now,updatedAt=now,version=v.version+1));audit(actorId,"TEACHER_ARCHIVED","Teacher",id,"{\"archived\":true}",reason)}
    }
    suspend fun addSubject(code:String,name:String,teacherId:String,levelId:String,semesterId:String,groupId:String,policyId:String?,actorId:String):String{
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"};require(code.isNotBlank()&&name.isNotBlank());require(authorization.canAccessGroup(actorId,groupId)){"OUTSIDE_ACADEMIC_SCOPE"}
        return db.withTransaction{val v=SubjectEntity(UUID.randomUUID().toString(),code.trim(),name.trim(),teacherId,levelId,semesterId,groupId,policyId,"ACTIVE");dao.insertSubject(v);dao.insertTeacherSubject(TeacherSubjectEntity(teacherId,v.id));audit(actorId,"SUBJECT_CREATED","Subject",v.id,"{\"code\":\"${safe(v.code)}\",\"name\":\"${safe(v.name)}\"}");v.id}
    }
    suspend fun archiveSubject(id:String,actorId:String,reason:String){
        require(authorization.hasPermission(actorId,"MANAGE_SUBJECTS")){"MANAGE_SUBJECTS_PERMISSION_REQUIRED"};require(reason.isNotBlank())
        db.withTransaction{val v=dao.getSubjectById(id)?:error("SUBJECT_NOT_FOUND");require(authorization.canAccessGroup(actorId,v.groupId)){"OUTSIDE_ACADEMIC_SCOPE"};val now=System.currentTimeMillis();dao.updateSubject(v.copy(status="ARCHIVED",archivedAt=now,version=v.version+1));audit(actorId,"SUBJECT_ARCHIVED","Subject",id,"{\"archived\":true}",reason)}
    }

    data class RolloverOptions(val copySubjects:Boolean=true,val copyTimetableTemplate:Boolean=false)
    data class RolloverResult(val newSemesterId:String,val copiedSubjects:Int,val copiedTimetableRows:Int)
    suspend fun closeSemesterAndStartNew(oldSemesterId:String,newName:String,newAcademicYearId:String,startDate:String,endDate:String,actorId:String,options:RolloverOptions=RolloverOptions()):RolloverResult{
        requireAcademic(actorId);require(newName.isNotBlank()){"SEMESTER_NAME_REQUIRED"}
        val (canonicalStart,canonicalEnd)=DateInputNormalizer.canonicalRange(startDate,endDate)
        val year=dao.getAcademicYearById(newAcademicYearId)?:error("ACADEMIC_YEAR_NOT_FOUND")
        val semesterStart=java.time.LocalDate.parse(canonicalStart);val semesterEnd=java.time.LocalDate.parse(canonicalEnd)
        val yearStart=java.time.LocalDate.parse(year.startDate);val yearEnd=java.time.LocalDate.parse(year.endDate)
        require(!semesterStart.isBefore(yearStart)&&!semesterEnd.isAfter(yearEnd)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"}
        return db.withTransaction{
            require(dao.countActiveLecturesInSemester(oldSemesterId)==0){"ACTIVE_LECTURES_EXIST"}
            require(dao.countPendingReviewsInSemester(oldSemesterId)==0){"PENDING_ATTENDANCE_REVIEWS_EXIST"}
            val old=dao.getSemesterById(oldSemesterId)?:error("SEMESTER_NOT_FOUND")
            val now=System.currentTimeMillis();dao.updateSemester(old.copy(status=SemesterStatus.COMPLETED,updatedAt=now))
            val newId=UUID.randomUUID().toString();dao.insertSemester(SemesterEntity(newId,newName.trim(),newAcademicYearId,canonicalStart,canonicalEnd,SemesterStatus.UPCOMING,now,now))
            var copied=0;var timetableRows=0
            if(options.copySubjects){
                val mapping=mutableMapOf<String,String>()
                for(subject in dao.getSubjectsForSemester(oldSemesterId)){
                    val newSubjectId=UUID.randomUUID().toString();mapping[subject.id]=newSubjectId
                    dao.insertSubject(subject.copy(id=newSubjectId,semesterId=newId,version=1,archivedAt=null,status="ACTIVE"));subject.teacherId?.let{dao.insertTeacherSubject(TeacherSubjectEntity(it,newSubjectId))};copied++
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

    private fun safe(v:String)=v.replace("\\","\\\\").replace("\"","'")
    private fun String?.blankToNull()=this?.trim()?.ifBlank{null}
}
