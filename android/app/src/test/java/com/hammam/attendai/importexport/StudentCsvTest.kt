package com.hammam.attendai.importexport

import org.junit.Assert.*
import org.junit.Test

class StudentCsvTest {
    @Test fun validCsvProducesPreviewWithoutCommitSideEffects(){
        val csv="university_number,full_name,phone\n1001,Ali Ahmed,+967777111222\n1002,\"Sara, Saleh\",777222333"
        val preview=StudentCsv.preview(csv)
        assertEquals(2,preview.valid.size)
        assertTrue(preview.invalid.isEmpty())
        assertEquals("Sara, Saleh",preview.valid[1].fullName)
        assertEquals("1001",preview.valid[0].universityNumber)
    }

    @Test fun arabicUtf8NameIsPreserved(){
        val preview=StudentCsv.preview("university_number,full_name,phone\n1001,محمد أحمد,+967777111222")
        assertTrue(preview.invalid.isEmpty())
        assertEquals("محمد أحمد",preview.valid.single().fullName)
    }

    @Test fun quotedNameWithCommaIsParsed(){
        val preview=StudentCsv.preview("university_number,full_name\n1001,\"Sara, Saleh\"")
        assertTrue(preview.invalid.isEmpty())
        assertEquals("Sara, Saleh",preview.valid.single().fullName)
    }

    @Test fun escapedDoubleQuotesAreParsed(){
        val preview=StudentCsv.preview("university_number,full_name\n1001,\"Ali \"\"A\"\" Ahmed\"")
        assertTrue(preview.invalid.isEmpty())
        assertEquals("Ali \"A\" Ahmed",preview.valid.single().fullName)
    }

    @Test fun malformedCsvIsInvalidInsteadOfThrowing(){
        val preview=StudentCsv.preview("university_number,full_name\n1001,\"Unclosed name")
        assertTrue(preview.valid.isEmpty())
        assertEquals(listOf("MALFORMED_CSV"),preview.invalid.single().errors)
    }

    @Test fun emptyNameIsRejected(){
        val preview=StudentCsv.preview("university_number,full_name\n1001,")
        assertTrue(preview.valid.isEmpty())
        assertTrue("EMPTY_NAME" in preview.invalid.single().errors)
    }

    @Test fun duplicateUniversityNumberIsRejectedWithinFile(){
        val preview=StudentCsv.preview("university_number,full_name\n1001,Ali\n1001,Sara")
        assertEquals(1,preview.valid.size)
        assertEquals(1,preview.invalid.size)
        assertTrue("DUPLICATE_UNIVERSITY_NUMBER" in preview.invalid.single().errors)
    }

    @Test fun invalidPhoneIsRejected(){
        val preview=StudentCsv.preview("university_number,full_name,phone\n1001,Ali,abc-123")
        assertTrue(preview.valid.isEmpty())
        assertTrue("INVALID_PHONE" in preview.invalid.single().errors)
    }

    @Test fun emptyCsvProducesEmptyPreview(){
        val preview=StudentCsv.preview("")
        assertTrue(preview.valid.isEmpty())
        assertTrue(preview.invalid.isEmpty())
    }

    @Test fun headerOnlyCsvProducesEmptyPreview(){
        val preview=StudentCsv.preview("university_number,full_name,phone")
        assertTrue(preview.valid.isEmpty())
        assertTrue(preview.invalid.isEmpty())
    }

    @Test fun exportEscapesQuotedAndCommaNames(){
        val text=StudentCsv.export(listOf("1001" to "Ali, \"A\""))
        assertTrue(text.contains("1001,\"Ali, \"\"A\"\"\""))
        val preview=StudentCsv.preview("university_number,full_name\n"+text.lineSequence().drop(1).first())
        assertEquals("Ali, \"A\"",preview.valid.single().fullName)
    }

    @Test fun bomAndCrlfAreAccepted(){
        val preview=StudentCsv.preview("\uFEFFuniversity_number,full_name,phone\r\n1001,محمد أحمد,+967777111222\r\n")
        assertTrue(preview.invalid.isEmpty())
        assertEquals("محمد أحمد",preview.valid.single().fullName)
    }

    @Test fun missingHeaderIsRejectedWithoutDiscardingFirstStudent(){
        val preview=StudentCsv.preview("1001,Ali Ahmed\n1002,Sara Saleh")
        assertTrue(preview.valid.isEmpty())
        assertEquals("CSV_HEADER_INVALID",preview.invalid.single().errors.single())
    }

    @Test fun unsupportedHeaderIsRejected(){
        val preview=StudentCsv.preview("id,name,email\n1001,Ali,a@example.com")
        assertEquals("CSV_HEADER_INVALID",preview.invalid.single().errors.single())
    }

    @Test fun exportNeutralizesSpreadsheetFormulaCells(){
        val csv=StudentCsv.export(listOf("=1+1" to "+SUM(A1:A2)"))
        assertTrue(csv.contains("'=1+1"))
        assertTrue(csv.contains("'+SUM(A1:A2)"))
    }
}
