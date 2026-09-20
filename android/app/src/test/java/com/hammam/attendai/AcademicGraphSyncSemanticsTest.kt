package com.hammam.attendai

import com.hammam.attendai.data.local.entity.AttendanceRecordEntity
import com.hammam.attendai.data.local.entity.LectureEntity
import com.hammam.attendai.data.local.entity.StudentEntity
import com.hammam.attendai.data.local.entity.SubjectEntity
import com.hammam.attendai.domain.model.*
import com.hammam.attendai.sync.AcademicGraphSyncCodec
import com.hammam.attendai.sync.HierarchySyncCodec
import com.hammam.attendai.sync.RemoteEntityDecision
import com.hammam.attendai.sync.SyncIntegrityRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcademicGraphSyncSemanticsTest {
    @Test fun studentCodecMinimizesDeviceDataAndSupportsLegacyNullAcademicScope(){
        val student=StudentEntity("stu1","2026001","Student One","student one",null,null,null,null,null,null,StudentStatus.ACTIVE,"device-secret-link",1000,2000,null,7)
        val content=AcademicGraphSyncCodec.student(student)
        assertFalse(content.has("registeredDeviceId"));assertFalse(content.has("version"));assertFalse(content.has("updatedAt"))
        val payload=HierarchySyncCodec.withMetadata(content,1,3000)
        assertNotNull(SyncIntegrityRules.queuedPayloadMetadata("Student",payload,"stu1"))
        assertTrue(payload.isNull("levelId"));assertTrue(payload.isNull("groupId"))
    }

    @Test fun graphCodecsAndValidatorsAreStrict(){
        val entries=listOf(
            Triple("Student","stu1",student()), Triple("Subject","sub1",subject()),
            Triple("Lecture","lec1",lecture()), Triple("AttendanceRecord","rec1",record())
        )
        for((type,id,payload) in entries){
            assertNotNull(type,SyncIntegrityRules.queuedPayloadMetadata(type,payload,id))
            val extra=JSONObject(payload.toString()).put("unexpected",true);assertNull("extra $type",SyncIntegrityRules.queuedPayloadMetadata(type,extra,id))
            val wrongId=JSONObject(payload.toString()).put("id","wrong");assertNull("id $type",SyncIntegrityRules.queuedPayloadMetadata(type,wrongId,id))
            val invalidVersion=JSONObject(payload.toString()).put("version",0L);assertNull("invalid version $type",SyncIntegrityRules.queuedPayloadMetadata(type,invalidVersion,id))
            val mismatchedEnvelope=change(1,type,id,payload).put("entityVersion",2L)
            assertEquals("envelope version mismatch $type","SYNC_PULL_MALFORMED_CHANGE",SyncIntegrityRules.validatePullBatch(0,1,false,listOf(mismatchedEnvelope),"workspace-source"))
            val wrongTime=JSONObject(payload.toString()).put("updatedAt",0L);assertNull("time $type",SyncIntegrityRules.queuedPayloadMetadata(type,wrongTime,id))
        }
    }

    @Test fun parentReferencesExpressTheReal5gGraph(){
        assertEquals(listOf("Level" to "lvl1","Batch" to "b1","Section" to "sec1","Group" to "g1"),SyncIntegrityRules.hierarchyParentRefs("Student",student()))
        assertEquals(listOf("Level" to "lvl1","Semester" to "sem1","Group" to "g1","Teacher" to "t1","AttendancePolicy" to "p1"),SyncIntegrityRules.hierarchyParentRefs("Subject",subject()))
        assertEquals(listOf("Subject" to "sub1","Teacher" to "t1","Semester" to "sem1","Group" to "g1"),SyncIntegrityRules.hierarchyParentRefs("Lecture",lecture()))
        assertEquals(listOf("Student" to "stu1","Lecture" to "lec1"),SyncIntegrityRules.hierarchyParentRefs("AttendanceRecord",record()))
        assertEquals(listOf("Student" to "stu1","AttendanceRecord" to "rec1","Lecture" to "lec1","Subject" to "sub1"),SyncIntegrityRules.hierarchyParentRefs("AttendanceAppeal",appeal()))
    }

    @Test fun graphDomainInvariantsAreRejected(){
        val partialStudent=student().put("batchId",JSONObject.NULL)
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Student",partialStudent,"stu1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Subject",subject().put("teacherId",JSONObject.NULL),"sub1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Subject",subject().put("attendancePolicyId",JSONObject.NULL),"sub1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Lecture",lecture().put("scheduledEnd",20000L),"lec1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Lecture",lecture().put("status","UNKNOWN"),"lec1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendanceRecord",record().put("attendancePercentage",1.1),"rec1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendanceRecord",record().put("confidenceScore",-0.1),"rec1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendanceRecord",record().put("verifiedPresenceSeconds",1001L),"rec1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AttendanceRecord",record().put("finalStatus","UNKNOWN"),"rec1"))
    }

    @Test fun unifiedPullAcceptsOrdered5gGraphAndNeverAdvancesFailedCursor(){
        val changes=listOf(
            change(1,"Student","stu1",student()),change(2,"Subject","sub1",subject()),
            change(3,"Lecture","lec1",lecture()),change(4,"AttendanceRecord","rec1",record()),change(5,"AttendanceAppeal","app1",appeal())
        )
        assertNull(SyncIntegrityRules.validatePullBatch(0,5,false,changes,"workspace-source"))
        assertEquals(7L,SyncIntegrityRules.cursorAfterBatch(7,12,false));assertEquals(12L,SyncIntegrityRules.cursorAfterBatch(7,12,true))
    }

    @Test fun conflictPolicyProtectsLocalPendingGraphMutation(){
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(null,1,false,false))
        assertEquals(RemoteEntityDecision.KEEP_LOCAL,SyncIntegrityRules.referenceDecision(3,2,false,false))
        assertEquals(RemoteEntityDecision.NO_OP,SyncIntegrityRules.referenceDecision(2,2,false,true))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,2,false,false))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,3,true,false))
    }

    @Test fun codecsExcludePresenceTelemetryAndDeviceSecrets(){
        val s=StudentEntity("stu1",null,"S","s",null,null,null,null,null,null,StudentStatus.ACTIVE,"device-id",1000,1000)
        assertFalse(AcademicGraphSyncCodec.student(s).toString().contains("device"))
        val r=AttendanceRecordEntity("rec1","lec1","stu1",null,null,0,1000,0.0,0,0,0.0,FinalAttendanceStatus.MANUAL_REVIEW,ApprovalStatus.DRAFT,PresenceSource.BLE,null,1000,1000)
        val encoded=AcademicGraphSyncCodec.attendanceRecord(r).toString()
        assertFalse(encoded.contains("presenceSecret"));assertFalse(encoded.contains("PresenceEvent"));assertFalse(encoded.contains("deviceId"))
    }

    private fun student()=JSONObject().put("id","stu1").put("universityNumber","2026001").put("fullName","Student").put("normalizedName","student").put("phoneNumber",JSONObject.NULL).put("whatsappNumber",JSONObject.NULL).put("levelId","lvl1").put("batchId","b1").put("sectionId","sec1").put("groupId","g1").put("status","ACTIVE").put("createdAt",1000L).put("archivedAt",JSONObject.NULL).put("updatedAt",13001L).put("version",1L)
    private fun subject()=JSONObject().put("id","sub1").put("code","MED101").put("name","Medicine").put("teacherId","t1").put("levelId","lvl1").put("semesterId","sem1").put("groupId","g1").put("attendancePolicyId","p1").put("status","ACTIVE").put("archivedAt",JSONObject.NULL).put("updatedAt",14001L).put("version",1L)
    private fun lecture()=JSONObject().put("id","lec1").put("subjectId","sub1").put("teacherId","t1").put("semesterId","sem1").put("groupId","g1").put("scheduledStart",20000L).put("scheduledEnd",21000L).put("actualStart",JSONObject.NULL).put("actualEnd",JSONObject.NULL).put("room","R1").put("status","SCHEDULED").put("attendancePolicySnapshotJson","{}").put("createdAt",1000L).put("updatedAt",15001L).put("version",1L)
    private fun record()=JSONObject().put("id","rec1").put("lectureId","lec1").put("studentId","stu1").put("firstSeenAt",20010L).put("lastSeenAt",20800L).put("verifiedPresenceSeconds",790L).put("lectureDurationSeconds",1000L).put("attendancePercentage",0.79).put("lateMinutes",0).put("earlyLeaveMinutes",0).put("confidenceScore",0.9).put("finalStatus","PRESENT").put("approvalStatus","APPROVED").put("source","MANUAL").put("notes",JSONObject.NULL).put("createdAt",1000L).put("updatedAt",16001L).put("version",1L)
    private fun appeal()=JSONObject().put("id","app1").put("studentId","stu1").put("attendanceRecordId","rec1").put("lectureId","lec1").put("subjectId","sub1").put("reasonType","OTHER").put("description","Review").put("attachmentRemoteUrl",JSONObject.NULL).put("status","PENDING").put("submittedAt",16000L).put("updatedAt",17001L).put("reviewedBy",JSONObject.NULL).put("reviewedAt",JSONObject.NULL).put("decisionNote",JSONObject.NULL).put("version",1L)
    private fun change(sequence:Long,type:String,id:String,payload:JSONObject)=JSONObject().put("workspace","workspace-source").put("entityType",type).put("entityId",id).put("operation","UPSERT").put("entityVersion",payload.getLong("version")).put("updatedAt",payload.getLong("updatedAt")).put("payload",payload).put("tombstone",false).put("serverVersion",sequence).put("sequence",sequence).put("serverUpdatedAt",18000L+sequence)
}
