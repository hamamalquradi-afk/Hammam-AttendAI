package com.hammam.attendai.reports

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.repository.ReportRepository
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.sync.BackendClient
import com.hammam.attendai.sync.NotificationDeliveryRules
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class ReportProcessor(private val context:Context,private val db:HammamDatabase,private val backend:BackendClient){
    private val pdf=PdfReportGenerator(); private val csv=CsvReportGenerator(); private val repository=ReportRepository(db)
    suspend fun processDueJobs():Boolean{
        repository.scheduleDue(); var all=true; val dao=db.coreDao(); val now=System.currentTimeMillis()
        recoverStaleClaims(dao,now)
        for(initial in dao.pendingReports(now)){
            val originalStatus=initial.status
            val claimMarker="REPORT_CLAIM:$now:${originalStatus.name}"
            if(dao.claimReportJob(initial.id,originalStatus,initial.version,claimMarker)!=1)continue
            var job=initial.copy(status=ReportJobStatus.GENERATING,errorMessage=claimMarker,version=initial.version+1)
            try{
                if(job.generatedAt==null && (originalStatus==ReportJobStatus.SCHEDULED || originalStatus==ReportJobStatus.FAILED)){
                    val records=dao.getOfficialTeacherReportRows(job.teacherId,job.subjectId,job.periodStart,job.periodEnd)
                    val setting=dao.getReportSetting(job.teacherId,job.subjectId)
                    val summary=ReportLifecycleRules.summary(records.map{it.finalStatus to it.attendancePercentage})
                    val format=setting?.reportFormat?.uppercase()?.takeIf{it in setOf("PDF","CSV","SUMMARY")} ?: "PDF"
                    val detailRows=if(setting?.includeStudentDetails==false) emptyList() else records
                    val output=generateAttempt(job.id,format,summary,detailRows)
                    val finalFile=reportFile(job.id,format)
                    var backup:File?=null
                    var finalInstalled=false
                    try{
                        val collision=dao.getGeneratedReportByHash(output.hash)
                        if(collision!=null && collision.reportJobId!=job.id)throw PermanentReportFailure("GENERATED_REPORT_HASH_CONFLICT")
                        val oldGenerated=dao.getGeneratedReport(job.id)
                        backup=if(finalFile.isFile) File(finalFile.parentFile,".${finalFile.name}.backup-${UUID.randomUUID()}") else null
                        if(backup!=null && !finalFile.renameTo(backup))throw IllegalStateException("GENERATED_REPORT_BACKUP_FAILED")
                        if(!output.file.renameTo(finalFile)){
                            backup?.renameTo(finalFile)
                            throw IllegalStateException("GENERATED_REPORT_FINALIZE_FAILED")
                        }
                        finalInstalled=true
                        db.withTransaction{
                            dao.deleteGeneratedReports(job.id)
                            dao.insertGeneratedReport(GeneratedReportEntity(UUID.randomUUID().toString(),job.id,format,finalFile.absolutePath,now,output.hash,finalFile.length()))
                        }
                        backup?.delete()
                        if(oldGenerated!=null && oldGenerated.filePath!=finalFile.absolutePath && dao.getGeneratedReportByFilePath(oldGenerated.filePath)==null)File(oldGenerated.filePath).delete()
                    }catch(e:CancellationException){
                        if(finalInstalled)finalFile.delete() else output.file.delete()
                        if(backup?.isFile==true)backup.renameTo(finalFile)
                        throw e
                    }catch(e:Exception){
                        if(finalInstalled)finalFile.delete() else output.file.delete()
                        if(backup?.isFile==true)backup.renameTo(finalFile)
                        throw e
                    }
                    val next=if(setting?.requireApproval==true)ReportJobStatus.PENDING_APPROVAL else ReportJobStatus.GENERATING
                    job=job.copy(generatedAt=now,status=next,errorMessage=if(next==ReportJobStatus.GENERATING)claimMarker else null,version=job.version+1);dao.updateReport(job)
                    val visibleNext=if(next==ReportJobStatus.GENERATING)ReportJobStatus.PENDING_SEND else next
                    dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"REPORT_GENERATED","ReportJob",job.id,null,"{\"status\":\"${visibleNext.name}\"}",null,now))
                } else if(originalStatus==ReportJobStatus.PENDING_SEND){
                    job=job.copy(status=ReportJobStatus.GENERATING)
                }
                if(originalStatus==ReportJobStatus.PENDING_SEND || (job.status==ReportJobStatus.GENERATING && job.generatedAt!=null)){
                    val generated=dao.getGeneratedReport(job.id) ?: throw PermanentReportFailure("GENERATED_REPORT_MISSING")
                    val file=File(generated.filePath);if(!file.isFile)throw PermanentReportFailure("GENERATED_REPORT_FILE_MISSING")
                    if(file.length()>4L*1024L*1024L)throw PermanentReportFailure("REPORT_TOO_LARGE_FOR_PROVIDER_RELAY")
                    val setting=dao.getReportSetting(job.teacherId,job.subjectId)
                    val channel=setting?.channel?.uppercase() ?: throw PermanentReportFailure("REPORT_CONFIG_INVALID")
                    if(channel !in setOf("EMAIL","WHATSAPP"))throw PermanentReportFailure("UNSUPPORTED_CHANNEL")
                    if(dao.isFeatureEnabled(channel)!=true)throw PermanentReportFailure("CHANNEL_DISABLED")
                    val teacher=dao.getTeacherById(job.teacherId) ?: throw PermanentReportFailure("RECIPIENT_NOT_FOUND")
                    if(teacher.archivedAt!=null)throw PermanentReportFailure("RECIPIENT_NOT_FOUND")
                    val recipient=if(channel=="EMAIL") NotificationDeliveryRules.validEmail(teacher.email) else NotificationDeliveryRules.normalizePhone(teacher.whatsapp?:teacher.phone)
                    if(recipient==null)throw PermanentReportFailure("RECIPIENT_NOT_FOUND")
                    val payload=reportPayload(job.id,job.teacherId,job.subjectId,channel,recipient,generated.format,file,generated.hash)
                    val r=backend.post("/api/v1/reports/send",payload,"report:${job.deduplicationKey}")
                    if(r.ok){
                        val sentAt=System.currentTimeMillis()
                        dao.updateReport(job.copy(status=ReportJobStatus.SENT,sentAt=sentAt,providerMessageId=r.providerMessageId,errorMessage=null,version=job.version+1))
                        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"REPORT_SEND_ACCEPTED","ReportJob",job.id,null,"{\"channel\":\"$channel\"}",null,sentAt))
                    }
                    else {
                        val attempts=job.retryCount+1; val retryAgain=r.retryable && attempts<5
                        val error=(if(r.retryable)"NETWORK_OR_TRANSIENT:" else "PERMANENT:")+(r.error?:"REPORT_SEND_FAILED")
                        dao.updateReport(job.copy(status=if(retryAgain)ReportJobStatus.PENDING_SEND else ReportJobStatus.FAILED,retryCount=attempts,errorMessage=error,version=job.version+1))
                        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"REPORT_SEND_FAILED","ReportJob",job.id,null,null,error,System.currentTimeMillis()))
                        if(retryAgain)all=false
                    }
                }
            }catch(e:PermanentReportFailure){dao.updateReport(job.copy(status=ReportJobStatus.FAILED,errorMessage=e.message,version=job.version+1));dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"REPORT_FAILED","ReportJob",job.id,null,null,e.message,System.currentTimeMillis()))
            }catch(e:CancellationException){
                throw e
            }catch(e:Exception){val attempts=job.retryCount+1;val retry=attempts<5;val next=if(!retry)ReportJobStatus.FAILED else if(job.generatedAt==null)ReportJobStatus.SCHEDULED else ReportJobStatus.PENDING_SEND;dao.updateReport(job.copy(status=next,retryCount=attempts,errorMessage="TRANSIENT:${e.javaClass.simpleName}:${e.message?:""}",version=job.version+1));if(retry)all=false}
        };return all
    }
    private suspend fun recoverStaleClaims(dao:com.hammam.attendai.data.local.dao.CoreDao,now:Long){
        val cutoff=now-15L*60L*1000L
        for(job in dao.generatingReports()){
            val marker=job.errorMessage?:continue
            val parts=marker.split(':')
            if(parts.size!=3 || parts[0]!="REPORT_CLAIM")continue
            val claimedAt=parts[1].toLongOrNull()?:continue
            if(claimedAt>cutoff)continue
            val original=runCatching{ReportJobStatus.valueOf(parts[2])}.getOrNull()?:continue
            val restore=if(job.generatedAt==null){
                if(original==ReportJobStatus.FAILED)ReportJobStatus.SCHEDULED else original
            }else ReportJobStatus.PENDING_SEND
            dao.recoverReportClaim(job.id,job.version,marker,restore)
        }
    }
    private class PermanentReportFailure(code:String):IllegalStateException(code)
    private data class Generated(val file:File,val hash:String)
    private fun reportFile(jobId:String,format:String):File{
        val dir=File(context.filesDir,"reports")
        val extension=when(format){"CSV"->"csv";"SUMMARY"->"txt";else->"pdf"}
        return File(dir,"$jobId.$extension")
    }
    private fun generateAttempt(jobId:String,format:String,summary:ReportSummary,records:List<com.hammam.attendai.data.local.dao.ReportAttendanceRow>):Generated{
        val finalFile=reportFile(jobId,format);finalFile.parentFile?.mkdirs()
        val attempt=File(finalFile.parentFile,".${finalFile.name}.pending-${UUID.randomUUID()}")
        return when(format){
            "CSV"->{val o=csv.generate(attempt,records);Generated(o.file,o.sha256)}
            "SUMMARY"->{attempt.writeText("Hammam AttendAI\nTotal=${summary.totalStudents}\nPresent=${summary.present}\nAbsent=${summary.absent}\nLate=${summary.late}\nPartial=${summary.partial}\nLeftEarly=${summary.leftEarly}\nAttendanceRate=${summary.attendanceRate}\n");Generated(attempt,sha256(attempt))}
            else->{val o=pdf.generate(attempt,"تقرير الحضور","Report period",summary,records);Generated(o.file,o.sha256)}
        }
    }
    private fun reportPayload(jobId:String,teacherId:String,subjectId:String?,channel:String,recipient:String,format:String,file:File,hash:String):String{
        val b64=Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)
        return """{"report_id":"${esc(jobId)}","teacher_id":"${esc(teacherId)}","subject_id":${subjectId?.let{"\"${esc(it)}\""}?:"null"},"channel":"${esc(channel)}","recipient":"${esc(recipient)}","format":"${esc(format)}","sha256":"${esc(hash)}","file_name":"${esc(file.name)}","file_base64":"$b64"}"""
    }
    private fun sha256(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}
    private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"")
}
