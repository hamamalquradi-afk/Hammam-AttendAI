package com.hammam.attendai

import com.hammam.attendai.domain.attendance.SessionClockGuard
import org.junit.Assert.*
import org.junit.Test

class SessionClockGuardTest {
    @Test fun normalWallClockProgressIsAccepted(){var elapsed=1_000L;val guard=SessionClockGuard({elapsed},5_000);guard.start("s",10_000);elapsed+=1_000;assertFalse(guard.observe("s",11_000))}
    @Test fun wallClockJumpBecomesStickyReviewSignal(){var elapsed=1_000L;val guard=SessionClockGuard({elapsed},5_000);guard.start("s",10_000);elapsed+=1_000;assertTrue(guard.observe("s",30_000));elapsed+=1_000;assertTrue(guard.observe("s",31_000))}
}
