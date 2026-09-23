package com.hammam.attendai.sync

import com.hammam.attendai.data.local.entity.AttendanceAppealEntity
import com.hammam.attendai.data.local.entity.AttendancePolicyEntity
import com.hammam.attendai.data.local.entity.TeacherEntity
import com.hammam.attendai.domain.model.QueueStatus
import org.json.JSONObject

internal enum class RemoteAppealDecision { APPLY, NO_OP, KEEP_LOCAL, CONFLICT }
internal enum class RemoteEntityDecision { APPLY, NO_OP, KEEP_LOCAL, CONFLICT }
internal data class SyncPayloadMetadata(val version:Long,val updatedAt:Long)

internal object SyncIntegrityRules {
    private val supportedEntities=setOf("Teacher","AttendancePolicy","University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord","AttendanceAppeal")
    private val supportedOperations=setOf("UPSERT")
    private val appealStatuses=setOf("PENDING","ACCEPTED","REJECTED","CANCELLED")
    private val workspacePattern=Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")
    private val permanentErrors=setOf(
        "SYNC_PERSISTENCE_NOT_IMPLEMENTED","SYNC_PERSISTENCE_NOT_CONFIRMED","CLOUD_SYNC_DISABLED",
        "UNSUPPORTED_SYNC_ENTITY","INVALID_SYNC_OPERATION","INVALID_SYNC_PAYLOAD","SYNC_INVALID_PAYLOAD",
        "SYNC_CURSOR_INVALID","SYNC_IDEMPOTENCY_MISMATCH","SYNC_STORED_MUTATION_INVALID",
        "SYNC_PULL_PROTOCOL_INVALID","SYNC_PULL_MALFORMED_CHANGE","SYNC_PULL_UNSUPPORTED_ENTITY",
        "SYNC_IDENTITY_NOT_CONFIGURED","SYNC_WORKSPACE_FORBIDDEN","SYNC_PULL_WORKSPACE_MISMATCH",
        "SYNC_LOCAL_ACCOUNT_BINDING_MISMATCH","SYNC_LOCAL_WORKSPACE_BINDING_MISMATCH","SYNC_LOCAL_WORKSPACE_MEMBERSHIP_MISMATCH",
        "SYNC_LOCAL_IDENTITY_CORRUPT","SYNC_WORKSPACE_SELECTION_REQUIRED","WORKSPACE_INVALID","WORKSPACE_NOT_FOUND","MEMBERSHIP_REQUIRED",
        "Backend not configured","HTTPS_REQUIRED","UNAUTHORIZED"
    )
    fun supported(entityType:String,operation:String)=entityType in supportedEntities && operation in supportedOperations
    fun hierarchyParentRefs(entityType:String,payload:JSONObject):List<Pair<String,String>> = when(entityType){
        "Faculty"->listOf("University" to payload.optString("universityId"))
        "Department"->listOf("Faculty" to payload.optString("facultyId"))
        "Level"->listOf("Department" to payload.optString("departmentId"))
        "Semester"->listOf("AcademicYear" to payload.optString("academicYearId"))
        "Batch"->listOf("Level" to payload.optString("levelId"),"AcademicYear" to payload.optString("academicYearId"))
        "Section"->listOf("Batch" to payload.optString("batchId"))
        "Group"->listOf("Section" to payload.optString("sectionId"))
        "Student"->listOfNotNull(
            payload.optNullableNonBlank("levelId")?.let{"Level" to it},payload.optNullableNonBlank("batchId")?.let{"Batch" to it},
            payload.optNullableNonBlank("sectionId")?.let{"Section" to it},payload.optNullableNonBlank("groupId")?.let{"Group" to it}
        )
        "Subject"->listOfNotNull("Level" to payload.optString("levelId"),"Semester" to payload.optString("semesterId"),"Group" to payload.optString("groupId"),payload.optNullableNonBlank("teacherId")?.let{"Teacher" to it},payload.optNullableNonBlank("attendancePolicyId")?.let{"AttendancePolicy" to it})
        "Lecture"->listOf("Subject" to payload.optString("subjectId"),"Teacher" to payload.optString("teacherId"),"Semester" to payload.optString("semesterId"),"Group" to payload.optString("groupId"))
        "AttendanceRecord"->listOf("Student" to payload.optString("studentId"),"Lecture" to payload.optString("lectureId"))
        "AttendanceAppeal"->listOf("Student" to payload.optString("studentId"),"AttendanceRecord" to payload.optString("attendanceRecordId"),"Lecture" to payload.optString("lectureId"),"Subject" to payload.optString("subjectId"))
        else->emptyList()
    }.filter{it.second.isNotBlank()}
    fun validWorkspaceId(workspaceId:String?)=workspaceId?.trim()?.let{workspacePattern.matches(it)}==true
    fun syncIdentityConfigured(workspaceId:String?,token:String?)=validWorkspaceId(workspaceId)&&!token.isNullOrBlank()
    fun authenticationRequired(result:BackendResult)=BackendAuthRules.authenticationRequired(result.error)
    fun identityRecoveryRequired(result:BackendResult)=BackendAuthRules.identityRecoveryRequired(result.error)
    fun permanent(result:BackendResult)=!identityRecoveryRequired(result) && (!result.retryable || result.error in permanentErrors)
    fun persistenceConfirmed(result:BackendResult)=result.ok && Regex("\\\"persisted\\\"\\s*:\\s*true",RegexOption.IGNORE_CASE).containsMatchIn(result.responseBody.orEmpty())
    fun serverVersion(result:BackendResult)=Regex("\\\"serverVersion\\\"\\s*:\\s*(\\d+)").find(result.responseBody.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
    fun claimable(status:QueueStatus)=status==QueueStatus.PENDING || status==QueueStatus.FAILED
    fun queueStatusAfterAuthFailure()=QueueStatus.PENDING
    fun pullBlockRequired(code:String?)=code in setOf(
        "SYNC_STORED_MUTATION_INVALID","SYNC_PULL_PROTOCOL_INVALID","SYNC_PULL_MALFORMED_CHANGE",
        "SYNC_PULL_UNSUPPORTED_ENTITY","SYNC_PULL_WORKSPACE_MISMATCH","SYNC_PULL_CURSOR_INVALID",
        "SYNC_PARENT_MISSING","SYNC_PARENT_CHAIN_MISMATCH","SYNC_LOCAL_METADATA_MISSING","SYNC_PULL_APPLY_FAILED"
    )
    fun pullConflictCanAdvance(code:String?)=code in setOf("SYNC_CONFLICT_LOCAL_UNSYNCED","SYNC_UNIQUE_CONFLICT")

    fun remoteDecision(localVersion:Long?,remoteVersion:Long,localUnresolved:Boolean,samePayload:Boolean,localFinal:Boolean,remotePending:Boolean):RemoteAppealDecision=when{
        localVersion==null->RemoteAppealDecision.APPLY
        remoteVersion<localVersion->RemoteAppealDecision.KEEP_LOCAL
        remoteVersion==localVersion && samePayload->RemoteAppealDecision.NO_OP
        remoteVersion==localVersion->RemoteAppealDecision.CONFLICT
        localUnresolved->RemoteAppealDecision.CONFLICT
        localFinal&&remotePending->RemoteAppealDecision.KEEP_LOCAL
        else->RemoteAppealDecision.APPLY
    }
    fun referenceDecision(localVersion:Long?,remoteVersion:Long,localUnresolved:Boolean,samePayload:Boolean):RemoteEntityDecision=when{
        localVersion==null->RemoteEntityDecision.APPLY
        remoteVersion<localVersion->RemoteEntityDecision.KEEP_LOCAL
        remoteVersion==localVersion&&samePayload->RemoteEntityDecision.NO_OP
        remoteVersion==localVersion->RemoteEntityDecision.CONFLICT
        localUnresolved->RemoteEntityDecision.CONFLICT
        else->RemoteEntityDecision.APPLY
    }

    fun sameAppealPayload(local:AttendanceAppealEntity,p:JSONObject)=
        local.studentId==p.optString("studentId") && local.attendanceRecordId==p.optString("attendanceRecordId") &&
        local.lectureId==p.optString("lectureId") && local.subjectId==p.optString("subjectId") &&
        local.reasonType==p.optString("reasonType") && local.description==p.optString("description") &&
        local.status.name==p.optString("status") && local.version==strictLong(p.opt("version")) &&
        local.updatedAt==strictLong(p.opt("updatedAt")) && local.decisionNote==nullableStringValue(p,"decisionNote")

    fun sameTeacherPayload(local:TeacherEntity,p:JSONObject)=
        local.fullName==p.optString("fullName")&&local.normalizedName==p.optString("normalizedName")&&
        local.phone==nullableStringValue(p,"phone")&&local.whatsapp==nullableStringValue(p,"whatsapp")&&local.email==nullableStringValue(p,"email")&&
        local.preferredNotificationChannel==nullableStringValue(p,"preferredNotificationChannel")&&local.notificationsEnabled==(p.opt("notificationsEnabled") as? Boolean)&&
        local.createdAt==strictLong(p.opt("createdAt"))&&local.updatedAt==strictLong(p.opt("updatedAt"))&&local.archivedAt==nullableLongValue(p,"archivedAt")&&local.version==strictLong(p.opt("version"))

    fun sameAttendancePolicyPayload(local:AttendancePolicyEntity,p:JSONObject)=
        local.name==p.optString("name")&&local.scopeType==p.optString("scopeType")&&local.scopeId==nullableStringValue(p,"scopeId")&&
        local.fullAttendanceThreshold==strictDouble(p.opt("fullAttendanceThreshold"))&&local.partialAttendanceThreshold==strictDouble(p.opt("partialAttendanceThreshold"))&&
        local.lateAfterMinutes==strictLong(p.opt("lateAfterMinutes"))?.toInt()&&local.earlyLeaveThresholdMinutes==strictLong(p.opt("earlyLeaveThresholdMinutes"))?.toInt()&&
        local.absenceThreshold==strictDouble(p.opt("absenceThreshold"))&&local.temporaryMissingGraceSeconds==strictLong(p.opt("temporaryMissingGraceSeconds"))&&
        local.minimumPresenceVerificationSeconds==strictLong(p.opt("minimumPresenceVerificationSeconds"))&&local.confidenceThreshold==strictDouble(p.opt("confidenceThreshold"))&&
        local.createdAt==strictLong(p.opt("createdAt"))&&local.updatedAt==strictLong(p.opt("updatedAt"))&&local.version==strictLong(p.opt("version"))

    fun queuedPayloadMetadata(entityType:String,payload:JSONObject,entityId:String):SyncPayloadMetadata?{
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return null
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return null
        val valid=when(entityType){
            "Teacher"->validRemoteTeacherPayload(payload,entityId,version,updatedAt)
            "AttendancePolicy"->validRemoteAttendancePolicyPayload(payload,entityId,version,updatedAt)
            "University"->validRemoteUniversityPayload(payload,entityId,version,updatedAt)
            "AcademicYear"->validRemoteAcademicYearPayload(payload,entityId,version,updatedAt)
            "Faculty"->validRemoteFacultyPayload(payload,entityId,version,updatedAt)
            "Department"->validRemoteDepartmentPayload(payload,entityId,version,updatedAt)
            "Level"->validRemoteLevelPayload(payload,entityId,version,updatedAt)
            "Semester"->validRemoteSemesterPayload(payload,entityId,version,updatedAt)
            "Batch"->validRemoteBatchPayload(payload,entityId,version,updatedAt)
            "Section"->validRemoteSectionPayload(payload,entityId,version,updatedAt)
            "Group"->validRemoteGroupPayload(payload,entityId,version,updatedAt)
            "Student"->validRemoteStudentPayload(payload,entityId,version,updatedAt)
            "Subject"->validRemoteSubjectPayload(payload,entityId,version,updatedAt)
            "Lecture"->validRemoteLecturePayload(payload,entityId,version,updatedAt)
            "AttendanceRecord"->validRemoteAttendanceRecordPayload(payload,entityId,version,updatedAt)
            "AttendanceAppeal"->validRemoteAppealPayload(payload,entityId,version,updatedAt)
            else->false
        }
        return if(valid)SyncPayloadMetadata(version,updatedAt) else null
    }

    fun validatePullBatch(currentCursor:Long,nextCursor:Long,hasMore:Boolean,changes:List<JSONObject?>,expectedWorkspace:String?=null):String?{
        if(currentCursor<0L||nextCursor<currentCursor)return "SYNC_PULL_PROTOCOL_INVALID"
        if(changes.isEmpty())return if(hasMore||nextCursor!=currentCursor)"SYNC_PULL_PROTOCOL_INVALID" else null
        var previous=currentCursor
        for(change in changes){
            if(change==null)return "SYNC_PULL_MALFORMED_CHANGE"
            val sequence=strictLong(change.opt("sequence"))?.takeIf{it>0L}?:return "SYNC_PULL_MALFORMED_CHANGE"
            val serverVersion=strictLong(change.opt("serverVersion"))?.takeIf{it>0L}?:return "SYNC_PULL_MALFORMED_CHANGE"
            val entityVersion=strictLong(change.opt("entityVersion"))?.takeIf{it>0L}?:return "SYNC_PULL_MALFORMED_CHANGE"
            val updatedAt=strictLong(change.opt("updatedAt"))?.takeIf{it>0L}?:return "SYNC_PULL_MALFORMED_CHANGE"
            if(sequence<=currentCursor||sequence<=previous)return "SYNC_PULL_PROTOCOL_INVALID"
            val workspace=change.opt("workspace") as? String ?:return "SYNC_PULL_MALFORMED_CHANGE"
            if(!validWorkspaceId(workspace))return "SYNC_PULL_MALFORMED_CHANGE"
            if(expectedWorkspace!=null&&workspace!=expectedWorkspace)return "SYNC_PULL_WORKSPACE_MISMATCH"
            val entityType=change.opt("entityType") as? String ?:return "SYNC_PULL_MALFORMED_CHANGE"
            val operation=change.opt("operation") as? String ?:return "SYNC_PULL_MALFORMED_CHANGE"
            if(!supported(entityType,operation))return "SYNC_PULL_UNSUPPORTED_ENTITY"
            val entityId=(change.opt("entityId") as? String)?.takeIf{it.isNotBlank()}?:return "SYNC_PULL_MALFORMED_CHANGE"
            if(change.opt("tombstone")!=false)return "SYNC_PULL_MALFORMED_CHANGE"
            val payload=change.optJSONObject("payload")?:return "SYNC_PULL_MALFORMED_CHANGE"
            val valid=when(entityType){
                "Teacher"->validRemoteTeacherPayload(payload,entityId,entityVersion,updatedAt)
                "AttendancePolicy"->validRemoteAttendancePolicyPayload(payload,entityId,entityVersion,updatedAt)
                "University"->validRemoteUniversityPayload(payload,entityId,entityVersion,updatedAt)
                "AcademicYear"->validRemoteAcademicYearPayload(payload,entityId,entityVersion,updatedAt)
                "Faculty"->validRemoteFacultyPayload(payload,entityId,entityVersion,updatedAt)
                "Department"->validRemoteDepartmentPayload(payload,entityId,entityVersion,updatedAt)
                "Level"->validRemoteLevelPayload(payload,entityId,entityVersion,updatedAt)
                "Semester"->validRemoteSemesterPayload(payload,entityId,entityVersion,updatedAt)
                "Batch"->validRemoteBatchPayload(payload,entityId,entityVersion,updatedAt)
                "Section"->validRemoteSectionPayload(payload,entityId,entityVersion,updatedAt)
                "Group"->validRemoteGroupPayload(payload,entityId,entityVersion,updatedAt)
                "Student"->validRemoteStudentPayload(payload,entityId,entityVersion,updatedAt)
                "Subject"->validRemoteSubjectPayload(payload,entityId,entityVersion,updatedAt)
                "Lecture"->validRemoteLecturePayload(payload,entityId,entityVersion,updatedAt)
                "AttendanceRecord"->validRemoteAttendanceRecordPayload(payload,entityId,entityVersion,updatedAt)
                "AttendanceAppeal"->validRemoteAppealPayload(payload,entityId,entityVersion,updatedAt)
                else->false
            }
            if(!valid||serverVersion<=0L)return "SYNC_PULL_MALFORMED_CHANGE"
            previous=sequence
        }
        if(nextCursor!=previous)return "SYNC_PULL_PROTOCOL_INVALID"
        return null
    }

    fun cursorAfterBatch(currentCursor:Long,nextCursor:Long,batchApplied:Boolean)=if(batchApplied)nextCursor else currentCursor

    internal fun validRemoteTeacherPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","fullName","normalizedName","phone","whatsapp","email","preferredNotificationChannel","notificationsEnabled","createdAt","updatedAt","archivedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("fullName") as? String).isNullOrBlank()||(payload.opt("normalizedName") as? String).isNullOrBlank())return false
        if(!nullableString(payload,"phone")||!nullableString(payload,"whatsapp")||!nullableString(payload,"email")||!nullableString(payload,"preferredNotificationChannel"))return false
        if(payload.opt("notificationsEnabled") !is Boolean)return false
        val createdAt=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        if(!payload.has("archivedAt")||(!payload.isNull("archivedAt")&&(strictLong(payload.opt("archivedAt"))?.takeIf{it>0L}==null)))return false
        return version==entityVersion&&updatedAt==envelopeUpdatedAt&&updatedAt>=createdAt
    }

    internal fun validRemoteAttendancePolicyPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","name","scopeType","scopeId","fullAttendanceThreshold","partialAttendanceThreshold","lateAfterMinutes","earlyLeaveThresholdMinutes","absenceThreshold","temporaryMissingGraceSeconds","minimumPresenceVerificationSeconds","confidenceThreshold","createdAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("name") as? String).isNullOrBlank()||payload.opt("scopeType")!="GLOBAL"||!payload.has("scopeId")||!payload.isNull("scopeId"))return false
        val full=strictDouble(payload.opt("fullAttendanceThreshold"))?.takeIf{it in 0.0..1.0}?:return false
        val partial=strictDouble(payload.opt("partialAttendanceThreshold"))?.takeIf{it in 0.0..1.0}?:return false
        val absence=strictDouble(payload.opt("absenceThreshold"))?.takeIf{it in 0.0..1.0}?:return false
        val confidence=strictDouble(payload.opt("confidenceThreshold"))?.takeIf{it in 0.0..1.0}?:return false
        if(full<partial||partial<absence||confidence !in 0.0..1.0)return false
        val late=strictLong(payload.opt("lateAfterMinutes"))?.takeIf{it>=0L}?:return false
        val early=strictLong(payload.opt("earlyLeaveThresholdMinutes"))?.takeIf{it>=0L}?:return false
        val grace=strictLong(payload.opt("temporaryMissingGraceSeconds"))?.takeIf{it>=0L}?:return false
        val minimum=strictLong(payload.opt("minimumPresenceVerificationSeconds"))?.takeIf{it>=0L}?:return false
        if(late>Int.MAX_VALUE||early>Int.MAX_VALUE||grace<0L||minimum<0L)return false
        val createdAt=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return version==entityVersion&&updatedAt==envelopeUpdatedAt&&updatedAt>=createdAt
    }

    internal fun validRemoteUniversityPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","name","archivedAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId||(payload.opt("name") as? String).isNullOrBlank())return false
        if(!payload.has("archivedAt")||(!payload.isNull("archivedAt")&&(strictLong(payload.opt("archivedAt"))?.takeIf{it>0L}==null)))return false
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updatedAt==envelopeUpdatedAt&&version==entityVersion
    }

    internal fun validRemoteAcademicYearPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","name","startDate","endDate","isActive","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId||(payload.opt("name") as? String).isNullOrBlank()||payload.opt("isActive") !is Boolean)return false
        val start=payload.opt("startDate") as? String ?:return false;val end=payload.opt("endDate") as? String ?:return false
        if(!validIsoDate(start)||!validIsoDate(end)||start>end)return false
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updatedAt==envelopeUpdatedAt&&version==entityVersion
    }

    internal fun validRemoteFacultyPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long)=validHierarchyNamedParent(payload,entityId,entityVersion,envelopeUpdatedAt,"universityId")
    internal fun validRemoteDepartmentPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long)=validHierarchyNamedParent(payload,entityId,entityVersion,envelopeUpdatedAt,"facultyId")
    internal fun validRemoteLevelPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","departmentId","name","orderIndex","archivedAt","updatedAt","version");if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("departmentId") as? String).isNullOrBlank()||(payload.opt("name") as? String).isNullOrBlank())return false
        val order=strictLong(payload.opt("orderIndex"))?.takeIf{it>=0L&&it<=Int.MAX_VALUE}?:return false
        if(order<0L||!nullablePositiveLong(payload,"archivedAt"))return false
        return syncMetaMatches(payload,entityVersion,envelopeUpdatedAt)
    }
    private fun validHierarchyNamedParent(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long,parentKey:String):Boolean{
        val keys=setOf("id",parentKey,"name","archivedAt","updatedAt","version");if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt(parentKey) as? String).isNullOrBlank()||(payload.opt("name") as? String).isNullOrBlank()||!nullablePositiveLong(payload,"archivedAt"))return false
        return syncMetaMatches(payload,entityVersion,envelopeUpdatedAt)
    }
    private fun syncMetaMatches(payload:JSONObject,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{val u=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false;val v=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false;return u==envelopeUpdatedAt&&v==entityVersion}
    private fun nullablePositiveLong(payload:JSONObject,key:String)=payload.has(key)&&(payload.isNull(key)||(strictLong(payload.opt(key))?.takeIf{it>0L}!=null))

    internal fun validRemoteSemesterPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","name","academicYearId","startDate","endDate","status","createdAt","updatedAt","version");if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("name") as? String).isNullOrBlank()||(payload.opt("academicYearId") as? String).isNullOrBlank())return false
        val start=payload.opt("startDate") as? String ?:return false;val end=payload.opt("endDate") as? String ?:return false;if(!validIsoDate(start)||!validIsoDate(end)||start>end)return false
        if((payload.opt("status") as? String) !in setOf("UPCOMING","ACTIVE","COMPLETED","ARCHIVED"))return false
        val created=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false;val updated=strictLong(payload.opt("updatedAt"))?.takeIf{it>=created}?:return false;val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updated==envelopeUpdatedAt&&version==entityVersion
    }
    internal fun validRemoteBatchPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","levelId","academicYearId","name","archivedAt","updatedAt","version");if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("levelId") as? String).isNullOrBlank()||(payload.opt("academicYearId") as? String).isNullOrBlank()||(payload.opt("name") as? String).isNullOrBlank()||!nullablePositiveLong(payload,"archivedAt"))return false
        return syncMetaMatches(payload,entityVersion,envelopeUpdatedAt)
    }
    internal fun validRemoteSectionPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long)=validHierarchyNamedParent(payload,entityId,entityVersion,envelopeUpdatedAt,"batchId")
    internal fun validRemoteGroupPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long)=validHierarchyNamedParent(payload,entityId,entityVersion,envelopeUpdatedAt,"sectionId")

    internal fun validRemoteStudentPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","universityNumber","fullName","normalizedName","phoneNumber","whatsappNumber","levelId","batchId","sectionId","groupId","status","createdAt","archivedAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if(!nullableNonBlankString(payload,"universityNumber")||(payload.opt("fullName") as? String).isNullOrBlank()||(payload.opt("normalizedName") as? String).isNullOrBlank())return false
        if(!nullableString(payload,"phoneNumber")||!nullableString(payload,"whatsappNumber")||!nullableNonBlankString(payload,"levelId")||!nullableNonBlankString(payload,"batchId")||!nullableNonBlankString(payload,"sectionId")||!nullableNonBlankString(payload,"groupId"))return false
        val refs=listOf("levelId","batchId","sectionId","groupId").map{nullableStringValue(payload,it)};if(!(refs.all{it==null}||refs.all{!it.isNullOrBlank()}))return false
        if((payload.opt("status") as? String) !in setOf("ACTIVE","INACTIVE","GRADUATED","SUSPENDED","ARCHIVED"))return false
        val created=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false;if(!nullablePositiveLong(payload,"archivedAt"))return false
        val updated=strictLong(payload.opt("updatedAt"))?.takeIf{it>=created}?:return false;val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updated==envelopeUpdatedAt&&version==entityVersion
    }
    internal fun validRemoteSubjectPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","code","name","teacherId","levelId","semesterId","groupId","attendancePolicyId","status","archivedAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("code") as? String).isNullOrBlank()||(payload.opt("name") as? String).isNullOrBlank()||(payload.opt("teacherId") as? String).isNullOrBlank()||(payload.opt("levelId") as? String).isNullOrBlank()||(payload.opt("semesterId") as? String).isNullOrBlank()||(payload.opt("groupId") as? String).isNullOrBlank()||(payload.opt("attendancePolicyId") as? String).isNullOrBlank())return false
        val status=payload.opt("status") as? String;if(status !in setOf("ACTIVE","ARCHIVED")||!nullablePositiveLong(payload,"archivedAt"))return false
        if((status=="ARCHIVED")!=(nullableLongValue(payload,"archivedAt")!=null))return false
        return syncMetaMatches(payload,entityVersion,envelopeUpdatedAt)
    }
    internal fun validRemoteLecturePayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","subjectId","teacherId","semesterId","groupId","scheduledStart","scheduledEnd","actualStart","actualEnd","room","status","attendancePolicySnapshotJson","createdAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId)return false
        if((payload.opt("subjectId") as? String).isNullOrBlank()||(payload.opt("teacherId") as? String).isNullOrBlank()||(payload.opt("semesterId") as? String).isNullOrBlank()||(payload.opt("groupId") as? String).isNullOrBlank())return false
        val start=strictLong(payload.opt("scheduledStart"))?.takeIf{it>0L}?:return false;val end=strictLong(payload.opt("scheduledEnd"))?.takeIf{it>start}?:return false
        if(!nullablePositiveLong(payload,"actualStart")||!nullablePositiveLong(payload,"actualEnd")||!nullableString(payload,"room"))return false
        val actualStart=nullableLongValue(payload,"actualStart");val actualEnd=nullableLongValue(payload,"actualEnd");if(actualStart!=null&&actualEnd!=null&&actualEnd<actualStart)return false
        if((payload.opt("status") as? String) !in setOf("SCHEDULED","READY","ACTIVE","COMPLETED","CANCELLED","NEEDS_REVIEW","FROZEN")||payload.opt("attendancePolicySnapshotJson") !is String)return false
        val created=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false;val updated=strictLong(payload.opt("updatedAt"))?.takeIf{it>=created}?:return false;val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updated==envelopeUpdatedAt&&version==entityVersion
    }
    internal fun validRemoteAttendanceRecordPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val keys=setOf("id","lectureId","studentId","firstSeenAt","lastSeenAt","verifiedPresenceSeconds","lectureDurationSeconds","attendancePercentage","lateMinutes","earlyLeaveMinutes","confidenceScore","finalStatus","approvalStatus","source","notes","createdAt","updatedAt","version")
        if(!exactKeys(payload,keys)||payload.opt("id")!=entityId||(payload.opt("lectureId") as? String).isNullOrBlank()||(payload.opt("studentId") as? String).isNullOrBlank())return false
        if(!nullablePositiveLong(payload,"firstSeenAt")||!nullablePositiveLong(payload,"lastSeenAt"))return false;val first=nullableLongValue(payload,"firstSeenAt");val last=nullableLongValue(payload,"lastSeenAt");if(first!=null&&last!=null&&last<first)return false
        val verified=strictLong(payload.opt("verifiedPresenceSeconds"))?:return false;val duration=strictLong(payload.opt("lectureDurationSeconds"))?:return false;if(verified<0L||duration<=0L||verified>duration)return false
        val pct=strictDouble(payload.opt("attendancePercentage"))?:return false;val conf=strictDouble(payload.opt("confidenceScore"))?:return false;if(pct !in 0.0..1.0||conf !in 0.0..1.0)return false
        if((strictLong(payload.opt("lateMinutes"))?:-1L)<0L||(strictLong(payload.opt("earlyLeaveMinutes"))?:-1L)<0L)return false
        if((payload.opt("finalStatus") as? String) !in setOf("PRESENT","LATE","PARTIAL","LEFT_EARLY","ABSENT","EXCUSED","MANUAL_REVIEW")||(payload.opt("approvalStatus") as? String) !in setOf("DRAFT","PENDING","APPROVED","FROZEN")||(payload.opt("source") as? String) !in setOf("BLE","QR","NFC","MANUAL")||!nullableString(payload,"notes"))return false
        val created=strictLong(payload.opt("createdAt"))?.takeIf{it>0L}?:return false;val updated=strictLong(payload.opt("updatedAt"))?.takeIf{it>=created}?:return false;val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        return updated==envelopeUpdatedAt&&version==entityVersion
    }

    private fun validRemoteAppealPayload(payload:JSONObject,entityId:String,entityVersion:Long,envelopeUpdatedAt:Long):Boolean{
        val id=payload.opt("id") as? String ?:return false
        if(id!=entityId)return false
        val version=strictLong(payload.opt("version"))?.takeIf{it>0L}?:return false
        if(version!=entityVersion)return false
        val studentId=(payload.opt("studentId") as? String)?.takeIf{it.isNotBlank()}?:return false
        val attendanceRecordId=(payload.opt("attendanceRecordId") as? String)?.takeIf{it.isNotBlank()}?:return false
        val lectureId=(payload.opt("lectureId") as? String)?.takeIf{it.isNotBlank()}?:return false
        val subjectId=(payload.opt("subjectId") as? String)?.takeIf{it.isNotBlank()}?:return false
        val reasonType=(payload.opt("reasonType") as? String)?.takeIf{it.isNotBlank()}?:return false
        val description=(payload.opt("description") as? String)?.takeIf{it.isNotBlank()}?:return false
        val status=payload.opt("status") as? String ?:return false
        if(status !in appealStatuses)return false
        val submittedAt=strictLong(payload.opt("submittedAt"))?.takeIf{it>0L}?:return false
        val updatedAt=strictLong(payload.opt("updatedAt"))?.takeIf{it>0L}?:return false
        if(updatedAt!=envelopeUpdatedAt||updatedAt<submittedAt)return false
        if(!nullableString(payload,"reviewedBy")||!nullableString(payload,"decisionNote")||!nullableString(payload,"attachmentRemoteUrl"))return false
        if(!payload.has("reviewedAt")||(!payload.isNull("reviewedAt")&&(strictLong(payload.opt("reviewedAt"))?.takeIf{it>0L}==null)))return false
        return true
    }

    private fun validIsoDate(value:String):Boolean=runCatching{java.time.LocalDate.parse(value).toString()==value}.getOrDefault(false)

    private fun exactKeys(obj:JSONObject,expected:Set<String>):Boolean{
        val actual=mutableSetOf<String>();val it=obj.keys();while(it.hasNext())actual+=it.next()
        return actual==expected
    }
    private fun nullableString(obj:JSONObject,key:String)=obj.has(key)&&(obj.isNull(key)||obj.opt(key) is String)
    private fun nullableNonBlankString(obj:JSONObject,key:String)=obj.has(key)&&(obj.isNull(key)||((obj.opt(key) as? String)?.isNotBlank()==true))
    private fun JSONObject.optNullableNonBlank(key:String)=if(!has(key)||isNull(key))null else (opt(key) as? String)?.takeIf{it.isNotBlank()}
    private fun nullableStringValue(obj:JSONObject,key:String)=if(!obj.has(key)||obj.isNull(key))null else obj.opt(key) as? String
    private fun nullableLongValue(obj:JSONObject,key:String)=if(!obj.has(key)||obj.isNull(key))null else strictLong(obj.opt(key))
    private fun strictDouble(value:Any?):Double?=if(value is Number&&value.toDouble().isFinite())value.toDouble() else null
    private fun strictLong(value:Any?):Long?{
        if(value !is Number)return null
        val asDouble=value.toDouble();val asLong=value.toLong()
        return if(asDouble.isFinite()&&asDouble==asLong.toDouble())asLong else null
    }
}
