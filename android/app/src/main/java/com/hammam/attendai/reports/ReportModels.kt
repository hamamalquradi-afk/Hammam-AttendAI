package com.hammam.attendai.reports

import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.domain.model.LectureStatus
import java.security.MessageDigest
import java.time.LocalTime
import java.time.ZoneId

data class ReportSummary(val totalStudents:Int,val present:Int,val absent:Int,val late:Int,val partial:Int,val leftEarly:Int,val attendanceRate:Double)
object ReportLifecycleRules {
    fun autoReportsAllowed(enabled:Boolean)=enabled
    fun lectureEligible(status:LectureStatus)=status==LectureStatus.COMPLETED || status==LectureStatus.FROZEN
    fun summary(statuses:List<Pair<FinalAttendanceStatus,Double>>):ReportSummary {
        val total=statuses.size
        return ReportSummary(total,statuses.count{it.first==FinalAttendanceStatus.PRESENT},statuses.count{it.first==FinalAttendanceStatus.ABSENT},statuses.count{it.first==FinalAttendanceStatus.LATE},statuses.count{it.first==FinalAttendanceStatus.PARTIAL},statuses.count{it.first==FinalAttendanceStatus.LEFT_EARLY},if(total==0)0.0 else statuses.map{it.second}.average())
    }
    fun retryableFailure(code:String):Boolean = code.startsWith("NETWORK_") || code in setOf("TIMEOUT","HTTP_408","HTTP_429","REPORT_PROVIDER_TRANSIENT_FAILURE") || code.startsWith("HTTP_5")
    fun safeFailureCode(raw:String?,fallback:String):String {
        val code=raw?.trim().orEmpty()
        return if(code.matches(Regex("[A-Z][A-Z0-9_]{2,79}")))code else fallback
    }
    fun validateSchedule(frequencies:Set<String>,sendTime:String,weeklyDay:Int?,monthlyDay:Int?,timezone:String,customRule:String?):String? {
        if(runCatching{LocalTime.parse(sendTime)}.isFailure)return "REPORT_SEND_TIME_INVALID"
        if(runCatching{ZoneId.of(timezone)}.isFailure)return "REPORT_TIMEZONE_INVALID"
        if("WEEKLY" in frequencies && (weeklyDay==null || weeklyDay !in 1..7))return "REPORT_WEEKLY_DAY_INVALID"
        if("MONTHLY" in frequencies && (monthlyDay==null || monthlyDay !in 1..31))return "REPORT_MONTHLY_DAY_INVALID"
        if("CUSTOM" in frequencies)return if(customRule.isNullOrBlank())"REPORT_CUSTOM_UNSUPPORTED" else "REPORT_CUSTOM_UNSUPPORTED"
        return null
    }
}
object ReportCsvSafety {
    fun spreadsheetSafe(value:String):String {
        val first=value.firstOrNull{!it.isWhitespace()} ?: return value
        return if(first in setOf('=','+','-','@')) "'$value" else value
    }
    fun escape(value:String):String = if(value.any{it==','||it=='"'||it=='\n'||it=='\r'}) "\"${value.replace("\"","\"\"")}\"" else value
}

object ReportDeduplication {
    fun key(teacherId:String,subjectId:String?,type:String,start:Long,end:Long,format:String?=null):String{
        val raw="$teacherId|${subjectId?:"ALL"}|$type|$start|$end|${format?:"DEFAULT"}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
