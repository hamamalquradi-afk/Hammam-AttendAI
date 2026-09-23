package com.hammam.attendai.domain.model

enum class AttendanceState { NOT_SEEN, VERIFYING, PRESENT, TEMPORARILY_MISSING, LEFT, RETURNED, COMPLETED, MANUAL_REVIEW }
enum class FinalAttendanceStatus { PRESENT, LATE, PARTIAL, LEFT_EARLY, ABSENT, EXCUSED, MANUAL_REVIEW }
enum class ApprovalStatus { DRAFT, PENDING, APPROVED, FROZEN }
enum class PresenceEventType { DETECTED, LOST, REDETECTED, MANUAL_PRESENT, MANUAL_ABSENT, QR_VERIFIED, NFC_VERIFIED }
enum class PresenceSource { BLE, QR, NFC, MANUAL }
enum class LectureStatus { SCHEDULED, READY, ACTIVE, COMPLETED, CANCELLED, NEEDS_REVIEW, FROZEN }
enum class SemesterStatus { UPCOMING, ACTIVE, COMPLETED, ARCHIVED }
enum class StudentStatus { ACTIVE, INACTIVE, GRADUATED, SUSPENDED, ARCHIVED }
enum class DeviceStatus { ACTIVE, REPLACED, BLOCKED, LOST }
enum class QueueStatus { PENDING, PROCESSING, SENT, FAILED, CANCELLED, NEEDS_MANUAL_REVIEW }
enum class ReportJobStatus { SCHEDULED, GENERATING, PENDING_APPROVAL, PENDING_SEND, SENT, FAILED, CANCELLED }
enum class AppealStatus { PENDING, ACCEPTED, REJECTED, CANCELLED }
enum class AppealSyncStatus { LOCAL_ONLY, PENDING_SYNC, SYNCED, SYNC_FAILED }

data class AttendancePolicy(
    val fullAttendanceThreshold: Double,
    val partialAttendanceThreshold: Double,
    val lateAfterMinutes: Int,
    val earlyLeaveThresholdMinutes: Int,
    val absenceThreshold: Double,
    val temporaryMissingGraceSeconds: Long,
    val minimumPresenceVerificationSeconds: Long,
    val confidenceThreshold: Double,
)

data class PresenceEvent(
    val timestampEpochSeconds: Long,
    val type: PresenceEventType,
    val source: PresenceSource,
    val signalStrength: Int? = null,
    val confidence: Double = 0.5,
)

data class PresenceInterval(val startEpochSeconds: Long, val endEpochSeconds: Long) {
    init { require(endEpochSeconds >= startEpochSeconds) }
    val durationSeconds: Long get() = endEpochSeconds - startEpochSeconds
}

data class AttendanceComputationInput(
    val lectureStartEpochSeconds: Long,
    val lectureEndEpochSeconds: Long,
    val intervals: List<PresenceInterval>,
    val confidenceScore: Double,
    val manualStatus: FinalAttendanceStatus? = null,
    val excused: Boolean = false,
)

data class AttendanceComputationResult(
    val verifiedPresenceSeconds: Long,
    val lectureDurationSeconds: Long,
    val attendancePercentage: Double,
    val lateMinutes: Int,
    val earlyLeaveMinutes: Int,
    val finalStatus: FinalAttendanceStatus,
    val requiresManualReview: Boolean,
)
