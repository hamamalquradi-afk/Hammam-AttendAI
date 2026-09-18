package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.data.local.entity.StudentEntity
import com.hammam.attendai.domain.model.StudentStatus
import com.hammam.attendai.importexport.StudentImportPreview
import com.hammam.attendai.importexport.StudentImportRow
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class StudentRepository(private val db:HammamDatabase) {
    private val dao=db.coreDao()
    fun observeAll():Flow<List<StudentEntity>> = dao.observeStudents()
    fun observeScoped(userId:String):Flow<List<StudentEntity>> = dao.observeScopedStudents(userId)
    fun search(query:String):Flow<List<StudentEntity>> = dao.searchStudents(ArabicNormalizer.normalize(query))
    suspend fun add(fullName:String, universityNumber:String?, actorId:String?) = db.withTransaction {
        require(fullName.isNotBlank())
        val now=System.currentTimeMillis()
        val s=StudentEntity(UUID.randomUUID().toString(), universityNumber?.trim()?.ifBlank{null}, fullName.trim(), ArabicNormalizer.normalize(fullName), null,null,null,null,null,null, StudentStatus.ACTIVE,null,now,now)
        dao.insertStudent(s)
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"STUDENT_CREATED","Student",s.id,null,"{\"name\":\"${s.fullName.replace("\"","'")}\"}",null,now))
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
            dao.insertStudent(st);count++
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
