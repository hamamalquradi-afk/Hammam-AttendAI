package com.hammam.attendai.domain.attendance

data class FraudContext(
    val sameDeviceStudentCount: Int = 1,
    val overlappingActiveLectures: Int = 0,
    val duplicateEvents: Int = 0,
    val suspiciousDeviceChanges30Days: Int = 0,
    val impossibleTimeConflict: Boolean = false,
)

data class FraudFlag(val code: String, val severity: Int, val message: String)

class AntiFraudEngine {
    fun evaluate(c: FraudContext): List<FraudFlag> = buildList {
        if (c.sameDeviceStudentCount > 1) add(FraudFlag("DEVICE_MULTI_STUDENT", 3, "A device appears linked to multiple students; review required."))
        if (c.overlappingActiveLectures > 0) add(FraudFlag("OVERLAPPING_LECTURES", 3, "Attendance overlaps another active lecture."))
        if (c.duplicateEvents > 3) add(FraudFlag("DUPLICATE_EVENTS", 1, "Unusual duplicate presence events were detected."))
        if (c.suspiciousDeviceChanges30Days >= 3) add(FraudFlag("DEVICE_CHANGE_PATTERN", 2, "Repeated device changes require review."))
        if (c.impossibleTimeConflict) add(FraudFlag("IMPOSSIBLE_TIME", 3, "An impossible time conflict was detected."))
    }
}
