package com.hammam.attendai.reports

import android.content.Context
import android.util.Base64
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.GeneratedReportEntity
import com.hammam.attendai.data.repository.ReportRepository
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.sync.BackendClient
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ReportProcessor(private val context:Context,private val db:HammamDatabase,private val backend:BackendClient){
    private val pdf=PdfReportGenerator()
    private val csv=CsvReportGenerator()
    private val repository=ReportRepository(db)

    suspend fun processDueJobs():Boolean{
        repository.scheduleDue()
        var all=true; val dao=db.coreDao(); val now=System.currentTimeMillis()
        for(initial in dao.pendingReports(now)){
            var job=initial
            try{
                if(job.generatedAt==null && (job.status==ReportJobStatus.SCHEDULED || job.status==ReportJobStatus.FAILED)){
                    val records=dao.getOfficialTeacherReportRows(job.teacherId,job.subjectId,job.periodStart,job.periodEnd)
                    val setting=dao.getReportSetting(job.teacherId,job.subjectId)
                    val summary=ReportSummary(
                        totalStudents=records.map{it.studentId}.distinct().size,
                        present=records.count{it.finalStatus==FinalAttendanceStatus.PRESENT},
                        absent=records.count{it.finalStatus==FinalAttendanceStatus.ABSENT},
                        late=records.count{it.finalStatus==FinalAttendanceStatus.LATE},
                        partial=records.count{it.finalStatus==FinalAttendanceStatus.PARTIAL},
                        leftEarly=records.count{it.finalStatus==FinalAttendanceStatus.LEFT_EARLY},
                        attendanceRate=if(records.isEmpty())0.0 else records.map{it.attendancePercentage}.average()
                    )
                    val format=setting?.reportFormat?.uppercase()?.takeIf{it in setOf("PDF","CSV","SUMMARY")} ?: "PDF"
                    val output=generate(job.id,format,summary,records)
                    dao.upsertGeneratedReport(GeneratedReportEntity(UUID.randomUUID().toString(),job.id,format,output.file.absolutePath,now,output.hash,output.file.length()))
                    val next=if(setting?.requireApproval==true)ReportJobStatus.PENDING_APPROVAL else ReportJobStatus.PENDING_SEND
                    job=job.copy(generatedAt=now,status=next,errorMessage=null,version=job.version+1)
                    dao.updateReport(job)
                }
                if(job.status==ReportJobStatus.PENDING_SEND){
                    val generated=dao.getGeneratedReport(job.id) ?: throw IllegalStateException("GENERATED_REPORT_MISSING")
                    val file=File(generated.filePath);if(!file.exists())throw IllegalStateException("GENERATED_REPORT_FILE_MISSING")
                    val setting=dao.getReportSetting(job.teacherId,job.subjectId)
                    val channel=setting?.channel?.uppercase() ?: "EMAIL"
                    val payload=reportPayload(job.id,job.teacherId,job.subjectId,channel,generated.format,file,generated.hash)
                    val r=backend.post("/api/v1/reports/send",payload,"report:${job.deduplicationKey}:${job.version}")
                    if(r.ok){dao.updateReport(job.copy(status=ReportJobStatus.SENT,sentAt=System.currentTimeMillis(),providerMessageId=r.providerMessageId,errorMessage=null,version=job.version+1))}
                    else {
                        val attempts=job.retryCount+1
                        val retryAgain=r.retryable && attempts<5
                        dao.updateReport(job.copy(status=if(retryAgain)ReportJobStatus.PENDING_SEND else ReportJobStatus.FAILED,retryCount=attempts,errorMessage=r.error,version=job.version+1))
                        if(retryAgain)all=false
                    }
                }
            }catch(e:Exception){
                dao.updateReport(job.copy(status=ReportJobStatus.FAILED,retryCount=job.retryCount+1,errorMessage=e.javaClass.simpleName+":"+(e.message?:""),version=job.version+1));all=false
            }
        }
        return all
    }

    private data class Generated(val file:File,val hash:String)
    private fun generate(jobId:String,format:String,summary:ReportSummary,records:List<com.hammam.attendai.data.local.dao.ReportAttendanceRow>):Generated{
        val dir=File(context.filesDir,"reports")
        return when(format){
            "CSV"->{val o=csv.generate(File(dir,"$jobId.csv"),records);Generated(o.file,o.sha256)}
            "SUMMARY"->{
                val f=File(dir,"$jobId.txt");f.parentFile?.mkdirs();f.writeText("Hammam AttendAI\nTotal=${summary.totalStudents}\nPresent=${summary.present}\nAbsent=${summary.absent}\nLate=${summary.late}\nPartial=${summary.partial}\nLeftEarly=${summary.leftEarly}\nAttendanceRate=${summary.attendanceRate}\n")
                Generated(f,sha256(f))
            }
            else->{val o=pdf.generate(File(dir,"$jobId.pdf"),"تقرير الحضور","Report period",summary,records);Generated(o.file,o.sha256)}
        }
    }
    private fun reportPayload(jobId:String,teacherId:String,subjectId:String?,channel:String,format:String,file:File,hash:String):String{
        require(file.length()<=4L*1024L*1024L){"REPORT_TOO_LARGE_FOR_PROVIDER_RELAY"}
        val b64=Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)
        return """{"report_id":"${esc(jobId)}","teacher_id":"${esc(teacherId)}","subject_id":${subjectId?.let{"\"${esc(it)}\""}?:"null"},"channel":"${esc(channel)}","format":"${esc(format)}","sha256":"${esc(hash)}","file_name":"${esc(file.name)}","file_base64":"$b64"}"""
    }
    private fun sha256(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}
    private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"")
}
