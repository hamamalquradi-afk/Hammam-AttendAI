package com.hammam.attendai.domain.setup

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Accepts common numeric date formats but always stores ISO yyyy-MM-dd. */
object DateInputNormalizer {
    private val formatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
    )

    fun parse(value: String): LocalDate {
        val normalized = normalizeDigits(value).trim()
        require(normalized.isNotEmpty()) { "INVALID_DATE_FORMAT" }
        return formatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDate.parse(normalized, formatter) }.getOrNull()
        } ?: throw IllegalArgumentException("INVALID_DATE_FORMAT")
    }

    fun canonical(value: String): String = parse(value).toString()

    fun canonicalRange(start: String, end: String): Pair<String, String> {
        val startDate = parse(start)
        val endDate = parse(end)
        require(startDate.isBefore(endDate)) { "INVALID_DATE_RANGE" }
        return startDate.toString() to endDate.toString()
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                    in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                    else -> ch
                }
            )
        }
    }
}

data class FirstRunSetup(
    val displayName: String,
    val language: String,
    val pin: String?,
    val academicYearName: String,
    val academicYearStart: String,
    val academicYearEnd: String,
    val semesterName: String,
    val semesterStart: String,
    val semesterEnd: String,
)

object FirstRunSetupValidator {
    fun normalize(input: FirstRunSetup): FirstRunSetup {
        val displayName = input.displayName.trim()
        require(displayName.isNotBlank()) { "OWNER_NAME_REQUIRED" }

        val language = input.language.trim().uppercase().let { if (it == "EN") "EN" else "AR" }
        val pin = input.pin?.trim()?.takeIf { it.isNotEmpty() }
        require(pin == null || (pin.length in 4..12 && pin.all(Char::isDigit))) { "INVALID_PIN" }

        val yearName = input.academicYearName.trim()
        val yearStartRaw = input.academicYearStart.trim()
        val yearEndRaw = input.academicYearEnd.trim()
        val semesterName = input.semesterName.trim()
        val semesterStartRaw = input.semesterStart.trim()
        val semesterEndRaw = input.semesterEnd.trim()

        val hasAnyYear = listOf(yearName, yearStartRaw, yearEndRaw).any(String::isNotBlank)
        val hasAnySemester = listOf(semesterName, semesterStartRaw, semesterEndRaw).any(String::isNotBlank)

        require(!hasAnyYear || listOf(yearName, yearStartRaw, yearEndRaw).all(String::isNotBlank)) {
            "ACADEMIC_YEAR_FIELDS_INCOMPLETE"
        }
        require(!hasAnySemester || listOf(semesterName, semesterStartRaw, semesterEndRaw).all(String::isNotBlank)) {
            "SEMESTER_FIELDS_INCOMPLETE"
        }
        require(!hasAnySemester || hasAnyYear) { "SEMESTER_REQUIRES_ACADEMIC_YEAR" }

        var yearStart = ""
        var yearEnd = ""
        var semesterStart = ""
        var semesterEnd = ""

        if (hasAnyYear) {
            val range = DateInputNormalizer.canonicalRange(yearStartRaw, yearEndRaw)
            yearStart = range.first
            yearEnd = range.second
        }

        if (hasAnySemester) {
            val range = DateInputNormalizer.canonicalRange(semesterStartRaw, semesterEndRaw)
            semesterStart = range.first
            semesterEnd = range.second
            val yStart = LocalDate.parse(yearStart)
            val yEnd = LocalDate.parse(yearEnd)
            val sStart = LocalDate.parse(semesterStart)
            val sEnd = LocalDate.parse(semesterEnd)
            require(!sStart.isBefore(yStart) && !sEnd.isAfter(yEnd)) { "SEMESTER_OUTSIDE_ACADEMIC_YEAR" }
        }

        return FirstRunSetup(
            displayName = displayName,
            language = language,
            pin = pin,
            academicYearName = yearName,
            academicYearStart = yearStart,
            academicYearEnd = yearEnd,
            semesterName = semesterName,
            semesterStart = semesterStart,
            semesterEnd = semesterEnd,
        )
    }
}
