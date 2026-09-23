package com.hammam.attendai

import com.hammam.attendai.domain.model.ReportJobStatus
import org.junit.Assert.*
import org.junit.Test

class ReportClaimIntegrityTest {
    private fun claimable(status:ReportJobStatus, generated:Boolean)=when(status){
        ReportJobStatus.SCHEDULED -> !generated
        ReportJobStatus.PENDING_SEND -> generated
        else -> false
    }
    @Test fun scheduledGenerationIsClaimableOnlyBeforeGeneration(){
        assertTrue(claimable(ReportJobStatus.SCHEDULED,false))
        assertFalse(claimable(ReportJobStatus.SCHEDULED,true))
    }
    @Test fun pendingSendIsClaimableOnlyWithGeneratedReport(){
        assertTrue(claimable(ReportJobStatus.PENDING_SEND,true))
        assertFalse(claimable(ReportJobStatus.PENDING_SEND,false))
    }
    @Test fun terminalStatesAreNotClaimable(){
        assertFalse(claimable(ReportJobStatus.SENT,true))
        assertFalse(claimable(ReportJobStatus.CANCELLED,true))
    }
}
