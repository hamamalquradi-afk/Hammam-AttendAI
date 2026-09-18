package com.hammam.attendai.domain.attendance

import com.hammam.attendai.data.local.entity.AttendancePolicyEntity
import com.hammam.attendai.domain.model.AttendancePolicy

object AttendancePolicySnapshotCodec {
    fun encode(entity:AttendancePolicyEntity):String = """{"policyId":"${esc(entity.id)}","name":"${esc(entity.name)}","fullAttendanceThreshold":${entity.fullAttendanceThreshold},"partialAttendanceThreshold":${entity.partialAttendanceThreshold},"lateAfterMinutes":${entity.lateAfterMinutes},"earlyLeaveThresholdMinutes":${entity.earlyLeaveThresholdMinutes},"absenceThreshold":${entity.absenceThreshold},"temporaryMissingGraceSeconds":${entity.temporaryMissingGraceSeconds},"minimumPresenceVerificationSeconds":${entity.minimumPresenceVerificationSeconds},"confidenceThreshold":${entity.confidenceThreshold},"snapshotVersion":1}"""

    fun decode(json:String):AttendancePolicy? = runCatching {
        AttendancePolicy(
            fullAttendanceThreshold=number(json,"fullAttendanceThreshold"),
            partialAttendanceThreshold=number(json,"partialAttendanceThreshold"),
            lateAfterMinutes=number(json,"lateAfterMinutes").toInt(),
            earlyLeaveThresholdMinutes=number(json,"earlyLeaveThresholdMinutes").toInt(),
            absenceThreshold=number(json,"absenceThreshold"),
            temporaryMissingGraceSeconds=number(json,"temporaryMissingGraceSeconds").toLong(),
            minimumPresenceVerificationSeconds=number(json,"minimumPresenceVerificationSeconds").toLong(),
            confidenceThreshold=number(json,"confidenceThreshold"),
        )
    }.getOrNull()

    private fun number(json:String,key:String):Double {
        val m=Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(json) ?: error("MISSING_$key")
        return m.groupValues[1].toDouble()
    }
    private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"")
}
