package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.ReportJobStatus
import com.hammam.attendai.reports.ReportDeduplication
import com.hammam.attendai.reports.ReportLifecycleRules
import com.hammam.attendai.sync.NotificationDeliveryRules
import com.hammam.attendai.security.AuthorizationRepository
import java.time.*
import java.util.UUID

class ReportRepository(private val db:HammamDatabase,private val authorization:AuthorizationRepository?=null){
    private val dao=db.coreDao()
    fun observeSettings()=dao.observeReportSettings()
    fun observeAllSettings()=dao.observeAllReportSettings()
    fun observeSettingsForUser(userId:String)=dao.observeReportSettingsForUser(userId)
    fun observeReportTeachersForUser(userId:String)=dao.observeReportTeachersForUser(userId)
    fun observeReportSubjectsForUser(userId:String)=dao.observeReportSubjectsForUser(userId)
    fun observeHistory()=dao.observeReportHistory()

    suspend fun saveSetting(setting:TeacherReportSettingEntity,actorId:String?=null)=db.withTransaction{
        if(authorization!=null){
            val actor=actorId?:error("LOCAL_SESSION_REQUIRED")
            require(authorization.hasPermission(actor,"MANAGE_REPORT_SETTINGS")){"MANAGE_REPORT_SETTINGS_PERMISSION_REQUIRED"}
            require(authorization.canAccessReportTarget(actor,setting.teacherId,setting.subjectId)){"REPORT_SCOPE_PERMISSION_REQUIRED"}
        }
        val frequencies=setting.frequency.split(',').map{it.trim().uppercase()}.filter{it.isNotBlank()}.toSet()
        ReportLifecycleRules.validateSchedule(frequencies,setting.sendTime,setting.weeklyDay,setting.monthlyDay,setting.timezone,setting.customRule)?.let{error(it)}
        require(!setting.aiSummaryEnabled){"AI_SUMMARY_NOT_IMPLEMENTED"}
        require(setting.reportFormat.uppercase() in setOf("PDF","CSV","SUMMARY")){"REPORT_FORMAT_INVALID"}
        require(setting.channel.uppercase() in setOf("EMAIL","WHATSAPP")){"REPORT_CHANNEL_INVALID"}
        dao.upsertTeacherReportSetting(setting)
        actorId?.let{dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),it,"TEACHER_REPORT_SETTING_CHANGED","TeacherReportSetting",setting.id,null,"{\"teacherId\":\"${setting.teacherId}\",\"frequency\":\"${setting.frequency}\"}",null,System.currentTimeMillis()))}
    }

    suspend fun enqueueToday(setting:TeacherReportSettingEntity):Boolean{
        val zone=ZoneId.of(setting.timezone);val today=LocalDate.now(zone)
        return enqueuePeriod(setting,"DAILY",today.atStartOfDay(zone).toInstant().toEpochMilli(),today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),System.currentTimeMillis())
    }

    suspend fun enqueueForLecture(lecture:LectureEntity,reportType:String="CUSTOM",actorId:String?=null):Boolean{
        if(authorization!=null){
            val actor=actorId?:error("LOCAL_SESSION_REQUIRED")
            require(authorization.hasPermission(actor,"SEND_REPORTS")){"SEND_REPORTS_PERMISSION_REQUIRED"}
            require(authorization.canAccessReportTarget(actor,lecture.teacherId,lecture.subjectId)){"REPORT_SCOPE_PERMISSION_REQUIRED"}
        }
        require(ReportLifecycleRules.lectureEligible(lecture.status)){"REPORT_LECTURE_NOT_ELIGIBLE"}
        require(dao.getLectureById(lecture.id)?.status==lecture.status){"REPORT_LECTURE_STATE_CHANGED"}
        val official=dao.getLectureRecords(lecture.id)
        require(official.isNotEmpty() && official.all{it.approvalStatus.name in setOf("APPROVED","FROZEN")}){"REPORT_LECTURE_RECORDS_NOT_APPROVED"}
        val start=lecture.scheduledStart;val end=(lecture.actualEnd ?: lecture.scheduledEnd).coerceAtLeast(start+1)
        val setting=dao.getReportSetting(lecture.teacherId,lecture.subjectId)
        val channel=setting?.channel?.uppercase() ?: error("REPORT_CONFIG_INVALID")
        require(dao.isFeatureEnabled(channel)==true){"CHANNEL_DISABLED"}
        require(reportRecipient(setting.teacherId,channel)!=null){"RECIPIENT_NOT_FOUND"}
        val format=setting.reportFormat.uppercase()
        val key=ReportDeduplication.key(lecture.teacherId,lecture.subjectId,reportType,start,end,format)
        val job=ReportJobEntity(UUID.randomUUID().toString(),lecture.teacherId,lecture.subjectId,reportType,start,end,System.currentTimeMillis(),null,null,ReportJobStatus.SCHEDULED,0,null,null,key,1)
        val inserted=dao.enqueueReport(job)>0
        if(inserted && actorId!=null)dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_GENERATE_REQUESTED","ReportJob",job.id,null,"{\"lectureId\":\"${lecture.id}\"}",null,System.currentTimeMillis()))
        return inserted
    }

    suspend fun scheduleDue(nowMillis:Long=System.currentTimeMillis()):Int{
        if(!ReportLifecycleRules.autoReportsAllowed(dao.isFeatureEnabled("AUTO_REPORTS")==true))return 0
        var count=0
        for(setting in dao.getEnabledReportSettings()){
            val frequencies=setting.frequency.split(',', ';', ' ').map{it.trim().uppercase()}.filter{it.isNotBlank()}.toSet()
            val scheduleError=ReportLifecycleRules.validateSchedule(frequencies,setting.sendTime,setting.weeklyDay,setting.monthlyDay,setting.timezone,setting.customRule)
            if(scheduleError!=null)continue
            val z=ZoneId.of(setting.timezone);val now=Instant.ofEpochMilli(nowMillis).atZone(z);val send=LocalTime.parse(setting.sendTime)
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
                    val first=d.withDayOfMonth(1);val periodEnd=d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
                    if(enqueuePeriod(setting,"MONTHLY",first.atStartOfDay(z).toInstant().toEpochMilli(),periodEnd,d.atTime(send).atZone(z).toInstant().toEpochMilli()))count++
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
        if(authorization!=null && (!authorization.hasPermission(actorId,"APPROVE_REPORT") || !authorization.canAccessReportJob(actorId,jobId)))return@withTransaction false
        if(job.status!=ReportJobStatus.PENDING_APPROVAL)return@withTransaction false
        val generated=dao.getGeneratedReport(jobId)?:return@withTransaction false
        if(!java.io.File(generated.filePath).isFile){dao.updateReport(job.copy(status=ReportJobStatus.FAILED,errorMessage="GENERATED_REPORT_FILE_MISSING",version=job.version+1));return@withTransaction false}
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=ReportJobStatus.PENDING_SEND,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_APPROVED","ReportJob",job.id,"{\"status\":\"PENDING_APPROVAL\"}","{\"status\":\"PENDING_SEND\"}",null,now));true
    }

    suspend fun cancel(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(authorization!=null && (!authorization.hasPermission(actorId,"APPROVE_REPORT") || !authorization.canAccessReportJob(actorId,jobId)))return@withTransaction false
        if(job.status==ReportJobStatus.SENT || job.status==ReportJobStatus.CANCELLED)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=ReportJobStatus.CANCELLED,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_CANCELLED","ReportJob",job.id,null,"{\"status\":\"CANCELLED\"}",null,now));true
    }

    suspend fun regenerate(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(authorization!=null && (!authorization.hasPermission(actorId,"APPROVE_REPORT") || !authorization.canAccessReportJob(actorId,jobId)))return@withTransaction false
        if(job.status==ReportJobStatus.CANCELLED)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(generatedAt=null,sentAt=null,status=ReportJobStatus.SCHEDULED,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_REGENERATE_REQUESTED","ReportJob",job.id,null,null,null,now));true
    }

    suspend fun generated(jobId:String):GeneratedReportEntity?=dao.getGeneratedReport(jobId)

    suspend fun retry(jobId:String,actorId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(authorization!=null && (!authorization.hasPermission(actorId,"SEND_REPORTS") || !authorization.canAccessReportJob(actorId,jobId)))return@withTransaction false
        if(job.status!=ReportJobStatus.FAILED)return@withTransaction false
        if(job.errorMessage?.let{isPermanentFailure(it)}==true)return@withTransaction false
        val now=System.currentTimeMillis();dao.updateReport(job.copy(status=if(job.generatedAt==null)ReportJobStatus.SCHEDULED else ReportJobStatus.PENDING_SEND,retryCount=0,errorMessage=null,version=job.version+1))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"REPORT_RETRY_REQUESTED","ReportJob",job.id,null,null,null,now));true
    }

    private fun isPermanentFailure(error:String)=listOf("REPORT_TOO_LARGE_FOR_PROVIDER_RELAY","GENERATED_REPORT_FILE_MISSING","GENERATED_REPORT_MISSING","GENERATED_REPORT_HASH_CONFLICT","REPORT_CONFIG_INVALID","UNSUPPORTED_CHANNEL","RECIPIENT_NOT_FOUND","CHANNEL_DISABLED","REPORT_STALE_AFTER_ATTENDANCE_CHANGE","GENERATED_REPORT_HASH_MISMATCH").any{error.contains(it)}

    suspend fun markGeneratedFileMissing(jobId:String):Boolean=db.withTransaction{
        val job=dao.getReportJob(jobId)?:return@withTransaction false
        if(job.status==ReportJobStatus.SENT || job.status==ReportJobStatus.CANCELLED)return@withTransaction false
        dao.updateReport(job.copy(status=ReportJobStatus.FAILED,errorMessage="GENERATED_REPORT_FILE_MISSING",version=job.version+1));true
    }

    private suspend fun reportRecipient(teacherId:String,channel:String):String?{
        val teacher=dao.getTeacherById(teacherId)?:return null
        if(teacher.archivedAt!=null)return null
        return if(channel=="EMAIL")NotificationDeliveryRules.validEmail(teacher.email) else if(channel=="WHATSAPP")NotificationDeliveryRules.normalizePhone(teacher.whatsapp?:teacher.phone) else null
    }

    private suspend fun enqueuePeriod(setting:TeacherReportSettingEntity,type:String,start:Long,end:Long,scheduledAt:Long):Boolean{
        val channel=setting.channel.uppercase()
        if(dao.isFeatureEnabled(channel)!=true)return false
        if(reportRecipient(setting.teacherId,channel)==null)return false
        if(!setting.sendIfNoLecture && dao.countLecturesForReport(setting.teacherId,setting.subjectId,start,end)==0)return false
        val key=ReportDeduplication.key(setting.teacherId,setting.subjectId,type,start,end,setting.reportFormat.uppercase())
        val job=ReportJobEntity(UUID.randomUUID().toString(),setting.teacherId,setting.subjectId,type,start,end,scheduledAt,null,null,ReportJobStatus.SCHEDULED,0,null,null,key,1)
        return dao.enqueueReport(job)>0
    }

}
