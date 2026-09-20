package com.hammam.attendai.sync

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AppSettingEntity
import com.hammam.attendai.data.local.entity.AttendanceAppealEntity
import com.hammam.attendai.data.local.entity.AttendancePolicyEntity
import com.hammam.attendai.data.local.entity.TeacherEntity
import com.hammam.attendai.data.local.entity.UniversityEntity
import com.hammam.attendai.data.local.entity.AcademicYearEntity
import com.hammam.attendai.data.local.entity.FacultyEntity
import com.hammam.attendai.data.local.entity.DepartmentEntity
import com.hammam.attendai.data.local.entity.LevelEntity
import com.hammam.attendai.data.local.entity.SemesterEntity
import com.hammam.attendai.data.local.entity.BatchEntity
import com.hammam.attendai.data.local.entity.SectionEntity
import com.hammam.attendai.data.local.entity.GroupEntity
import com.hammam.attendai.data.local.entity.StudentEntity
import com.hammam.attendai.data.local.entity.SubjectEntity
import com.hammam.attendai.data.local.entity.TeacherSubjectEntity
import com.hammam.attendai.data.local.entity.LectureEntity
import com.hammam.attendai.data.local.entity.AttendanceRecordEntity
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.domain.model.AppealStatus
import com.hammam.attendai.domain.model.AppealSyncStatus
import com.hammam.attendai.domain.model.StudentStatus
import com.hammam.attendai.domain.model.LectureStatus
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.domain.model.ApprovalStatus
import com.hammam.attendai.domain.model.PresenceSource
import org.json.JSONObject

interface BackendClient {
    suspend fun post(path:String,payload:String,idempotencyKey:String):BackendResult
    suspend fun postWithBearer(path:String,payload:String,idempotencyKey:String,bearerToken:String?):BackendResult
}
data class BackendResult(val ok:Boolean,val providerMessageId:String?=null,val retryable:Boolean=true,val error:String?=null,val responseBody:String?=null)

private fun decryptOrFail(ciphertext:String,decrypt:(String)->String):Result<String> = runCatching{decrypt(ciphertext)}

class SyncProcessor(private val db:HammamDatabase,private val backend:BackendClient,private val decrypt:(String)->String,private val encrypt:(String)->String,private val workspaceProvider:suspend ()->String?,private val authTokenProvider:suspend ()->String?,private val onAuthFailure:suspend (String?)->Unit={}){
    private fun cursorKey(workspace:String)="sync.pull.cursor.$workspace"
    private fun pullErrorKey(workspace:String)="sync.pull.lastError.$workspace"
    suspend fun prepareLocalBackfill(){
        val dao=db.coreDao()
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return
        AcademicReferenceSyncOutbox(db,encrypt).backfillExistingIfEnabled()
        AcademicHierarchySyncOutbox(db,encrypt).backfillExistingIfEnabled()
        AcademicGraphSyncOutbox(db,encrypt).backfillExistingIfEnabled()
    }
    suspend fun process():Boolean{
        val dao=db.coreDao()
        if(dao.isFeatureEnabled("CLOUD_SYNC")!=true)return true
        // Local backfill is idempotent and does not require authentication or network I/O.
        prepareLocalBackfill()
        val workspace=workspaceProvider()?.trim()
        if(!SyncIntegrityRules.validWorkspaceId(workspace)){putSetting("sync.identity.lastError","SYNC_IDENTITY_NOT_CONFIGURED");return true}
        val token=authTokenProvider()
        if(token.isNullOrBlank()){putSetting("sync.identity.lastError","AUTH_REQUIRED");return true}
        putSetting("sync.identity.lastError","")
        val now=System.currentTimeMillis()
        for(stale in dao.staleProcessingSync(now-15*60_000L)){
            val claimAt=stale.lastAttempt?:continue
            dao.recoverSyncClaim(stale.id,stale.version,claimAt,QueueStatus.PENDING)
        }
        var all=true
        for(candidate in dao.pendingSync()){
            if(!SyncIntegrityRules.claimable(candidate.status))continue
            val claimAt=System.currentTimeMillis()
            if(dao.claimSync(candidate.id,candidate.status,candidate.version,claimAt)==0)continue
            val job=candidate.copy(status=QueueStatus.PROCESSING,lastAttempt=claimAt,version=candidate.version+1)
            if(!SyncIntegrityRules.supported(job.entityType,job.operation)){
                dao.updateSync(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error=if(job.entityType!="AttendanceAppeal")"UNSUPPORTED_SYNC_ENTITY" else "INVALID_SYNC_OPERATION"));continue
            }
            val payload=decryptOrFail(job.payloadCiphertext,decrypt).getOrNull()
            if(payload==null){dao.updateSync(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="LOCAL_DECRYPT_FAILED"));all=false;continue}
            val parsed=runCatching{JSONObject(payload)}.getOrNull()
            val metadata=parsed?.let{SyncIntegrityRules.queuedPayloadMetadata(job.entityType,it,job.entityId)}
            if(parsed==null||metadata==null){dao.updateSync(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="INVALID_SYNC_PAYLOAD"));continue}
            val waitingParent=SyncIntegrityRules.hierarchyParentRefs(job.entityType,parsed).firstOrNull{(type,id)->!parentConfirmed(workspace!!,type,id)}
            if(waitingParent!=null){dao.updateSync(job.copy(status=QueueStatus.PENDING,retryCount=job.retryCount,error="SYNC_PARENT_PENDING:${waitingParent.first}:${waitingParent.second}"));continue}
            val envelope=JSONObject().put("workspace",workspace).put("entityType",job.entityType).put("entityId",job.entityId).put("operation",job.operation).put("entityVersion",metadata.version).put("updatedAt",metadata.updatedAt).put("payload",parsed).toString()
            val r=backend.post("/api/v1/sync/push",envelope,job.idempotencyKey)
            val attemptedAt=System.currentTimeMillis();val persisted=SyncIntegrityRules.persistenceConfirmed(r)
            val effective=if(r.ok&&!persisted)r.copy(ok=false,retryable=false,error="SYNC_PERSISTENCE_NOT_CONFIRMED")else r
            if(SyncIntegrityRules.identityRecoveryRequired(effective)){
                dao.updateSync(job.copy(status=SyncIntegrityRules.queueStatusAfterAuthFailure(),retryCount=job.retryCount,lastAttempt=attemptedAt,error=effective.error))
                putSetting("sync.identity.lastError",effective.error?:"AUTH_REQUIRED");onAuthFailure(effective.error);return true
            }
            val attempts=job.retryCount+if(effective.ok)0 else 1
            val manual=effective.error in setOf("SYNC_VERSION_CONFLICT","SYNC_CONFLICT","SYNC_IDEMPOTENCY_MISMATCH")
            val status=when{effective.ok->QueueStatus.SENT;manual||SyncIntegrityRules.permanent(effective)||attempts>=5->QueueStatus.NEEDS_MANUAL_REVIEW;else->QueueStatus.FAILED}
            dao.updateSync(job.copy(status=status,retryCount=attempts,lastAttempt=attemptedAt,error=effective.error))
            if(job.entityType=="AttendanceAppeal"){
                dao.updateAppealSyncStatusOnly(job.entityId,if(effective.ok)AppealSyncStatus.SYNCED else AppealSyncStatus.SYNC_FAILED)
                if(effective.ok)SyncIntegrityRules.serverVersion(effective)?.let{putSetting("sync.server.$workspace.AttendanceAppeal.${job.entityId}",it.toString())}
            }
            if(effective.ok&&job.entityType in setOf("Teacher","AttendancePolicy"))SyncIntegrityRules.serverVersion(effective)?.let{putSetting("sync.server.$workspace.${job.entityType}.${job.entityId}",it.toString())}
            if(effective.ok&&job.entityType in setOf("University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord"))SyncIntegrityRules.serverVersion(effective)?.let{dao.markSyncEntityServerVersion(job.entityType,job.entityId,it)}
            if(!effective.ok&&status==QueueStatus.FAILED)all=false
        }
        if(!pullAll(workspace!!))all=false
        return all
    }
    private suspend fun pullAll(workspace:String):Boolean{
        val cursorKey=cursorKey(workspace)
        val pullErrorKey=pullErrorKey(workspace)
        var cursor=getSetting(cursorKey)?.toLongOrNull()?:0L
        repeat(20){
            val request=JSONObject().put("workspace",workspace).put("cursor",cursor).put("limit",50).toString()
            val r=backend.post("/api/v1/sync/pull",request,"pull:$workspace:$cursor")
            if(!r.ok){
                recordPullError(pullErrorKey,r.error?:"SYNC_PULL_FAILED",r.responseBody)
                if(SyncIntegrityRules.identityRecoveryRequired(r)){putSetting("sync.identity.lastError",r.error?:"AUTH_REQUIRED");onAuthFailure(r.error);return true}
                return SyncIntegrityRules.permanent(r)
            }
            val root=runCatching{JSONObject(r.responseBody.orEmpty())}.getOrNull()
            if(root==null){recordPullError(pullErrorKey,"SYNC_PULL_PROTOCOL_INVALID",null);return true}
            val changes=root.optJSONArray("changes")
            val hasMore=root.opt("hasMore") as? Boolean
            val nextValue=root.opt("nextCursor")
            val next=if(nextValue is Number){val d=nextValue.toDouble();val l=nextValue.toLong();if(d.isFinite()&&d==l.toDouble())l else null}else null
            if(changes==null||hasMore==null||next==null){recordPullError(pullErrorKey,"SYNC_PULL_PROTOCOL_INVALID",r.responseBody);return true}
            val batch=(0 until changes.length()).map{changes.optJSONObject(it)}
            val batchError=SyncIntegrityRules.validatePullBatch(cursor,next,hasMore,batch,workspace)
            if(batchError!=null){recordPullError(pullErrorKey,batchError,r.responseBody);return true}
            try{
                db.withTransaction{
                    for(c in batch){
                        if(c==null||c.optString("workspace")!=workspace||!applyChange(c,workspace))throw PullApplyException(c?.optString("entityType")?.takeIf{it.isNotBlank()},c?.optString("entityId")?.takeIf{it.isNotBlank()},"SYNC_PULL_APPLY_FAILED")
                    }
                    daoSetting(cursorKey,next.toString())
                }
            }catch(e:PullApplyException){
                if(e.entityType!=null&&e.entityId!=null)db.coreDao().markEntitySyncConflict(e.entityType,e.entityId,e.code)
                recordPullError(pullErrorKey,e.code,null)
                return false
            }
            cursor=SyncIntegrityRules.cursorAfterBatch(cursor,next,true)
            if(!hasMore){putSetting(pullErrorKey,"");return true}
        }
        recordPullError(pullErrorKey,"SYNC_PULL_PAGE_LIMIT_REACHED",null)
        return false
    }
    private suspend fun applyChange(change:JSONObject,workspace:String):Boolean=when(change.optString("entityType")){
        "Teacher"->applyTeacherChange(change,workspace)
        "AttendancePolicy"->applyAttendancePolicyChange(change,workspace)
        "University"->applyUniversityChange(change)
        "AcademicYear"->applyAcademicYearChange(change)
        "Faculty"->applyFacultyChange(change)
        "Department"->applyDepartmentChange(change)
        "Level"->applyLevelChange(change)
        "Semester"->applySemesterChange(change)
        "Batch"->applyBatchChange(change)
        "Section"->applySectionChange(change)
        "Group"->applyGroupChange(change)
        "Student"->applyStudentChange(change)
        "Subject"->applySubjectChange(change)
        "Lecture"->applyLectureChange(change)
        "AttendanceRecord"->applyAttendanceRecordChange(change)
        "AttendanceAppeal"->applyAppealChange(change,workspace)
        else->false
    }
    private suspend fun applyTeacherChange(change:JSONObject,workspace:String):Boolean{
        if(change.optBoolean("tombstone",false))return false
        val payload=change.optJSONObject("payload")?:return false
        val id=payload.optString("id");val remoteVersion=payload.optLong("version",0);if(id.isBlank()||remoteVersion<=0)return false
        val dao=db.coreDao();val local=dao.getTeacherById(id);val unresolved=dao.countUnresolvedSyncForEntity("Teacher",id)>0
        when(SyncIntegrityRules.referenceDecision(local?.version,remoteVersion,unresolved,local?.let{SyncIntegrityRules.sameTeacherPayload(it,payload)}==true)){
            RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true
            RemoteEntityDecision.CONFLICT->throw PullApplyException("Teacher",id,"SYNC_CONFLICT_LOCAL_UNSYNCED")
            RemoteEntityDecision.APPLY->Unit
        }
        val remote=TeacherEntity(id,payload.optString("fullName"),payload.optString("normalizedName"),payload.optNullableString("phone"),payload.optNullableString("whatsapp"),payload.optNullableString("email"),payload.optNullableString("preferredNotificationChannel"),payload.optBoolean("notificationsEnabled"),payload.optLong("createdAt"),payload.optLong("updatedAt"),payload.optNullableLong("archivedAt"),remoteVersion)
        if(local==null)dao.insertTeacher(remote) else dao.updateTeacher(remote)
        daoSetting("sync.server.$workspace.Teacher.$id",change.optLong("serverVersion").toString())
        return true
    }
    private suspend fun applyAttendancePolicyChange(change:JSONObject,workspace:String):Boolean{
        if(change.optBoolean("tombstone",false))return false
        val payload=change.optJSONObject("payload")?:return false
        val id=payload.optString("id");val remoteVersion=payload.optLong("version",0);if(id.isBlank()||remoteVersion<=0)return false
        val dao=db.coreDao();val local=dao.getAttendancePolicyById(id);val unresolved=dao.countUnresolvedSyncForEntity("AttendancePolicy",id)>0
        when(SyncIntegrityRules.referenceDecision(local?.version,remoteVersion,unresolved,local?.let{SyncIntegrityRules.sameAttendancePolicyPayload(it,payload)}==true)){
            RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true
            RemoteEntityDecision.CONFLICT->throw PullApplyException("AttendancePolicy",id,"SYNC_CONFLICT_LOCAL_UNSYNCED")
            RemoteEntityDecision.APPLY->Unit
        }
        val remote=AttendancePolicyEntity(id,payload.optString("name"),"GLOBAL",null,payload.optDouble("fullAttendanceThreshold"),payload.optDouble("partialAttendanceThreshold"),payload.optInt("lateAfterMinutes"),payload.optInt("earlyLeaveThresholdMinutes"),payload.optDouble("absenceThreshold"),payload.optLong("temporaryMissingGraceSeconds"),payload.optLong("minimumPresenceVerificationSeconds"),payload.optDouble("confidenceThreshold"),payload.optLong("createdAt"),payload.optLong("updatedAt"),remoteVersion)
        dao.upsertAttendancePolicy(remote)
        daoSetting("sync.server.$workspace.AttendancePolicy.$id",change.optLong("serverVersion").toString())
        return true
    }
    private suspend fun applyUniversityChange(change:JSONObject):Boolean{
        val payload=change.optJSONObject("payload")?:return false;val id=payload.optString("id");val remoteVersion=payload.optLong("version",0);val dao=db.coreDao();val local=dao.getUniversityById(id);val meta=dao.getSyncEntityMetadata("University",id)
        if(local!=null&&meta==null)throw PullApplyException("University",id,"SYNC_LOCAL_METADATA_MISSING")
        val unresolved=dao.countUnresolvedSyncForEntity("University",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(payload)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("University",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=UniversityEntity(id,payload.optString("name"),payload.optNullableLong("archivedAt"));if(local==null)dao.insertUniversity(remote) else dao.updateUniversity(remote)
        AcademicHierarchySyncOutbox(db,encrypt).acceptRemote("University",id,remoteVersion,payload.optLong("updatedAt"),payload,change.optLong("serverVersion"));return true
    }
    private suspend fun applyAcademicYearChange(change:JSONObject):Boolean{
        val payload=change.optJSONObject("payload")?:return false;val id=payload.optString("id");val remoteVersion=payload.optLong("version",0);val dao=db.coreDao();val local=dao.getAcademicYearById(id);val meta=dao.getSyncEntityMetadata("AcademicYear",id)
        if(local!=null&&meta==null)throw PullApplyException("AcademicYear",id,"SYNC_LOCAL_METADATA_MISSING")
        val unresolved=dao.countUnresolvedSyncForEntity("AcademicYear",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(payload)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("AcademicYear",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=AcademicYearEntity(id,payload.optString("name"),payload.optString("startDate"),payload.optString("endDate"),payload.optBoolean("isActive"));if(local==null)dao.insertAcademicYear(remote) else dao.updateAcademicYear(remote)
        AcademicHierarchySyncOutbox(db,encrypt).acceptRemote("AcademicYear",id,remoteVersion,payload.optLong("updatedAt"),payload,change.optLong("serverVersion"));return true
    }
    private suspend fun applyFacultyChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;val parent=p.optString("universityId");if(db.coreDao().getUniversityById(parent)==null)return false;return applyHierarchy(change,"Faculty",db.coreDao().getFacultyById(p.optString("id"))!=null,{FacultyEntity(p.optString("id"),parent,p.optString("name"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getFacultyById(v.id)==null)d.insertFaculty(v)else d.updateFaculty(v)})}
    private suspend fun applyDepartmentChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;val parent=p.optString("facultyId");if(db.coreDao().getFacultyById(parent)==null)return false;return applyHierarchy(change,"Department",db.coreDao().getDepartmentById(p.optString("id"))!=null,{DepartmentEntity(p.optString("id"),parent,p.optString("name"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getDepartmentById(v.id)==null)d.insertDepartment(v)else d.updateDepartment(v)})}
    private suspend fun applyLevelChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;val parent=p.optString("departmentId");if(db.coreDao().getDepartmentById(parent)==null)return false;return applyHierarchy(change,"Level",db.coreDao().getLevelById(p.optString("id"))!=null,{LevelEntity(p.optString("id"),parent,p.optString("name"),p.optInt("orderIndex"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getLevelById(v.id)==null)d.insertLevel(v)else d.updateLevel(v)})}
    private suspend fun applySemesterChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;val year=db.coreDao().getAcademicYearById(p.optString("academicYearId"))?:return false;if(p.optString("startDate")<year.startDate||p.optString("endDate")>year.endDate)return false;return applyHierarchy(change,"Semester",db.coreDao().getSemesterById(p.optString("id"))!=null,{SemesterEntity(p.optString("id"),p.optString("name"),p.optString("academicYearId"),p.optString("startDate"),p.optString("endDate"),SemesterStatus.valueOf(p.optString("status")),p.optLong("createdAt"),p.optLong("updatedAt"))},{v->val d=db.coreDao();if(d.getSemesterById(v.id)==null)d.insertSemester(v)else d.updateSemester(v)})}
    private suspend fun applyBatchChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;if(db.coreDao().getLevelById(p.optString("levelId"))==null||db.coreDao().getAcademicYearById(p.optString("academicYearId"))==null)return false;return applyHierarchy(change,"Batch",db.coreDao().getBatchById(p.optString("id"))!=null,{BatchEntity(p.optString("id"),p.optString("levelId"),p.optString("name"),p.optString("academicYearId"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getBatchById(v.id)==null)d.insertBatch(v)else d.updateBatch(v)})}
    private suspend fun applySectionChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;if(db.coreDao().getBatchById(p.optString("batchId"))==null)return false;return applyHierarchy(change,"Section",db.coreDao().getSectionById(p.optString("id"))!=null,{SectionEntity(p.optString("id"),p.optString("batchId"),p.optString("name"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getSectionById(v.id)==null)d.insertSection(v)else d.updateSection(v)})}
    private suspend fun applyGroupChange(change:JSONObject):Boolean{val p=change.optJSONObject("payload")?:return false;if(db.coreDao().getSectionById(p.optString("sectionId"))==null)return false;return applyHierarchy(change,"Group",db.coreDao().getGroupById(p.optString("id"))!=null,{GroupEntity(p.optString("id"),p.optString("sectionId"),p.optString("name"),p.optNullableLong("archivedAt"))},{v->val d=db.coreDao();if(d.getGroupById(v.id)==null)d.insertGroup(v)else d.updateGroup(v)})}
    private suspend fun <T> applyHierarchy(change:JSONObject,type:String,localExists:Boolean,build:()->T,write:suspend (T)->Unit):Boolean{
        val p=change.optJSONObject("payload")?:return false;val id=p.optString("id");val remoteVersion=p.optLong("version",0);val dao=db.coreDao();val meta=dao.getSyncEntityMetadata(type,id)
        if(localExists&&meta==null)throw PullApplyException(type,id,"SYNC_LOCAL_METADATA_MISSING")
        val unresolved=dao.countUnresolvedSyncForEntity(type,id)>0;val same=localExists&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(p)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException(type,id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        write(build());AcademicHierarchySyncOutbox(db,encrypt).acceptRemote(type,id,remoteVersion,p.optLong("updatedAt"),p,change.optLong("serverVersion"));return true
    }
    private suspend fun applyStudentChange(change:JSONObject):Boolean{
        val p=change.optJSONObject("payload")?:return false
        val id=p.optString("id");val remoteVersion=p.optLong("version",0);val dao=db.coreDao();val local=dao.getStudentById(id);val meta=dao.getSyncEntityMetadata("Student",id)
        if(local!=null&&meta==null)throw PullApplyException("Student",id,"SYNC_LOCAL_METADATA_MISSING")
        val levelId=p.optNullableString("levelId"),batchId=p.optNullableString("batchId"),sectionId=p.optNullableString("sectionId"),groupId=p.optNullableString("groupId")
        val level=levelId?.let{dao.getLevelById(it)};val batch=batchId?.let{dao.getBatchById(it)};val section=sectionId?.let{dao.getSectionById(it)};val group=groupId?.let{dao.getGroupById(it)}
        if(levelId!=null&&level==null||batchId!=null&&batch==null||sectionId!=null&&section==null||groupId!=null&&group==null)throw PullApplyException("Student",id,"SYNC_PARENT_MISSING")
        if(batch!=null&&levelId!=null&&batch.levelId!=levelId||section!=null&&batchId!=null&&section.batchId!=batchId||group!=null&&sectionId!=null&&group.sectionId!=sectionId)throw PullApplyException("Student",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val duplicate=p.optNullableString("universityNumber")?.let{number->dao.getStudentsOnce().firstOrNull{it.id!=id&&it.universityNumber==number}}
        if(duplicate!=null)throw PullApplyException("Student",id,"SYNC_UNIQUE_CONFLICT")
        val unresolved=dao.countUnresolvedSyncForEntity("Student",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(p)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("Student",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=StudentEntity(id,p.optNullableString("universityNumber"),p.optString("fullName"),p.optString("normalizedName"),p.optNullableString("phoneNumber"),p.optNullableString("whatsappNumber"),levelId,batchId,sectionId,groupId,StudentStatus.valueOf(p.optString("status")),local?.registeredDeviceId,p.optLong("createdAt"),maxOf(local?.updatedAt?:0L,p.optLong("updatedAt")),p.optNullableLong("archivedAt"),local?.version?:1L)
        if(local==null)dao.insertStudent(remote) else dao.updateStudent(remote)
        AcademicGraphSyncOutbox(db,encrypt).acceptRemote("Student",id,remoteVersion,p.optLong("updatedAt"),p,change.optLong("serverVersion"));return true
    }
    private suspend fun applySubjectChange(change:JSONObject):Boolean{
        val p=change.optJSONObject("payload")?:return false
        val id=p.optString("id");val remoteVersion=p.optLong("version",0);val dao=db.coreDao();val local=dao.getSubjectById(id);val meta=dao.getSyncEntityMetadata("Subject",id)
        if(local!=null&&meta==null)throw PullApplyException("Subject",id,"SYNC_LOCAL_METADATA_MISSING")
        val level=dao.getLevelById(p.optString("levelId"))?:throw PullApplyException("Subject",id,"SYNC_PARENT_MISSING")
        val semester=dao.getSemesterById(p.optString("semesterId"))?:throw PullApplyException("Subject",id,"SYNC_PARENT_MISSING")
        val group=dao.getGroupById(p.optString("groupId"))?:throw PullApplyException("Subject",id,"SYNC_PARENT_MISSING")
        val section=dao.getSectionById(group.sectionId)?:throw PullApplyException("Subject",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val batch=dao.getBatchById(section.batchId)?:throw PullApplyException("Subject",id,"SYNC_PARENT_CHAIN_MISMATCH")
        if(batch.levelId!=level.id)throw PullApplyException("Subject",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val teacherId=p.optNullableString("teacherId");if(teacherId!=null&&dao.getTeacherById(teacherId)==null)throw PullApplyException("Subject",id,"SYNC_PARENT_MISSING")
        val policyId=p.optNullableString("attendancePolicyId");if(policyId!=null){val policy=dao.getAttendancePolicyById(policyId)?:throw PullApplyException("Subject",id,"SYNC_PARENT_MISSING");if(policy.scopeType!="GLOBAL"||policy.scopeId!=null)throw PullApplyException("Subject",id,"SYNC_POLICY_SCOPE_UNSUPPORTED")}
        val unresolved=dao.countUnresolvedSyncForEntity("Subject",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(p)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("Subject",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=SubjectEntity(id,p.optString("code"),p.optString("name"),teacherId,level.id,semester.id,group.id,policyId,p.optString("status"),p.optNullableLong("archivedAt"),local?.version?:1L)
        if(local==null)dao.insertSubject(remote) else dao.updateSubject(remote)
        dao.deleteTeacherSubjectsForSubject(id);if(teacherId!=null)dao.insertTeacherSubject(TeacherSubjectEntity(teacherId,id))
        AcademicGraphSyncOutbox(db,encrypt).acceptRemote("Subject",id,remoteVersion,p.optLong("updatedAt"),p,change.optLong("serverVersion"));return true
    }
    private suspend fun applyLectureChange(change:JSONObject):Boolean{
        val p=change.optJSONObject("payload")?:return false
        val id=p.optString("id");val remoteVersion=p.optLong("version",0);val dao=db.coreDao();val local=dao.getLectureById(id);val meta=dao.getSyncEntityMetadata("Lecture",id)
        if(local!=null&&meta==null)throw PullApplyException("Lecture",id,"SYNC_LOCAL_METADATA_MISSING")
        val subject=dao.getSubjectById(p.optString("subjectId"))?:throw PullApplyException("Lecture",id,"SYNC_PARENT_MISSING")
        if(dao.getTeacherById(p.optString("teacherId"))==null||dao.getSemesterById(p.optString("semesterId"))==null||dao.getGroupById(p.optString("groupId"))==null)throw PullApplyException("Lecture",id,"SYNC_PARENT_MISSING")
        if(subject.semesterId!=p.optString("semesterId")||subject.groupId!=p.optString("groupId"))throw PullApplyException("Lecture",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val unresolved=dao.countUnresolvedSyncForEntity("Lecture",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(p)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("Lecture",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=LectureEntity(id,subject.id,p.optString("teacherId"),p.optString("semesterId"),p.optString("groupId"),p.optLong("scheduledStart"),p.optLong("scheduledEnd"),p.optNullableLong("actualStart"),p.optNullableLong("actualEnd"),p.optNullableString("room"),LectureStatus.valueOf(p.optString("status")),p.optString("attendancePolicySnapshotJson"),p.optLong("createdAt"),maxOf(local?.updatedAt?:0L,p.optLong("updatedAt")),local?.version?:1L)
        if(local==null)dao.insertLecture(remote) else dao.updateLecture(remote)
        AcademicGraphSyncOutbox(db,encrypt).acceptRemote("Lecture",id,remoteVersion,p.optLong("updatedAt"),p,change.optLong("serverVersion"));return true
    }
    private suspend fun applyAttendanceRecordChange(change:JSONObject):Boolean{
        val p=change.optJSONObject("payload")?:return false
        val id=p.optString("id");val remoteVersion=p.optLong("version",0);val dao=db.coreDao();val local=dao.getAttendanceRecord(id);val meta=dao.getSyncEntityMetadata("AttendanceRecord",id)
        if(local!=null&&meta==null)throw PullApplyException("AttendanceRecord",id,"SYNC_LOCAL_METADATA_MISSING")
        val student=dao.getStudentById(p.optString("studentId"))?:throw PullApplyException("AttendanceRecord",id,"SYNC_PARENT_MISSING")
        val lecture=dao.getLectureById(p.optString("lectureId"))?:throw PullApplyException("AttendanceRecord",id,"SYNC_PARENT_MISSING")
        if(student.groupId!=null&&student.groupId!=lecture.groupId)throw PullApplyException("AttendanceRecord",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val pair=dao.getAttendanceRecord(lecture.id,student.id);if(pair!=null&&pair.id!=id)throw PullApplyException("AttendanceRecord",id,"SYNC_UNIQUE_CONFLICT")
        val unresolved=dao.countUnresolvedSyncForEntity("AttendanceRecord",id)>0;val same=local!=null&&meta?.payloadFingerprint==HierarchySyncCodec.fingerprintPayload(p)
        when(SyncIntegrityRules.referenceDecision(meta?.localVersion,remoteVersion,unresolved,same)){RemoteEntityDecision.NO_OP,RemoteEntityDecision.KEEP_LOCAL->return true;RemoteEntityDecision.CONFLICT->throw PullApplyException("AttendanceRecord",id,"SYNC_CONFLICT_LOCAL_UNSYNCED");RemoteEntityDecision.APPLY->Unit}
        val remote=AttendanceRecordEntity(id,lecture.id,student.id,p.optNullableLong("firstSeenAt"),p.optNullableLong("lastSeenAt"),p.optLong("verifiedPresenceSeconds"),p.optLong("lectureDurationSeconds"),p.optDouble("attendancePercentage"),p.optInt("lateMinutes"),p.optInt("earlyLeaveMinutes"),p.optDouble("confidenceScore"),FinalAttendanceStatus.valueOf(p.optString("finalStatus")),ApprovalStatus.valueOf(p.optString("approvalStatus")),PresenceSource.valueOf(p.optString("source")),p.optNullableString("notes"),p.optLong("createdAt"),maxOf(local?.updatedAt?:0L,p.optLong("updatedAt")),local?.version?:1L)
        dao.upsertRecord(remote)
        AcademicGraphSyncOutbox(db,encrypt).acceptRemote("AttendanceRecord",id,remoteVersion,p.optLong("updatedAt"),p,change.optLong("serverVersion"));return true
    }
    private suspend fun applyAppealChange(change:JSONObject,workspace:String):Boolean{
        val dao=db.coreDao()
        if(change.optString("entityType")!="AttendanceAppeal"||change.optBoolean("tombstone",false))return false
        val payload=change.optJSONObject("payload")?:return false
        val id=payload.optString("id");val remoteVersion=payload.optLong("version",0);if(id.isBlank()||remoteVersion<=0)return false
        val local=dao.getAppeal(id)
        val unresolved=dao.countUnresolvedSyncForEntity("AttendanceAppeal",id)>0
        val decision=SyncIntegrityRules.remoteDecision(local?.version,remoteVersion,unresolved,local?.let{SyncIntegrityRules.sameAppealPayload(it,payload)}==true,local?.status?.let{it!=AppealStatus.PENDING}==true,payload.optString("status")=="PENDING")
        when(decision){
            RemoteAppealDecision.NO_OP,RemoteAppealDecision.KEEP_LOCAL->return true
            RemoteAppealDecision.CONFLICT->throw PullApplyException("AttendanceAppeal",id,"SYNC_CONFLICT_LOCAL_UNSYNCED")
            RemoteAppealDecision.APPLY->Unit
        }
        val studentId=payload.optString("studentId"),recordId=payload.optString("attendanceRecordId"),lectureId=payload.optString("lectureId"),subjectId=payload.optString("subjectId")
        val student=dao.getStudentById(studentId)?:return false;val record=dao.getAttendanceRecord(recordId)?:return false;val lecture=dao.getLectureById(lectureId)?:return false;val subject=dao.getSubjectById(subjectId)?:return false
        if(record.studentId!=student.id||record.lectureId!=lecture.id||lecture.subjectId!=subject.id)throw PullApplyException("AttendanceAppeal",id,"SYNC_PARENT_CHAIN_MISMATCH")
        val remote=AttendanceAppealEntity(id,studentId,recordId,lectureId,subjectId,payload.optString("reasonType","OTHER"),payload.optString("description"),local?.attachmentLocalUri,payload.optNullableString("attachmentRemoteUrl"),runCatching{AppealStatus.valueOf(payload.optString("status"))}.getOrNull()?:return false,payload.optLong("submittedAt"),payload.optLong("updatedAt"),payload.optNullableString("reviewedBy"),payload.optNullableLong("reviewedAt"),payload.optNullableString("decisionNote"),AppealSyncStatus.SYNCED,remoteVersion)
        if(local==null)dao.insertAppeal(remote) else dao.updateAppeal(remote)
        daoSetting("sync.server.$workspace.AttendanceAppeal.$id",change.optLong("serverVersion").toString())
        return true
    }
    private suspend fun parentConfirmed(workspace:String,entityType:String,entityId:String):Boolean{
        if(entityId.isBlank())return false
        val dao=db.coreDao()
        if(dao.getSyncEntityMetadata(entityType,entityId)?.lastSyncedServerVersion!=null)return true
        if(entityType in setOf("Teacher","AttendancePolicy")){
            val marker=getSetting("sync.server.$workspace.$entityType.$entityId")?.toLongOrNull()?.let{it>0L}==true
            return marker||dao.countSentSyncForEntity(entityType,entityId)>0
        }
        return false
    }
    private suspend fun recordPullError(key:String,code:String,responseBody:String?){
        val root=responseBody?.let{runCatching{JSONObject(it)}.getOrNull()}
        val sequence=root?.opt("sequence")?.let{v->if(v is Number)v.toLong().toString() else null}
        val entityId=(root?.opt("entityId") as? String)?.takeIf{it.isNotBlank()}
        putSetting(key,listOfNotNull(code,sequence?.let{"sequence=$it"},entityId?.let{"entityId=$it"}).joinToString("|").take(500))
    }
    private class PullApplyException(val entityType:String?=null,val entityId:String?=null,val code:String="SYNC_PULL_APPLY_FAILED"):RuntimeException(code)
    private fun JSONObject.optNullableString(key:String)=if(isNull(key)||!has(key))null else optString(key).takeIf{it.isNotBlank()}
    private fun JSONObject.optNullableLong(key:String)=if(isNull(key)||!has(key))null else optLong(key)
    private suspend fun getSetting(key:String)=db.coreDao().getSetting(key)?.valueCiphertext?.let{runCatching{decrypt(it)}.getOrNull()}
    private suspend fun putSetting(key:String,value:String)=daoSetting(key,value)
    private suspend fun daoSetting(key:String,value:String){db.coreDao().upsertSetting(AppSettingEntity(key,encrypt(value),System.currentTimeMillis()))}
}

class NotificationProcessor(private val db:HammamDatabase,private val backend:BackendClient,private val decrypt:(String)->String){
    suspend fun process():Boolean{
        var all=true
        val dao=db.coreDao()
        for(candidate in dao.pendingNotifications(System.currentTimeMillis())){
            val claimAt=System.currentTimeMillis()
            if(dao.claimNotification(candidate.id,claimAt)==0)continue
            val job=candidate.copy(status=QueueStatus.PROCESSING,scheduledAt=claimAt,version=candidate.version+1)
            val channel=job.channel.uppercase()
            if(!NotificationDeliveryRules.externalChannel(channel)){
                dao.updateNotification(job.copy(status=QueueStatus.CANCELLED,error="UNSUPPORTED_CHANNEL"))
                audit(job,"NOTIFICATION_PERMANENT_FAILURE","UNSUPPORTED_CHANNEL")
                continue
            }
            if(dao.isFeatureEnabled(channel)!=true){
                dao.updateNotification(job.copy(status=QueueStatus.CANCELLED,error="CHANNEL_DISABLED"))
                audit(job,"NOTIFICATION_CHANNEL_DISABLED","CHANNEL_DISABLED")
                continue
            }
            val recipient=resolveRecipient(job.recipientType,job.recipientId,channel)
            if(recipient==null){
                dao.updateNotification(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="RECIPIENT_NOT_FOUND"))
                audit(job,"NOTIFICATION_PERMANENT_FAILURE","RECIPIENT_NOT_FOUND")
                continue
            }
            val payload=decryptOrFail(job.payloadCiphertext,decrypt).getOrNull()
            if(payload==null){
                dao.updateNotification(job.copy(status=QueueStatus.NEEDS_MANUAL_REVIEW,error="LOCAL_DECRYPT_FAILED"));audit(job,"NOTIFICATION_PERMANENT_FAILURE","LOCAL_DECRYPT_FAILED");continue
            }
            val path=if(channel=="WHATSAPP")"/api/v1/notifications/whatsapp" else "/api/v1/notifications/email"
            val envelope=deliveryEnvelope(channel,recipient,job.template,payload)
            val r=backend.post(path,envelope,job.deduplicationKey)
            val now=System.currentTimeMillis();val attempts=job.retryCount+if(r.ok)0 else 1
            val status=when{r.ok->QueueStatus.SENT;NotificationDeliveryRules.permanent(r)||attempts>=5->QueueStatus.NEEDS_MANUAL_REVIEW;else->QueueStatus.FAILED}
            dao.updateNotification(job.copy(status=status,sentAt=if(r.ok)now else null,retryCount=attempts,error=r.error))
            audit(job,if(r.ok)"NOTIFICATION_SEND_ACCEPTED" else if(status==QueueStatus.FAILED)"NOTIFICATION_SEND_RETRYABLE_FAILURE" else "NOTIFICATION_PERMANENT_FAILURE",r.error)
            if(!r.ok&&status==QueueStatus.FAILED)all=false
        }
        return all
    }
    private suspend fun resolveRecipient(type:String,id:String,channel:String):String?=when(type.uppercase()){
        "STUDENT"->{
            val s=db.coreDao().getStudentById(id)?:return null
            if(s.archivedAt!=null || s.status.name!="ACTIVE")return null
            if(channel=="WHATSAPP")NotificationDeliveryRules.normalizePhone(s.whatsappNumber?:s.phoneNumber) else null
        }
        "TEACHER"->{
            val t=db.coreDao().getTeacherById(id)?:return null
            if(t.archivedAt!=null)return null
            if(channel=="WHATSAPP")NotificationDeliveryRules.normalizePhone(t.whatsapp?:t.phone) else NotificationDeliveryRules.validEmail(t.email)
        }
        else->null
    }
    private fun deliveryEnvelope(channel:String,recipient:String,template:String,payload:String):String{
        fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"")
        return """{"channel":"${esc(channel)}","recipient":"${esc(recipient)}","template":"${esc(template)}","payload":$payload}"""
    }
    private suspend fun audit(job:com.hammam.attendai.data.local.entity.NotificationEntity,action:String,error:String?){
        db.coreDao().insertAudit(com.hammam.attendai.data.local.entity.AuditLogEntity(java.util.UUID.randomUUID().toString(),null,action,"Notification",job.id,null,"{\"channel\":\"${job.channel}\",\"recipientType\":\"${job.recipientType}\"}",error,System.currentTimeMillis()))
    }
}
