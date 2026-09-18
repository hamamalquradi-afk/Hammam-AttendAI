package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.ReportJobStatus
import com.hammam.attendai.reports.ReportDeduplication
import java.time.*
import java.util.UUID

class ReportRepository(private val db:HammamDatabase){
    private val dao=db.coreDao()
    fun observeSettings()=dao.observeReportSettings()
    fun observeAllSettings()=dao.observeAllReportSettings()
    fun observeHistory()=dao.observeReportHistory()

    suspend fun saveSetting(setting:TeacherReportSettingEntity,actorId:String?=null)=db.withTransaction{
        dao.upsertTeacherReportSetting(setting)
        actorId?.let{dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),it,"TEACHER_REPORT_SETTING_CHANGED","TeacherReportSetting",setting.id,null,"{\"teacherId\":\"${setting.teacherId}\",\"frequency\":\"${setting.frequency}\"}",null,System.currentTimeMillis()))}
    }

    suspend fun enqueueToday(setting:TeacherReportSettingEntity):Boolean{
        val zone=zone(setting.timezone);val today=LocalDate.now(zone)
        return enqueuePeriod(setting,"DAILY",today.atStartOfDay(zone).toInstant().toEpochMilli(),today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),System.currentTimeMillis())
    }

    suspend fun enqueueForLecture(lecture:LectureEntity,reportType:String="CUSTOM"):Boolean{
        val start=lecture.scheduledStart;val end=(lecture.actualEnd ?: lecture.scheduledEnd).coerceAtLeast(start+1)
        val key=ReportDeduplication.key(lecture.teacherId,lecture.subjectId,reportType,start,end)
        val job=ReportJobEntity(UUID.randomUUID().toString(),lecture.teacherId,lecture.subjectId,reportType,start,end,System.currentTimeMillis(),null,null,ReportJobStatus.SCHEDULED,0,null,null,key,1)
        return dao.enqueueReport(job)>0
    }

    suspend fun scheduleDue(nowMillis:Long=System.currentTimeMillis()):Int{
        var count=0
        for(setting in dao.getEnabledReportSettings()){
            val z=zone(setting.timezone);val now=Instant.ofEpochMilli(nowMillis).atZone(z);val send=parseTime(setting.sendTime)
            val frequencies=setting.frequency.split(',', ';', ' ').map{it.trim().uppercase()}.filter{it.isNotBlank()}.toSet()
            if("DAILY" in frequencies && now.toLocalTime()>=send){
                val d=now.toLocalDate(); if(enqueuePeriod(setting,"DAILY",d.atStartOfDay(z).toInstant().toEpochMilli(),d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(),d.atTime(send).atZone(z).toInstant().toEpochMilli()))count++
            }
            if("WEEKLY" in frequencies && now.dayOfWeek.value==(setting.weeklyDay ?: 7).coerceIn(1,7) && now.toLocalTime()>=send){
                val endDate=now.toLocalDate().plusDays(1);val startDate=endDate.minusDays(7)
                if(enqueuePeriod(setting,"WEEKLY",startDate.atStartOfDay(z).toInstant().toEpochMilli(),endDate.atStartOfDay(z).toInstant().toEpochMilli(),now.toLocalDate().atTime(send).atZone(z).toInstant().toEpochMilli()))count++
            }
            if("MONTHLY" in frequencies){
                val d=now.toLocalDate();val configured=(setting.monthlyDay ?: d.lengthOfMonth()).coerceAtLeast(1);val dueDay=configured.coerceAtMost(d.lengthOfMonth())
                if(d.dayOfMonth==dueDay && now.toLocalTime()>=send){
                    val first=d.withDayOfMonth(1);val next=first.plusMonths(1)
                    if(enqueuePeriod(setting,"MONTHLY",first.atStartOfDay(z).toInstant().toEpochMilli(),next.atStartOfDay(z).toInstant().toEpochMilli(),d.atTime(send).atZone(z).toInstant().toEpochMilli()))count++
                }
            }
            if(setting.semesterReportEnabled || "END_OF_SEMESTER" in frequencies){
                val subject=setting.subjectId?.let{dao.getSubjectById(it)}
                val semester=subject?.let{dao.getSemesterById(it.semesterId)}
                if(semester!=null){
                    val endDate=runCatching{LocalDate.parse(semester.endDate)}.getOrNull();val startDate=runCatching{LocalDate.parse(semester.startDate)}.getOrNull()
                    if(endDate!=null&&startDate!=null&&now.toLocalDate()>=endDate&&now.toLocalTime()>=send){
                        if(enqueuePeriod(setting,"END_OF_SEMESTER",startDate.atStartOfDay(z).toInstant().toEpochMilli(),endDate.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(),endDate.atTime(send).atZone(z).toInstant().toEpochMilli()))count++
                    }
                }
            }
        }
        return count
    }

    suspend fun approve(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(job.status!=ReportJobStatus.PENDING_APPROVAL)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=ReportJobStatus.PENDING_SEND,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_APPROVED","ReportJob",job.id,"{\"status\":\"PENDING_APPROVAL\"}","{\"status\":\"PENDING_SEND\"}",null,now));true
    }

    suspend fun cancel(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(job.status==ReportJobStatus.SENT || job.status==ReportJobStatus.CANCELLED)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=ReportJobStatus.CANCELLED,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_CANCELLED","ReportJob",job.id,null,"{\"status\":\"CANCELLED\"}",null,now));true
    }

    suspend fun regenerate(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(job.status==ReportJobStatus.SENT)return@withTransaction false
        dao.deleteGeneratedReports(jobId)
        val now=System.currentTimeMillis();dao.updateReport(job.copy(generatedAt=null,sentAt=null,status=ReportJobStatus.SCHEDULED,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_REGENERATE_REQUESTED","ReportJob",job.id,null,null,null,now));true
    }

    suspend fun generated(jobId:String):GeneratedReportEntity?=dao.getGeneratedReport(jobId)

    suspend fun retry(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(job.status!=ReportJobStatus.FAILED)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=if(job.generatedAt==null)ReportJobStatus.SCHEDULED else ReportJobStatus.PENDING_SEND,retryCount=0,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_RETRY_REQUESTED","ReportJob",job.id,null,null,null,now));true
    }

    private suspend fun enqueuePeriod(setting:TeacherReportSettingEntity,type:String,start:Long,end:Long,scheduledAt:Long):Boolean{
        if(!setting.sendIfNoLecture && dao.countLecturesForReport(setting.teacherId,setting.subjectId,start,end)==0)return false
        val key=ReportDeduplication.key(setting.teacherId,setting.subjectId,type,start,end)
        val job=ReportJobEntity(UUID.randomUUID().toString(),setting.teacherId,setting.subjectId,type,start,end,scheduledAt,null,null,ReportJobStatus.SCHEDULED,0,null,null,key,1)
        return dao.enqueueReport(job)>0
    }
    private fun zone(id:String)=runCatching{ZoneId.of(id)}.getOrElse{ZoneId.systemDefault()}
    private fun parseTime(value:String)=runCatching{LocalTime.parse(value)}.getOrElse{LocalTime.of(18,0)}
}
