package com.hammam.attendai.reports

import java.security.MessageDigest

data class ReportSummary(val totalStudents:Int,val present:Int,val absent:Int,val late:Int,val partial:Int,val leftEarly:Int,val attendanceRate:Double)
object ReportDeduplication {
    fun key(teacherId:String,subjectId:String?,type:String,start:Long,end:Long):String{
        val raw="$teacherId|${subjectId?:"ALL"}|$type|$start|$end"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
