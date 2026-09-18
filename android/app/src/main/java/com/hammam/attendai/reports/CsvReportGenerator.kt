package com.hammam.attendai.reports

import com.hammam.attendai.data.local.dao.ReportAttendanceRow
import java.io.File
import java.security.MessageDigest

class CsvReportGenerator {
    data class Output(val file:File,val sha256:String)
    fun generate(file:File,records:List<ReportAttendanceRow>):Output{
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("record_id,lecture_id,student_id,student_name,university_number,status,attendance_percentage,presence_seconds,entry_time,exit_time")
            records.forEach { r ->
                out.appendLine(listOf(r.recordId,r.lectureId,r.studentId,r.studentName,r.universityNumber.orEmpty(),r.finalStatus.name,"%.4f".format(r.attendancePercentage),r.verifiedPresenceSeconds.toString(),r.firstSeenAt?.toString().orEmpty(),r.lastSeenAt?.toString().orEmpty()).joinToString(","){csv(it)})
            }
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }
    private fun csv(v:String)=if(v.any{it==','||it=='"'||it=='\n'})"\"${v.replace("\"","\"\"")}\"" else v
}
