package com.hammam.attendai.sync

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.security.KeystoreCipher
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

/**
 * PHASE 5F sidecar metadata + deterministic outbox for academic hierarchy entities.
 *
 * The legacy hierarchy tables intentionally remain unchanged. Entities that pre-date 5F receive
 * a stable baseline (version=1, updatedAt=1 unless the entity already has a trustworthy timestamp).
 * A semantic payload difference against an existing sidecar increments localVersion exactly once.
 */
class AcademicHierarchySyncOutbox(
    private val db:HammamDatabase,
    private val encrypt:(String)->String,
){
    constructor(db:HammamDatabase,cipher:KeystoreCipher):this(db,cipher::encrypt)
    private val dao=db.coreDao()

    suspend fun backfillExistingIfEnabled(){
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        // Topological order is deliberate and paired with the queue priority query.
        dao.getUniversitiesOnce().forEach{recordUniversity(it,legacy=true)}
        dao.getAcademicYearsOnce().forEach{recordAcademicYear(it,legacy=true)}
        dao.getFacultiesOnce().forEach{recordFaculty(it,legacy=true)}
        dao.getSemestersOnce().forEach{recordSemester(it,legacy=true)}
        dao.getDepartmentsOnce().forEach{recordDepartment(it,legacy=true)}
        dao.getLevelsOnce().forEach{recordLevel(it,legacy=true)}
        dao.getBatchesOnce().forEach{recordBatch(it,legacy=true)}
        dao.getSectionsOnce().forEach{recordSection(it,legacy=true)}
        dao.getGroupsOnce().forEach{recordGroup(it,legacy=true)}
    }

    suspend fun recordUniversity(v:UniversityEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("University",v.id,HierarchySyncCodec.university(v),now,legacy)
    suspend fun recordAcademicYear(v:AcademicYearEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("AcademicYear",v.id,HierarchySyncCodec.academicYear(v),now,legacy)
    suspend fun recordFaculty(v:FacultyEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Faculty",v.id,HierarchySyncCodec.faculty(v),now,legacy)
    suspend fun recordDepartment(v:DepartmentEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Department",v.id,HierarchySyncCodec.department(v),now,legacy)
    suspend fun recordLevel(v:LevelEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Level",v.id,HierarchySyncCodec.level(v),now,legacy)
    suspend fun recordBatch(v:BatchEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Batch",v.id,HierarchySyncCodec.batch(v),now,legacy)
    suspend fun recordSection(v:SectionEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Section",v.id,HierarchySyncCodec.section(v),now,legacy)
    suspend fun recordGroup(v:GroupEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Group",v.id,HierarchySyncCodec.group(v),now,legacy)
    suspend fun recordSemester(v:SemesterEntity,now:Long=System.currentTimeMillis(),legacy:Boolean=false)=record("Semester",v.id,HierarchySyncCodec.semester(v),now,legacy,if(legacy)v.createdAt else now,if(legacy)v.updatedAt else now)

    suspend fun acceptRemote(entityType:String,entityId:String,remoteVersion:Long,remoteUpdatedAt:Long,payload:JSONObject,serverVersion:Long){
        val fingerprint=HierarchySyncCodec.fingerprintPayload(payload)
        val existing=dao.getSyncEntityMetadata(entityType,entityId)
        val firstSeen=existing?.firstSeenAt ?: remoteUpdatedAt
        val metadata=SyncEntityMetadataEntity(entityType,entityId,remoteVersion,firstSeen,remoteUpdatedAt,fingerprint,serverVersion)
        if(existing==null)dao.insertSyncEntityMetadata(metadata) else dao.updateSyncEntityMetadata(metadata)
    }

    private suspend fun record(entityType:String,entityId:String,content:JSONObject,now:Long,legacy:Boolean,sourceFirstSeenAt:Long?=null,sourceUpdatedAt:Long?=null){
        val fingerprint=HierarchySyncCodec.fingerprintPayload(content)
        val existing=dao.getSyncEntityMetadata(entityType,entityId)
        val metadata=AcademicHierarchyMetadataRules.evolve(existing,entityType,entityId,fingerprint,now,legacy,sourceFirstSeenAt,sourceUpdatedAt)
        if(existing==null)dao.insertSyncEntityMetadata(metadata) else if(metadata!=existing)dao.updateSyncEntityMetadata(metadata)
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        val payload=HierarchySyncCodec.withMetadata(content,metadata.localVersion,metadata.updatedAt)
        enqueue(entityType,entityId,metadata.localVersion,payload.toString(),metadata.updatedAt)
    }

    private suspend fun enqueue(entityType:String,entityId:String,entityVersion:Long,payload:String,now:Long){
        val key=AcademicHierarchyMetadataRules.idempotencyKey(entityType,entityId,entityVersion)
        val queueId=AcademicHierarchyMetadataRules.queueId(key)
        dao.enqueueSync(SyncQueueEntity(
            id=queueId,entityType=entityType,entityId=entityId,operation="UPSERT",
            payloadCiphertext=encrypt(payload),createdAt=now,lastAttempt=null,retryCount=0,status=QueueStatus.PENDING,error=null,
            idempotencyKey=key,version=1
        ))
    }
}

internal object AcademicHierarchyMetadataRules {
    fun evolve(existing:SyncEntityMetadataEntity?,entityType:String,entityId:String,fingerprint:String,now:Long,legacy:Boolean,sourceFirstSeenAt:Long?=null,sourceUpdatedAt:Long?=null):SyncEntityMetadataEntity{
        if(existing==null){
            val first=if(legacy)(sourceFirstSeenAt?.takeIf{it>0L}?:1L) else (sourceFirstSeenAt?.takeIf{it>0L}?:now.coerceAtLeast(1L))
            val updated=if(legacy)(sourceUpdatedAt?.takeIf{it>0L}?:first) else (sourceUpdatedAt?.takeIf{it>0L}?:now.coerceAtLeast(first))
            return SyncEntityMetadataEntity(entityType,entityId,1L,first,updated,fingerprint,null)
        }
        if(existing.payloadFingerprint==fingerprint)return existing
        val nextAt=max(max(now,existing.updatedAt+1L),sourceUpdatedAt?:0L).coerceAtLeast(1L)
        return existing.copy(localVersion=existing.localVersion+1L,updatedAt=nextAt,payloadFingerprint=fingerprint,lastSyncedServerVersion=null)
    }
    fun idempotencyKey(entityType:String,entityId:String,version:Long)="${entityType.lowercase()}:$entityId:$version"
    fun queueId(key:String)=UUID.nameUUIDFromBytes("sync:$key".toByteArray(StandardCharsets.UTF_8)).toString()
}

internal object HierarchySyncCodec {
    fun university(v:UniversityEntity)=JSONObject().put("id",v.id).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun academicYear(v:AcademicYearEntity)=JSONObject().put("id",v.id).put("name",v.name).put("startDate",v.startDate).put("endDate",v.endDate).put("isActive",v.isActive)
    fun faculty(v:FacultyEntity)=JSONObject().put("id",v.id).put("universityId",v.universityId).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun department(v:DepartmentEntity)=JSONObject().put("id",v.id).put("facultyId",v.facultyId).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun level(v:LevelEntity)=JSONObject().put("id",v.id).put("departmentId",v.departmentId).put("name",v.name).put("orderIndex",v.orderIndex).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun batch(v:BatchEntity)=JSONObject().put("id",v.id).put("levelId",v.levelId).put("academicYearId",v.academicYearId).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun section(v:SectionEntity)=JSONObject().put("id",v.id).put("batchId",v.batchId).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun group(v:GroupEntity)=JSONObject().put("id",v.id).put("sectionId",v.sectionId).put("name",v.name).put("archivedAt",v.archivedAt?:JSONObject.NULL)
    fun semester(v:SemesterEntity)=JSONObject().put("id",v.id).put("name",v.name).put("academicYearId",v.academicYearId).put("startDate",v.startDate).put("endDate",v.endDate).put("status",v.status.name).put("createdAt",v.createdAt)

    fun withMetadata(content:JSONObject,version:Long,updatedAt:Long):JSONObject{
        val copy=JSONObject(content.toString())
        copy.put("updatedAt",updatedAt).put("version",version)
        return copy
    }

    fun fingerprintPayload(payload:JSONObject):String{
        val content=JSONObject(payload.toString()).apply{remove("version");remove("updatedAt")}
        val canonical=canonical(content)
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }

    private fun canonical(value:Any?):String=when(value){
        null,JSONObject.NULL->"null"
        is JSONObject->{
            val keys=mutableListOf<String>();val it=value.keys();while(it.hasNext())keys+=it.next();keys.sort()
            keys.joinToString(prefix="{",postfix="}",separator=","){k->JSONObject.quote(k)+":"+canonical(value.opt(k))}
        }
        is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]",separator=","){canonical(value.opt(it))}
        is String->JSONObject.quote(value)
        is Number,is Boolean->value.toString()
        else->JSONObject.quote(value.toString())
    }
}
