package com.hammam.attendai.ble

import kotlinx.coroutines.flow.Flow

data class PresenceObservation(val rotatingId:String,val rssi:Int,val timestampMillis:Long)
sealed interface DetectorState { data object Idle:DetectorState; data object Starting:DetectorState; data object Active:DetectorState; data class Error(val code:String):DetectorState }

interface PresenceDetector {
    val observations: Flow<PresenceObservation>
    val state: Flow<DetectorState>
    suspend fun start(sessionId:String)
    suspend fun stop()
}

interface PresenceTokenResolver {
    suspend fun resolve(rotatingId:String, timestampMillis:Long):ResolvedPresence?
}
data class ResolvedPresence(val studentId:String,val deviceId:String,val trusted:Boolean)
