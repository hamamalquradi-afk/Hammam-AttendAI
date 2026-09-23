package com.hammam.attendai.sync

import org.junit.Assert.*
import org.junit.Test

class SyncIntegrityRulesTest {
    @Test fun allowlistRemainsExactAtBehaviorBoundary(){
        val allowed=listOf("Teacher","AttendancePolicy","University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord","AttendanceAppeal")
        allowed.forEach{assertTrue(it,SyncIntegrityRules.supported(it,"UPSERT"))}
        assertFalse(SyncIntegrityRules.supported("User","UPSERT"))
        assertFalse(SyncIntegrityRules.supported("StudentDevice","UPSERT"))
        assertFalse(SyncIntegrityRules.supported("Student","DELETE"))
    }

    @Test fun entityConflictResolutionIsDeterministic(){
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(null,1,false,false))
        assertEquals(RemoteEntityDecision.KEEP_LOCAL,SyncIntegrityRules.referenceDecision(3,2,false,false))
        assertEquals(RemoteEntityDecision.NO_OP,SyncIntegrityRules.referenceDecision(2,2,false,true))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,2,false,false))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,3,true,false))
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(2,3,false,false))
    }

    @Test fun poisonAndManualConflictOutcomesStayDistinct(){
        assertTrue(SyncIntegrityRules.pullBlockRequired("SYNC_STORED_MUTATION_INVALID"))
        assertTrue(SyncIntegrityRules.pullBlockRequired("SYNC_PULL_MALFORMED_CHANGE"))
        assertTrue(SyncIntegrityRules.pullBlockRequired("SYNC_PARENT_CHAIN_MISMATCH"))
        assertFalse(SyncIntegrityRules.pullBlockRequired("SYNC_CONFLICT_LOCAL_UNSYNCED"))
        assertTrue(SyncIntegrityRules.pullConflictCanAdvance("SYNC_CONFLICT_LOCAL_UNSYNCED"))
        assertTrue(SyncIntegrityRules.pullConflictCanAdvance("SYNC_UNIQUE_CONFLICT"))
        assertFalse(SyncIntegrityRules.pullConflictCanAdvance("SYNC_PARENT_MISSING"))
    }
}
