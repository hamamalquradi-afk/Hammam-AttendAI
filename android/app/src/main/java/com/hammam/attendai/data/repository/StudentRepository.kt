package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.data.local.entity.StudentEntity
import com.hammam.attendai.domain.model.StudentStatus
import com.hammam.attendai.importexport.StudentImportPreview
import com.hammam.attendai.importexport.StudentImportRow
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicGraphSyncOutbox
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class StudentRepository(private val db:HammamDatabase, cipher:KeystoreCipher?=null) {
    private val syncOutbox=cipher?.let{AcademicGraphSyncOutbox(db,it)}
    private val dao=db.coreDao()
    fun observeAll():Flow<List<StudentEntity>> = dao.observeStudents()
    fun observeScoped(userId:String):Flow<List<StudentEntity>> = dao.observeScopedStudents(userId)
    fun search(query:String):Flow<List<StudentEntity>> = dao.searchStudents(ArabicNormalizer.normalize(query))
    suspend fun add(fullName:String, universityNumber:String?, levelId:String, batchId:String, sectionId:String, groupId:String, actorId:String?) = db.withTransaction {
        require(fullName.isNotBlank()){"EMPTY_NAME"}
        validateAcademicScope(levelId,batchId,sectionId,groupId)
        val now=System.currentTimeMillis()
        val s=StudentEntity(UUID.randomUUID().toString(), universityNumber?.trim()?.ifBlank{null}, fullName.trim(), ArabicNormalizer.normalize(fullName), null,null,levelId,batchId,sectionId,groupId, StudentStatus.ACTIVE,null,now,now)
        dao.insertStudent(s)
        syncOutbox?.recordStudent(s,now)
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"STUDENT_CREATED","Student",s.id,null,"{\"name\":\"${s.fullName.replace("\"","'")}\",\"levelId\":\"$levelId\",\"batchId\":\"$batchId\",\"sectionId\":\"$sectionId\",\"groupId\":\"$groupId\"}",null,now))
    }

    suspend fun updateAcademicScope(studentId:String,levelId:String,batchId:String,sectionId:String,groupId:String,actorId:String?) = db.withTransaction {
        validateAcademicScope(levelId,batchId,sectionId,groupId)
        val student=dao.getStudentById(studentId)?:error("STUDENT_NOT_FOUND")
        val now=System.currentTimeMillis()
        val updated=student.copy(levelId=levelId,batchId=batchId,sectionId=sectionId,groupId=groupId,updatedAt=now,version=student.version+1)
        dao.updateStudent(updated)
        syncOutbox?.recordStudent(updated,now)
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"STUDENT_ACADEMIC_SCOPE_UPDATED","Student",student.id,null,"{\"levelId\":\"$levelId\",\"batchId\":\"$batchId\",\"sectionId\":\"$sectionId\",\"groupId\":\"$groupId\"}",null,now))
    }

    private suspend fun validateAcademicScope(levelId:String,batchId:String,sectionId:String,groupId:String){
        require(levelId.isNotBlank()&&batchId.isNotBlank()&&sectionId.isNotBlank()&&groupId.isNotBlank()){"STUDENT_ACADEMIC_SCOPE_REQUIRED"}
        val level=dao.getActiveLevelById(levelId)?:error("STUDENT_LEVEL_NOT_FOUND")
        val batch=dao.getActiveBatchById(batchId)?:error("STUDENT_BATCH_NOT_FOUND")
        require(batch.levelId==level.id){"STUDENT_BATCH_LEVEL_MISMATCH"}
        val section=dao.getActiveSectionById(sectionId)?:error("STUDENT_SECTION_NOT_FOUND")
        require(section.batchId==batch.id){"STUDENT_SECTION_BATCH_MISMATCH"}
        val group=dao.getActiveGroupById(groupId)?:error("STUDENT_GROUP_NOT_FOUND")
        require(group.sectionId==section.id){"STUDENT_GROUP_SECTION_MISMATCH"}
    }

    suspend fun validateImport(preview:StudentImportPreview):StudentImportPreview{
        val all=preview.valid+preview.invalid
        val rows=all.map{r->
            val errors=r.errors.toMutableList();val number=r.universityNumber
            if(number!=null&&dao.getStudentByUniversityNumber(number)!=null)errors+="EXISTING_UNIVERSITY_NUMBER"
            r.copy(errors=errors.distinct())
        }
        return StudentImportPreview(rows.filter{it.errors.isEmpty()},rows.filter{it.errors.isNotEmpty()})
    }

    suspend fun confirmImport(rows:List<StudentImportRow>,actorId:String):Int=db.withTransaction{
        var count=0;val now=System.currentTimeMillis()
        for(r in rows){
            require(r.errors.isEmpty()){"INVALID_IMPORT_ROW_${r.line}"};require(r.fullName.isNotBlank()){"EMPTY_NAME"}
            r.universityNumber?.let{require(dao.getStudentByUniversityNumber(it)==null){"EXISTING_UNIVERSITY_NUMBER:$it"}}
            val st=StudentEntity(UUID.randomUUID().toString(),r.universityNumber,r.fullName.trim(),ArabicNormalizer.normalize(r.fullName),r.phone,null,null,null,null,null,StudentStatus.ACTIVE,null,now,now)
            dao.insertStudent(st);syncOutbox?.recordStudent(st,now);count++
        }
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"STUDENT_BULK_IMPORT","Student",null,null,"{\"count\":$count}",null,now));count
    }
}

object ArabicNormalizer {
    fun normalize(input:String):String = input.trim().lowercase()
        .replace(Regex("[أإآٱ]"),"ا")
        .replace("ى","ي").replace("ة","ه")
        .replace(Regex("[ًٌٍَُِّْـ]"),"")
        .replace(Regex("\\s+")," ")
}
