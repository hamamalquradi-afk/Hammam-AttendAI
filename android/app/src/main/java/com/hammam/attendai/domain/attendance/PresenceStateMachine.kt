package com.hammam.attendai.domain.attendance

import com.hammam.attendai.domain.model.AttendanceState
import com.hammam.attendai.domain.model.PresenceEventType

class PresenceStateMachine(private val graceSeconds: Long) {
    data class Snapshot(val state: AttendanceState, val lastSeen: Long?, val missingSince: Long?)

    fun reduce(current: Snapshot, event: PresenceEventType, timestamp: Long): Snapshot = when (event) {
        PresenceEventType.DETECTED, PresenceEventType.QR_VERIFIED, PresenceEventType.NFC_VERIFIED, PresenceEventType.MANUAL_PRESENT ->
            Snapshot(if (current.state == AttendanceState.LEFT) AttendanceState.RETURNED else AttendanceState.PRESENT, timestamp, null)
        PresenceEventType.REDETECTED -> Snapshot(AttendanceState.RETURNED, timestamp, null)
        PresenceEventType.LOST -> Snapshot(AttendanceState.TEMPORARILY_MISSING, current.lastSeen, current.missingSince ?: timestamp)
        PresenceEventType.MANUAL_ABSENT -> Snapshot(AttendanceState.MANUAL_REVIEW, current.lastSeen, current.missingSince)
    }

    fun onTick(current: Snapshot, now: Long): Snapshot {
        val missing = current.missingSince ?: return current
        return if (current.state == AttendanceState.TEMPORARILY_MISSING && now - missing >= graceSeconds) {
            current.copy(state = AttendanceState.LEFT)
        } else current
    }
}
