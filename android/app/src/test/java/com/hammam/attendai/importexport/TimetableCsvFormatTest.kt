package com.hammam.attendai.importexport

import org.junit.Assert.*
import org.junit.Test

class TimetableCsvFormatTest {
    @Test fun validQuotedCsvPreservesCommaAndEscapedQuote(){
        val text="day,subjectCode,teacher,start,end,room,lectureType\n1,CS101,\"Ali, \"\"A\"\"\",08:00,09:00,\"Lab, 2\",THEORY"
        val result=TimetableCsvFormat.parse(text)
        assertTrue(result.issues.isEmpty())
        assertEquals("Ali, \"A\"",result.rows.single().teacherRef)
        assertEquals("Lab, 2",result.rows.single().room)
    }

    @Test fun missingHeaderIsRejectedWithoutDroppingFirstDataRow(){
        val result=TimetableCsvFormat.parse("1,CS101,Ali,08:00,09:00")
        assertEquals("TIMETABLE_CSV_HEADER_INVALID",result.issues.single().code)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun malformedCsvIsRejected(){
        val result=TimetableCsvFormat.parse("day,subjectCode,teacher,start,end\n1,CS101,\"Ali,08:00,09:00")
        assertEquals("TIMETABLE_CSV_MALFORMED",result.issues.single().code)
    }

    @Test fun invalidDayTextIsReported(){
        val result=TimetableCsvFormat.parse("day,subjectCode,teacher,start,end\nX,CS101,Ali,08:00,09:00")
        assertEquals("INVALID_DAY",result.issues.single().code)
    }
}
