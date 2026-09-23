package com.hammam.attendai

import com.hammam.attendai.domain.attendance.*
import com.hammam.attendai.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceEngineTest {
    private val policy=AttendancePolicy(.85,.50,10,10,.20,120,300,.60)
    private val engine=AttendanceEngine()
    private fun input(intervals:List<PresenceInterval>,confidence:Double=.9)=AttendanceComputationInput(0,3600,intervals,confidence)
    @Test fun fullPresence(){assertEquals(FinalAttendanceStatus.PRESENT,engine.compute(input(listOf(PresenceInterval(0,3600))),policy).finalStatus)}
    @Test fun late(){assertEquals(FinalAttendanceStatus.LATE,engine.compute(input(listOf(PresenceInterval(900,3600))),policy).finalStatus)}
    @Test fun leftEarly(){assertEquals(FinalAttendanceStatus.LEFT_EARLY,engine.compute(input(listOf(PresenceInterval(0,2700))),policy).finalStatus)}
    @Test fun returnedMergesOverlaps(){assertEquals(3000,engine.compute(input(listOf(PresenceInterval(0,1200),PresenceInterval(1800,3600))),policy).verifiedPresenceSeconds)}
    @Test fun absentWhenNeverSeen(){assertEquals(FinalAttendanceStatus.ABSENT,engine.compute(input(emptyList()),policy).finalStatus)}
    @Test fun briefDetectionIsAbsent(){assertEquals(FinalAttendanceStatus.ABSENT,engine.compute(input(listOf(PresenceInterval(10,100))),policy).finalStatus)}
    @Test fun lowConfidenceNeedsReview(){assertEquals(FinalAttendanceStatus.MANUAL_REVIEW,engine.compute(input(listOf(PresenceInterval(0,3600)),.2),policy).finalStatus)}
    @Test fun manualOverrideWins(){val i=input(emptyList()).copy(manualStatus=FinalAttendanceStatus.PRESENT);assertEquals(FinalAttendanceStatus.PRESENT,engine.compute(i,policy).finalStatus)}
    @Test fun policySnapshotChangesOutcome(){val changed=policy.copy(fullAttendanceThreshold=.95);assertNotEquals(engine.compute(input(listOf(PresenceInterval(0,3200))),policy).finalStatus,engine.compute(input(listOf(PresenceInterval(0,3200))),changed).finalStatus)}
}
