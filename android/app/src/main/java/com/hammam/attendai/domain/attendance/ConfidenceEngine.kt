package com.hammam.attendai.domain.attendance

import com.hammam.attendai.domain.model.PresenceEvent
import com.hammam.attendai.domain.model.PresenceEventType
import com.hammam.attendai.domain.model.PresenceSource

class ConfidenceEngine {
    fun score(events: List<PresenceEvent>, verifiedSeconds: Long, interruptionSeconds: Long, deviceTrusted: Boolean): Double {
        if (events.isEmpty()) return 0.0
        val detections = events.count { it.type == PresenceEventType.DETECTED || it.type == PresenceEventType.REDETECTED }
        val manualOrStrong = events.count { it.source == PresenceSource.QR || it.source == PresenceSource.NFC || it.source == PresenceSource.MANUAL }
        val continuity = (1.0 - interruptionSeconds.toDouble() / (verifiedSeconds + interruptionSeconds).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val evidence = (detections / 8.0).coerceAtMost(1.0)
        val strong = (manualOrStrong / 2.0).coerceAtMost(1.0)
        val trust = if (deviceTrusted) 1.0 else 0.35
        return (0.30 * evidence + 0.30 * continuity + 0.25 * trust + 0.15 * strong).coerceIn(0.0, 1.0)
    }
}
