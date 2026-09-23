package com.hammam.attendai

import com.hammam.attendai.domain.setup.DateInputNormalizer
import com.hammam.attendai.domain.setup.FirstRunSetup
import com.hammam.attendai.domain.setup.FirstRunSetupValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirstRunSetupValidatorTest {
    @Test fun acceptsIsoAndDayFirstDatesAndStoresIso() {
        val normalized = FirstRunSetupValidator.normalize(
            FirstRunSetup(
                "Owner", "AR", "2580",
                "2026/2027", "20/09/2026", "2027-06-30",
                "Semester 1", "20/09/2026", "15/01/2027",
            )
        )
        assertEquals("2026-09-20", normalized.academicYearStart)
        assertEquals("2027-06-30", normalized.academicYearEnd)
        assertEquals("2027-01-15", normalized.semesterEnd)
    }

    @Test fun acceptsArabicIndicDigits() {
        assertEquals("2026-09-20", DateInputNormalizer.canonical("٢٠/٠٩/٢٠٢٦"))
    }

    @Test fun rejectsIncompleteAcademicSetupBeforePersistence() {
        val input = FirstRunSetup("Owner", "AR", null, "2026/2027", "", "", "", "", "")
        val error = assertThrows(IllegalArgumentException::class.java) { FirstRunSetupValidator.normalize(input) }
        assertEquals("ACADEMIC_YEAR_FIELDS_INCOMPLETE", error.message)
    }

    @Test fun rejectsSemesterOutsideAcademicYear() {
        val input = FirstRunSetup(
            "Owner", "AR", null,
            "2026/2027", "2026-09-20", "2027-06-30",
            "Semester 1", "2026-08-01", "2027-01-15",
        )
        val error = assertThrows(IllegalArgumentException::class.java) { FirstRunSetupValidator.normalize(input) }
        assertEquals("SEMESTER_OUTSIDE_ACADEMIC_YEAR", error.message)
    }
}
