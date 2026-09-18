package com.hammam.attendai
import com.hammam.attendai.reports.ReportDeduplication
import org.junit.Assert.*
import org.junit.Test
class QueueAndReportTest{
 @Test fun reportDedupStable(){assertEquals(ReportDeduplication.key("t","s","WEEKLY",1,2),ReportDeduplication.key("t","s","WEEKLY",1,2))}
 @Test fun reportDedupChangesWithPeriod(){assertNotEquals(ReportDeduplication.key("t","s","WEEKLY",1,2),ReportDeduplication.key("t","s","WEEKLY",2,3))}
}
