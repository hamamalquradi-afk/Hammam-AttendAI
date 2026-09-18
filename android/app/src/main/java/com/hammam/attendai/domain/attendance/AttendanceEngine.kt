package com.hammam.attendai.domain.attendance

import com.hammam.attendai.domain.model.*
import kotlin.math.max
import kotlin.math.min

class AttendanceEngine {
    fun compute(input: AttendanceComputationInput, policy: AttendancePolicy): AttendanceComputationResult {
        require(input.lectureEndEpochSeconds > input.lectureStartEpochSeconds)
        val duration = input.lectureEndEpochSeconds - input.lectureStartEpochSeconds
        val merged = mergeAndClamp(input.intervals, input.lectureStartEpochSeconds, input.lectureEndEpochSeconds)
        val present = merged.sumOf { it.durationSeconds }
        val percentage = (present.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        val firstSeen = merged.firstOrNull()?.startEpochSeconds
        val lastSeen = merged.lastOrNull()?.endEpochSeconds
        val late = if (firstSeen == null) 0 else max(0, ((firstSeen - input.lectureStartEpochSeconds) / 60L).toInt())
        val early = if (lastSeen == null) 0 else max(0, ((input.lectureEndEpochSeconds - lastSeen) / 60L).toInt())

        val review = input.confidenceScore < policy.confidenceThreshold && !input.excused && input.manualStatus == null
        val status = when {
            input.manualStatus != null -> input.manualStatus
            input.excused -> FinalAttendanceStatus.EXCUSED
            review -> FinalAttendanceStatus.MANUAL_REVIEW
            present < policy.minimumPresenceVerificationSeconds -> FinalAttendanceStatus.ABSENT
            percentage < policy.partialAttendanceThreshold || percentage <= policy.absenceThreshold -> FinalAttendanceStatus.ABSENT
            percentage < policy.fullAttendanceThreshold -> FinalAttendanceStatus.PARTIAL
            late > policy.lateAfterMinutes -> FinalAttendanceStatus.LATE
            early > policy.earlyLeaveThresholdMinutes -> FinalAttendanceStatus.LEFT_EARLY
            else -> FinalAttendanceStatus.PRESENT
        }
        return AttendanceComputationResult(present, duration, percentage, late, early, status, review)
    }

    fun mergeAndClamp(intervals: List<PresenceInterval>, start: Long, end: Long): List<PresenceInterval> {
        val clean = intervals.mapNotNull {
            val s = max(start, it.startEpochSeconds)
            val e = min(end, it.endEpochSeconds)
            if (e > s) PresenceInterval(s, e) else null
        }.sortedBy { it.startEpochSeconds }
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<PresenceInterval>()
        var current = clean.first()
        for (next in clean.drop(1)) {
            current = if (next.startEpochSeconds <= current.endEpochSeconds) {
                PresenceInterval(current.startEpochSeconds, max(current.endEpochSeconds, next.endEpochSeconds))
            } else {
                out += current
                next
            }
        }
        out += current
        return out
    }
}
