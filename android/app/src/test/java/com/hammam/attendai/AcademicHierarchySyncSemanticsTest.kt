package com.hammam.attendai

import com.hammam.attendai.data.local.entity.SyncEntityMetadataEntity
import com.hammam.attendai.data.local.entity.UniversityEntity
import com.hammam.attendai.sync.AcademicHierarchyMetadataRules
import com.hammam.attendai.sync.HierarchySyncCodec
import com.hammam.attendai.sync.RemoteEntityDecision
import com.hammam.attendai.sync.SyncIntegrityRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcademicHierarchySyncSemanticsTest {
    @Test fun hierarchyAndPhase5gGraphTypesAreSupportedWhileSecurityTypesRemainExcluded(){
        for(type in listOf("University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord"))
            assertTrue(type, SyncIntegrityRules.supported(type,"UPSERT"))
        for(type in listOf("User","Role","Permission","StudentDevice","PresenceEvent","PresenceInterval"))
            assertFalse(type, SyncIntegrityRules.supported(type,"UPSERT"))
    }

    @Test fun allHierarchyPayloadsValidateStrictly(){
        val payloads=listOf(
            Triple("University","u1",university()), Triple("AcademicYear","y1",year()),
            Triple("Faculty","f1",faculty()), Triple("Department","d1",department()),
            Triple("Level","l1",level()), Triple("Semester","sem1",semester()),
            Triple("Batch","b1",batch()), Triple("Section","s1",section()), Triple("Group","g1",group())
        )
        for((type,id,payload) in payloads){
            assertNotNull(type,SyncIntegrityRules.queuedPayloadMetadata(type,payload,id))
            val extra=JSONObject(payload.toString()).put("unexpected",true)
            assertNull("extra field: $type",SyncIntegrityRules.queuedPayloadMetadata(type,extra,id))
            val wrongId=JSONObject(payload.toString()).put("id","other")
            assertNull("id mismatch: $type",SyncIntegrityRules.queuedPayloadMetadata(type,wrongId,id))
            val wrongVersion=JSONObject(payload.toString()).put("version",2L)
            assertNull("version mismatch: $type",SyncIntegrityRules.queuedPayloadMetadata(type,wrongVersion,id))
            val missingName=JSONObject(payload.toString()).apply{remove("name")}
            assertNull("missing field: $type",SyncIntegrityRules.queuedPayloadMetadata(type,missingName,id))
        }
    }

    @Test fun academicDatesAndSemesterStatusAreStrict(){
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AcademicYear",year().put("startDate","2026-99-01"),"y1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("AcademicYear",year().put("startDate","2028-01-01"),"y1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Semester",semester().put("status","UNKNOWN"),"sem1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Semester",semester().put("updatedAt",999L),"sem1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("Level",level().put("orderIndex",-1),"l1"))
        assertNull(SyncIntegrityRules.queuedPayloadMetadata("University",university().put("archivedAt",0),"u1"))
    }

    @Test fun parentReferencesAreExplicitAndTopological(){
        assertEquals(listOf("University" to "u1"),SyncIntegrityRules.hierarchyParentRefs("Faculty",faculty()))
        assertEquals(listOf("Faculty" to "f1"),SyncIntegrityRules.hierarchyParentRefs("Department",department()))
        assertEquals(listOf("Department" to "d1"),SyncIntegrityRules.hierarchyParentRefs("Level",level()))
        assertEquals(listOf("AcademicYear" to "y1"),SyncIntegrityRules.hierarchyParentRefs("Semester",semester()))
        assertEquals(listOf("Level" to "l1","AcademicYear" to "y1"),SyncIntegrityRules.hierarchyParentRefs("Batch",batch()))
        assertEquals(listOf("Batch" to "b1"),SyncIntegrityRules.hierarchyParentRefs("Section",section()))
        assertEquals(listOf("Section" to "s1"),SyncIntegrityRules.hierarchyParentRefs("Group",group()))
    }

    @Test fun unifiedPullAcceptsFullOrderedHierarchyAndRejectsSequenceRegression(){
        val entries=listOf(
            Triple("University","u1",university()), Triple("AcademicYear","y1",year()), Triple("Faculty","f1",faculty()),
            Triple("Department","d1",department()), Triple("Level","l1",level()), Triple("Semester","sem1",semester()),
            Triple("Batch","b1",batch()), Triple("Section","s1",section()), Triple("Group","g1",group())
        )
        val changes=entries.mapIndexed{i,(type,id,p)->change((i+1).toLong(),type,id,p)}
        assertNull(SyncIntegrityRules.validatePullBatch(0,changes.size.toLong(),false,changes,"workspace-source"))
        val reversed=listOf(changes[1],changes[0])
        assertEquals("SYNC_PULL_PROTOCOL_INVALID",SyncIntegrityRules.validatePullBatch(0,2,false,reversed,"workspace-source"))
    }

    @Test fun metadataBootstrapIsDeterministicAndSemanticChangeIncrementsOnce(){
        val first=AcademicHierarchyMetadataRules.evolve(null,"University","u1","fp-a",9999L,true)
        assertEquals(1L,first.localVersion);assertEquals(1L,first.firstSeenAt);assertEquals(1L,first.updatedAt);assertNull(first.lastSyncedServerVersion)
        val unchanged=AcademicHierarchyMetadataRules.evolve(first,"University","u1","fp-a",10000L,false)
        assertEquals(first,unchanged)
        val synced=first.copy(lastSyncedServerVersion=7L)
        val changed=AcademicHierarchyMetadataRules.evolve(synced,"University","u1","fp-b",10000L,false)
        assertEquals(2L,changed.localVersion);assertTrue(changed.updatedAt>synced.updatedAt);assertNull(changed.lastSyncedServerVersion)
        assertEquals(AcademicHierarchyMetadataRules.queueId(AcademicHierarchyMetadataRules.idempotencyKey("University","u1",2)),AcademicHierarchyMetadataRules.queueId(AcademicHierarchyMetadataRules.idempotencyKey("University","u1",2)))
    }

    @Test fun metadataContainsOnlySyncStateAndFingerprintIgnoresEnvelopeMetadata(){
        val meta=SyncEntityMetadataEntity("University","u1",1,1,1,"hash",3)
        assertEquals("University",meta.entityType);assertEquals("u1",meta.entityId)
        val base=HierarchySyncCodec.university(UniversityEntity("u1","University",null))
        val p1=HierarchySyncCodec.withMetadata(base,1,100)
        val p2=HierarchySyncCodec.withMetadata(base,9,999)
        assertEquals(HierarchySyncCodec.fingerprintPayload(p1),HierarchySyncCodec.fingerprintPayload(p2))
        val renamed=JSONObject(p1.toString()).put("name","Changed")
        assertNotEquals(HierarchySyncCodec.fingerprintPayload(p1),HierarchySyncCodec.fingerprintPayload(renamed))
    }

    @Test fun conflictPolicyNeverSilentlyOverwritesSameVersionOrPendingLocal(){
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(null,1,false,false))
        assertEquals(RemoteEntityDecision.KEEP_LOCAL,SyncIntegrityRules.referenceDecision(3,2,false,false))
        assertEquals(RemoteEntityDecision.NO_OP,SyncIntegrityRules.referenceDecision(2,2,false,true))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,2,false,false))
        assertEquals(RemoteEntityDecision.CONFLICT,SyncIntegrityRules.referenceDecision(2,3,true,false))
        assertEquals(RemoteEntityDecision.APPLY,SyncIntegrityRules.referenceDecision(2,3,false,false))
    }

    @Test fun cursorNeverAdvancesWhenHierarchyApplyFails(){
        assertEquals(10L,SyncIntegrityRules.cursorAfterBatch(10,19,false))
        assertEquals(19L,SyncIntegrityRules.cursorAfterBatch(10,19,true))
    }

    private fun university()=JSONObject().put("id","u1").put("name","University").put("archivedAt",JSONObject.NULL).put("updatedAt",1001L).put("version",1L)
    private fun year()=JSONObject().put("id","y1").put("name","2026/27").put("startDate","2026-09-01").put("endDate","2027-06-30").put("isActive",true).put("updatedAt",2001L).put("version",1L)
    private fun faculty()=JSONObject().put("id","f1").put("universityId","u1").put("name","Faculty").put("archivedAt",JSONObject.NULL).put("updatedAt",3001L).put("version",1L)
    private fun department()=JSONObject().put("id","d1").put("facultyId","f1").put("name","Department").put("archivedAt",JSONObject.NULL).put("updatedAt",4001L).put("version",1L)
    private fun level()=JSONObject().put("id","l1").put("departmentId","d1").put("name","Level 1").put("orderIndex",1).put("archivedAt",JSONObject.NULL).put("updatedAt",5001L).put("version",1L)
    private fun semester()=JSONObject().put("id","sem1").put("name","Semester 1").put("academicYearId","y1").put("startDate","2026-09-01").put("endDate","2027-01-31").put("status","ACTIVE").put("createdAt",1000L).put("updatedAt",6001L).put("version",1L)
    private fun batch()=JSONObject().put("id","b1").put("levelId","l1").put("academicYearId","y1").put("name","Batch").put("archivedAt",JSONObject.NULL).put("updatedAt",7001L).put("version",1L)
    private fun section()=JSONObject().put("id","s1").put("batchId","b1").put("name","Section").put("archivedAt",JSONObject.NULL).put("updatedAt",8001L).put("version",1L)
    private fun group()=JSONObject().put("id","g1").put("sectionId","s1").put("name","Group").put("archivedAt",JSONObject.NULL).put("updatedAt",9001L).put("version",1L)
    private fun change(sequence:Long,type:String,id:String,payload:JSONObject)=JSONObject()
        .put("workspace","workspace-source").put("entityType",type).put("entityId",id).put("operation","UPSERT")
        .put("entityVersion",payload.getLong("version")).put("updatedAt",payload.getLong("updatedAt")).put("payload",payload)
        .put("tombstone",false).put("serverVersion",sequence).put("sequence",sequence).put("serverUpdatedAt",10000L+sequence)
}
