package com.hammam.attendai.data.repository

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.importexport.CsvCodec
import com.hammam.attendai.reports.PdfReportGenerator
import com.hammam.attendai.security.AuthorizationRepository
import kotlinx.coroutines.flow.Flow
import java.io.File

class AuditRepository(private val db:HammamDatabase,private val authorization:AuthorizationRepository){
    private val dao=db.coreDao()
    fun observeEntity(entityType:String,entityId:String):Flow<List<AuditLogEntity>> = dao.observeEntityAudit(entityType,entityId)
    suspend fun query(requesterId:String,start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null,limit:Int=5000):List<AuditLogEntity>{
        require(authorization.hasPermission(requesterId,"VIEW_AUDIT_LOG")){"VIEW_AUDIT_LOG_PERMISSION_REQUIRED"}
        require(limit in 1..5000){"AUDIT_LIMIT_INVALID"}
        if(start!=null&&end!=null)require(end>=start){"AUDIT_DATE_RANGE_INVALID"}
        return dao.queryAudit(start,end,actorId,actorRole,entityType,action,limit)
    }
    suspend fun exportCsv(requesterId:String,file:File,start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null,limit:Int=5000):File{
        require(authorization.hasPermission(requesterId,"EXPORT_AUDIT_LOG")){"EXPORT_AUDIT_LOG_PERMISSION_REQUIRED"}
        val rows=queryWithExportPermission(requesterId,start,end,actorId,actorRole,entityType,action,limit);file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use{w->
            w.appendLine("timestamp,actor_id,actor_role,action,entity_type,entity_id,reason")
            rows.forEach{r->w.appendLine(listOf(r.timestamp,r.actorId.orEmpty(),r.actorRole.orEmpty(),r.action,r.entityType,r.entityId.orEmpty(),r.reason.orEmpty()).joinToString(","){CsvCodec.escape(it.toString())})}
        };return file
    }
    suspend fun exportPdf(requesterId:String,file:File,start:Long?=null,end:Long?=null,actorId:String?=null,actorRole:String?=null,entityType:String?=null,action:String?=null,limit:Int=5000):File{
        require(authorization.hasPermission(requesterId,"EXPORT_AUDIT_LOG")){"EXPORT_AUDIT_LOG_PERMISSION_REQUIRED"}
        val rows=queryWithExportPermission(requesterId,start,end,actorId,actorRole,entityType,action,limit)
        return PdfReportGenerator().generateAudit(file,rows).file
    }
    private suspend fun queryWithExportPermission(requesterId:String,start:Long?,end:Long?,actorId:String?,actorRole:String?,entityType:String?,action:String?,limit:Int):List<AuditLogEntity>{
        require(db.coreDao().isUserActive(requesterId)){"LOCAL_SESSION_INVALID"};require(limit in 1..5000){"AUDIT_LIMIT_INVALID"}
        if(start!=null&&end!=null)require(end>=start){"AUDIT_DATE_RANGE_INVALID"}
        return dao.queryAudit(start,end,actorId,actorRole,entityType,action,limit)
    }
}
