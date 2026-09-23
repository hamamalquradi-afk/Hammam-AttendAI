package com.hammam.attendai.reports

import com.hammam.attendai.data.local.dao.ReportAttendanceRow
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import java.io.File
import java.security.MessageDigest

class CsvReportGenerator {
    data class Output(val file:File,val sha256:String)
    fun generate(file:File,records:List<ReportAttendanceRow>,statusLabels:Map<FinalAttendanceStatus,String> = emptyMap()):Output{
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("record_id,lecture_id,student_id,student_name,university_number,status,attendance_percentage,presence_seconds,entry_time,exit_time")
            records.forEach { r ->
                val values=listOf(
                    r.recordId,r.lectureId,r.studentId,r.studentName,r.universityNumber.orEmpty(),
                    statusLabels[r.finalStatus]?:r.finalStatus.name,
                    "%.4f".format(java.util.Locale.ROOT,r.attendancePercentage),r.verifiedPresenceSeconds.toString(),
                    r.firstSeenAt?.toString().orEmpty(),r.lastSeenAt?.toString().orEmpty()
                )
                out.appendLine(values.joinToString(","){ReportCsvSafety.escape(ReportCsvSafety.spreadsheetSafe(it))})
            }
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }
}
