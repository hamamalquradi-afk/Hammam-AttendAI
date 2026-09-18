package com.hammam.attendai.data.repository

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.reports.PdfReportGenerator
import kotlinx.coroutines.flow.Flow
import java.io.File

class AuditRepository(private val db:HammamDatabase){
    private val dao=db.coreDao()
    fun observeEntity(entityType:String,entityId:String):Flow<List<AuditLogEntity>> = dao.observeEntityAudit(entityType,entityId)
    suspend fun query(start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null)=dao.queryAudit(start,end,actorId,actorRole,entityType,action)
    suspend fun exportCsv(file:File,start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null):File{
        val rows=query(start,end,actorId,actorRole,entityType,action);file.parentFile?.mkdirs()
        file.bufferedWriter().use{w->
            w.appendLine("timestamp,actor_id,actor_role,action,entity_type,entity_id,reason")
            rows.forEach{r->w.appendLine(listOf(r.timestamp,r.actorId.orEmpty(),r.actorRole.orEmpty(),r.action,r.entityType,r.entityId.orEmpty(),r.reason.orEmpty()).joinToString(","){csv(it.toString())})}
        };return file
    }
    suspend fun exportPdf(file:File,start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null):File{
        val rows=query(start,end,actorId,actorRole,entityType,action)
        return PdfReportGenerator().generateAudit(file,rows).file
    }
    private fun csv(v:String)="\"${v.replace("\"","\"\"")}\""
}
