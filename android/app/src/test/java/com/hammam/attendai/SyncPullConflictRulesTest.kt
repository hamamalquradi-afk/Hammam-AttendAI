package com.hammam.attendai

import com.hammam.attendai.sync.RemoteAppealDecision
import com.hammam.attendai.sync.SyncIntegrityRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPullConflictRulesTest {
    @Test fun sameVersionSamePayloadIsNoOp(){assertEquals(RemoteAppealDecision.NO_OP,SyncIntegrityRules.remoteDecision(2,2,false,true,false,false))}
    @Test fun staleRemoteKeepsNewerLocal(){assertEquals(RemoteAppealDecision.KEEP_LOCAL,SyncIntegrityRules.remoteDecision(3,2,false,false,false,false))}
    @Test fun newerRemoteAppliesWithoutLocalConflict(){assertEquals(RemoteAppealDecision.APPLY,SyncIntegrityRules.remoteDecision(2,3,false,false,false,false))}
    @Test fun localUnsyncedChangeIsConflict(){assertEquals(RemoteAppealDecision.CONFLICT,SyncIntegrityRules.remoteDecision(2,3,true,false,false,false))}
    @Test fun oldPendingCannotReopenFinalAppeal(){assertEquals(RemoteAppealDecision.KEEP_LOCAL,SyncIntegrityRules.remoteDecision(2,3,false,false,true,true))}

    @Test fun validOrderedBatchPassesValidation(){assertNull(SyncIntegrityRules.validatePullBatch(0,2,false,listOf(change(1),change(2))))}
    @Test fun nonMonotonicSequenceIsRejected(){assertEquals("SYNC_PULL_PROTOCOL_INVALID",SyncIntegrityRules.validatePullBatch(0,2,false,listOf(change(2),change(1))))}
    @Test fun nextCursorBehindCurrentIsRejected(){assertEquals("SYNC_PULL_PROTOCOL_INVALID",SyncIntegrityRules.validatePullBatch(5,4,false,emptyList()))}
    @Test fun malformedChangeIsRejected(){val bad=change(1);bad.getJSONObject("payload").remove("studentId");assertEquals("SYNC_PULL_MALFORMED_CHANGE",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(bad)))}
    @Test fun unsupportedEntityIsRejected(){val bad=change(1).put("entityType","User");assertEquals("SYNC_PULL_UNSUPPORTED_ENTITY",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(bad)))}
    @Test fun differentWorkspaceIsRejectedBeforeApply(){assertEquals("SYNC_PULL_WORKSPACE_MISMATCH",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(change(1)),"workspace-test"))}
    @Test fun cursorOnlyAdvancesAfterFullBatchSuccess(){assertEquals(3L,SyncIntegrityRules.cursorAfterBatch(3L,5L,false));assertEquals(5L,SyncIntegrityRules.cursorAfterBatch(3L,5L,true))}
    @Test fun emptyPageCannotClaimMoreData(){assertEquals("SYNC_PULL_PROTOCOL_INVALID",SyncIntegrityRules.validatePullBatch(7,7,true,emptyList()))}

    private fun change(sequence:Long):JSONObject{
        val payload=JSONObject()
            .put("id","a$sequence").put("studentId","s1").put("attendanceRecordId","r1")
            .put("lectureId","l1").put("subjectId","sub1").put("reasonType","OTHER")
            .put("description","valid appeal").put("attachmentRemoteUrl",JSONObject.NULL)
            .put("status","PENDING").put("submittedAt",900L).put("updatedAt",1001L)
            .put("reviewedBy",JSONObject.NULL).put("reviewedAt",JSONObject.NULL).put("decisionNote",JSONObject.NULL)
            .put("version",1L)
        return JSONObject().put("workspace","workspace-source").put("entityType","AttendanceAppeal").put("entityId","a$sequence")
            .put("operation","UPSERT").put("entityVersion",1L).put("updatedAt",1001L).put("payload",payload)
            .put("tombstone",false).put("serverVersion",sequence).put("sequence",sequence).put("serverUpdatedAt",2000L+sequence)
    }
}
