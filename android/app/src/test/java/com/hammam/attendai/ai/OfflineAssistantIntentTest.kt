package com.hammam.attendai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAssistantIntentTest {
    @Test fun arabicAndEnglishAbsenceQueriesMapToSameSafeIntent(){
        val parser=OfflineIntentParser()
        assertTrue(parser.parse("كم عدد الغائبين اليوم؟") is SafeIntent.AbsentToday)
        assertTrue(parser.parse("How many students are absent today?") is SafeIntent.AbsentToday)
    }

    @Test fun englishStudentAttendanceQueryIsRecognized(){
        assertTrue(OfflineIntentParser().parse("Show attendance record for student 202612345") is SafeIntent.StudentAttendance)
    }

    @Test fun localEngineReturnsStructuredReplyInsteadOfLocalizedLiteral() = kotlinx.coroutines.runBlocking {
        val tools=object:AttendanceQueryTools{
            override suspend fun searchStudents(query:String)=listOf(mapOf("id" to "s1","fullName" to "Test Student"))
            override suspend fun getStudentAttendance(studentId:String)=mapOf("studentName" to "Test Student","present" to 8,"absent" to 1,"late" to 1,"partial" to 0,"attendanceRate" to .8)
            override suspend fun getLectureAttendance(lectureId:String)=emptyMap<String,Any?>()
            override suspend fun getSubjectStatistics(subjectId:String,start:Long,end:Long)=emptyMap<String,Any?>()
            override suspend fun getAbsenceSummary(start:Long,end:Long)=mapOf("count" to 2)
            override suspend fun getLateStudents(start:Long,end:Long)=emptyList<Map<String,Any?>>()
            override suspend fun getPartialAttendance(start:Long,end:Long)=emptyList<Map<String,Any?>>()
            override suspend fun getNeedsReview(start:Long,end:Long)=emptyList<Map<String,Any?>>()
            override suspend fun getReportData(subjectId:String?,start:Long,end:Long)=emptyMap<String,Any?>()
        }
        val answer=OfflineAssistantEngine(OfflineIntentParser(),tools).answer("attendance record for 202612345",0L)
        assertEquals(AssistantReplyCode.STUDENT_ATTENDANCE,answer.replyCode)
        assertEquals("Test Student",answer.values["studentName"])
        assertEquals("80.0",answer.values["attendanceRate"])
    }
}
