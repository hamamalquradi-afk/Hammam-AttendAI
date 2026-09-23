package com.hammam.attendai

import com.hammam.attendai.sync.RemoteEntityDecision
import com.hammam.attendai.sync.SyncIntegrityRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MultiEntitySyncFoundationTest {
    @Test fun teacherPayloadIsStrictAndAccepted(){
        val payload=teacherPayload("t1")
        assertNotNull(SyncIntegrityRules.queuedPayloadMetadata("Teacher",payload,"t1"))
        payload.remove("normalizedName")
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Teacher",payload,"t1"))
    }
    @Test fun teacherEnvelopeIdMismatchIsRejected(){assertNull(SyncIntegrityRules.queuedPayloadMetadata("Teacher",teacherPayload("t1"),"t2"))}
    @Test fun globalPolicyIsAcceptedButScopedPolicyIsRejected(){
        assertNotNull(SyncIntegrityRules.queuedPayloadMetadata("AttendancePolicy",policyPayload("p1"),"p1"))
        val scoped=policyPayload("p1").put("scopeType","SUBJECT").put("scopeId","sub1")
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendancePolicy",scoped,"p1"))
    }
    @Test fun invalidPolicyThresholdOrderingIsRejected(){
        val bad=policyPayload("p1").put("fullAttendanceThreshold",0.1)
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendancePolicy",bad,"p1"))
    }
    @Test fun selectedEntitiesValidateInUnifiedPullStream(){
        val teacher=change(1,"Teacher","t1",teacherPayload("t1"),2001)
        val policy=change(2,"AttendancePolicy","p1",policyPayload("p1"),3001)
        assertNull(SyncIntegrityRules.validatePullBatch(0,2,false,listOf(teacher,policy),"workspace-source"))
    }
    @Test fun wrongWorkspaceAndUnsupportedChildAreRejected(){
        val teacher=change(1,"Teacher","t1",teacherPayload("t1"),2001)
        assertEquals("SYNC_PULL_WORKSPACE_MISMATCH",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(teacher),"workspace-other"))
        teacher.put("entityType","User")
        assertEquals("SYNC_PULL_UNSUPPORTED_ENTITY",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(teacher),"workspace-source"))
    }
    @Test fun rootReferenceConflictPolicyProtectsLocalPendingChange(){
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(null,1,false,false))
        assertEquals(RemoteEntityDecision.NO_OP,SyncIntegrityRules.referenceDecision(2,2,false,true))
        assertEquals(RemoteEntityDecision.KEEP_LOCAL,SyncIntegrityRules.referenceDecision(3,2,false,false))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,3,true,false))
    }
    @Test fun cursorDoesNotAdvanceWhenBatchApplyFails(){assertEquals(4L,SyncIntegrityRules.cursorAfterBatch(4,6,false))}

    private fun teacherPayload(id:String)=JSONObject()
        .put("id",id).put("fullName","Dr One").put("normalizedName","dr one")
        .put("phone",JSONObject.NULL).put("whatsapp",JSONObject.NULL).put("email",JSONObject.NULL)
        .put("preferredNotificationChannel",JSONObject.NULL).put("notificationsEnabled",true)
        .put("createdAt",1000L).put("updatedAt",2001L).put("archivedAt",JSONObject.NULL).put("version",1L)

    private fun policyPayload(id:String)=JSONObject()
        .put("id",id).put("name","Global Policy").put("scopeType","GLOBAL").put("scopeId",JSONObject.NULL)
        .put("fullAttendanceThreshold",0.8).put("partialAttendanceThreshold",0.5).put("lateAfterMinutes",10)
        .put("earlyLeaveThresholdMinutes",5).put("absenceThreshold",0.2).put("temporaryMissingGraceSeconds",30L)
        .put("minimumPresenceVerificationSeconds",60L).put("confidenceThreshold",0.7)
        .put("createdAt",1000L).put("updatedAt",3001L).put("version",1L)

    private fun change(sequence:Long,type:String,id:String,payload:JSONObject,updatedAt:Long)=JSONObject()
        .put("workspace","workspace-source").put("entityType",type).put("entityId",id).put("operation","UPSERT")
        .put("entityVersion",1L).put("updatedAt",updatedAt).put("payload",payload).put("tombstone",false)
        .put("serverVersion",sequence).put("sequence",sequence).put("serverUpdatedAt",4000L+sequence)
}
