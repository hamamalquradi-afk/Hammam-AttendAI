package com.hammam.attendai
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.reports.*
import org.junit.Assert.*
import org.junit.Test
class ReportLifecycleIntegrityTest {
 @Test fun futureScheduledLectureIsNotReportEligible(){assertFalse(ReportLifecycleRules.lectureEligible(LectureStatus.SCHEDULED));assertTrue(ReportLifecycleRules.lectureEligible(LectureStatus.COMPLETED));assertTrue(ReportLifecycleRules.lectureEligible(LectureStatus.FROZEN))}
 @Test fun summaryCountsAttendanceInstancesConsistently(){val s=ReportLifecycleRules.summary(listOf(FinalAttendanceStatus.PRESENT to 1.0,FinalAttendanceStatus.ABSENT to 0.0,FinalAttendanceStatus.LATE to .8));assertEquals(3,s.totalStudents);assertEquals(3,s.present+s.absent+s.late+s.partial+s.leftEarly)}
 @Test fun failureClassificationSeparatesTransient(){assertTrue(ReportLifecycleRules.retryableFailure("NETWORK_UNAVAILABLE"));assertTrue(ReportLifecycleRules.retryableFailure("HTTP_503"));assertFalse(ReportLifecycleRules.retryableFailure("GENERATED_REPORT_FILE_MISSING"));assertFalse(ReportLifecycleRules.retryableFailure("REPORT_TOO_LARGE_FOR_PROVIDER_RELAY"))}
 @Test fun invalidSendTimeRejected(){assertEquals("REPORT_SEND_TIME_INVALID",ReportLifecycleRules.validateSchedule(setOf("DAILY"),"99:99",null,null,"UTC",null))}
 @Test fun customIsRejectedUntilImplemented(){assertEquals("REPORT_CUSTOM_UNSUPPORTED",ReportLifecycleRules.validateSchedule(setOf("CUSTOM"),"18:00",null,null,"UTC",null));assertEquals("REPORT_CUSTOM_UNSUPPORTED",ReportLifecycleRules.validateSchedule(setOf("CUSTOM"),"18:00",null,null,"UTC","legacy-value"))}
 @Test fun invalidTimezoneAndCalendarInputsRejected(){assertEquals("REPORT_TIMEZONE_INVALID",ReportLifecycleRules.validateSchedule(setOf("DAILY"),"18:00",null,null,"Not/AZone",null));assertEquals("REPORT_WEEKLY_DAY_INVALID",ReportLifecycleRules.validateSchedule(setOf("WEEKLY"),"18:00",0,null,"UTC",null));assertEquals("REPORT_MONTHLY_DAY_INVALID",ReportLifecycleRules.validateSchedule(setOf("MONTHLY"),"18:00",null,32,"UTC",null))}
 @Test fun providerErrorsAreSanitized(){assertEquals("HTTP_429",ReportLifecycleRules.safeFailureCode("HTTP_429","REPORT_PROVIDER_FAILURE"));assertEquals("REPORT_PROVIDER_FAILURE",ReportLifecycleRules.safeFailureCode("https://provider/error?token=secret","REPORT_PROVIDER_FAILURE"))}
 @Test fun csvSafetyEscapesAndPreventsSpreadsheetFormulaExecution(){
  assertEquals("'=SUM(A1:A2)",ReportCsvSafety.spreadsheetSafe("=SUM(A1:A2)"))
  assertEquals("\"A,B\"",ReportCsvSafety.escape("A,B"))
  assertEquals("\"A\"\"B\"",ReportCsvSafety.escape("A\"B"))
  assertEquals("\"A\nB\"",ReportCsvSafety.escape("A\nB"))
 }
 @Test fun autoReportsFlagControlsAutomaticScheduling(){assertFalse(ReportLifecycleRules.autoReportsAllowed(false));assertTrue(ReportLifecycleRules.autoReportsAllowed(true))}
 @Test fun dedupIncludesFormat(){assertNotEquals(ReportDeduplication.key("t","s","DAILY",1,2,"PDF"),ReportDeduplication.key("t","s","DAILY",1,2,"CSV"))}
 @Test fun dedupIsIdempotent(){assertEquals(ReportDeduplication.key("t","s","DAILY",1,2),ReportDeduplication.key("t","s","DAILY",1,2))}
}
