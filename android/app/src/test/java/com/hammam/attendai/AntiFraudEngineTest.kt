package com.hammam.attendai

import com.hammam.attendai.domain.attendance.AntiFraudEngine
import com.hammam.attendai.domain.attendance.FraudContext
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFraudEngineTest{
    @Test fun overlappingAttendanceIsReviewFlag(){
        val flags=AntiFraudEngine().evaluate(FraudContext(overlappingActiveLectures=1,impossibleTimeConflict=true))
        assertTrue(flags.any{it.code=="OVERLAPPING_LECTURES" && it.severity>=3})
        assertTrue(flags.any{it.code=="IMPOSSIBLE_TIME" && it.severity>=3})
    }
    @Test fun repeatedDeviceChangesAreSuspiciousNotAutomaticAccusation(){
        val flags=AntiFraudEngine().evaluate(FraudContext(suspiciousDeviceChanges30Days=3))
        assertTrue(flags.any{it.code=="DEVICE_CHANGE_PATTERN"})
    }
}
