package com.hammam.attendai.sync

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AppSettingEntity
import org.json.JSONObject

internal data class LocalSyncIdentity(val accountId:String,val workspaceId:String)
internal data class SyncWorkspaceSelection(val workspaceId:String?=null,val error:String?=null)
private data class LocalSyncIdentityRead(val identity:LocalSyncIdentity?=null,val error:String?=null)

internal object LocalSyncIdentityRules {
    const val ACCOUNT_MISMATCH="SYNC_LOCAL_ACCOUNT_BINDING_MISMATCH"
    const val WORKSPACE_MISMATCH="SYNC_LOCAL_WORKSPACE_BINDING_MISMATCH"
    const val MEMBERSHIP_MISMATCH="SYNC_LOCAL_WORKSPACE_MEMBERSHIP_MISMATCH"
    const val SELECTION_REQUIRED="SYNC_WORKSPACE_SELECTION_REQUIRED"
    const val IDENTITY_CORRUPT="SYNC_LOCAL_IDENTITY_CORRUPT"
    const val HISTORY_AMBIGUOUS="SYNC_LOCAL_WORKSPACE_HISTORY_AMBIGUOUS"

    fun mismatch(binding:LocalSyncIdentity?,accountId:String?,workspaceId:String?):String?{
        val account=accountId?.trim().orEmpty()
        val workspace=workspaceId?.trim().orEmpty()
        if(account.isBlank()||!SyncIntegrityRules.validWorkspaceId(workspace))return "SYNC_IDENTITY_NOT_CONFIGURED"
        if(binding==null)return null
        if(binding.accountId!=account)return ACCOUNT_MISMATCH
        if(binding.workspaceId!=workspace)return WORKSPACE_MISMATCH
        return null
    }

    fun historicalWorkspace(history:Set<String>):SyncWorkspaceSelection=when{
        history.size>1->SyncWorkspaceSelection(error=HISTORY_AMBIGUOUS)
        history.size==1->SyncWorkspaceSelection(workspaceId=history.single())
        else->SyncWorkspaceSelection()
    }

    fun selectWorkspace(binding:LocalSyncIdentity?,accountId:String,currentWorkspace:String?,memberships:List<String>):SyncWorkspaceSelection{
        val account=accountId.trim()
        if(account.isBlank())return SyncWorkspaceSelection(error="ACCOUNT_NOT_PROVISIONED")
        val validMemberships=memberships.map{it.trim()}.filter{SyncIntegrityRules.validWorkspaceId(it)}.distinct()
        if(binding!=null){
            if(binding.accountId!=account)return SyncWorkspaceSelection(error=ACCOUNT_MISMATCH)
            if(binding.workspaceId !in validMemberships)return SyncWorkspaceSelection(error=MEMBERSHIP_MISMATCH)
            return SyncWorkspaceSelection(workspaceId=binding.workspaceId)
        }
        val current=currentWorkspace?.trim()?.takeIf{it in validMemberships}
        if(current!=null)return SyncWorkspaceSelection(workspaceId=current)
        return when(validMemberships.size){
            0->SyncWorkspaceSelection(error="MEMBERSHIP_REQUIRED")
            1->SyncWorkspaceSelection(workspaceId=validMemberships.first())
            else->SyncWorkspaceSelection(error=SELECTION_REQUIRED)
        }
    }
}

/**
 * Binds one local Room database to one backend account/workspace identity without changing
 * database v5. The binding lives in the existing encrypted app_settings table.
 *
 * Sync queue and sidecar metadata rows are intentionally not reinterpreted under another
 * identity. A mismatch therefore fails closed before queue claiming or pull application.
 */
class LocalSyncIdentityGuard(
    private val db:HammamDatabase,
    private val encrypt:(String)->String,
    private val decrypt:(String)->String,
){
    companion object { const val SETTING_KEY="sync.local.identity.binding.v1" }

    internal suspend fun provisioningError(requestedWorkspaceId:String?):String?{
        val state=read()
        if(state.error!=null)return state.error
        if(state.identity!=null)return "SYNC_LOCAL_IDENTITY_ALREADY_BOUND"
        val requested=requestedWorkspaceId?.trim().orEmpty()
        if(requested.isNotBlank()&&!SyncIntegrityRules.validWorkspaceId(requested))return "SYNC_WORKSPACE_INVALID"
        val history=LocalSyncIdentityRules.historicalWorkspace(historicalWorkspaces())
        if(history.error!=null)return history.error
        if(history.workspaceId!=null&&requested.isNotBlank()&&requested!=history.workspaceId)return LocalSyncIdentityRules.WORKSPACE_MISMATCH
        return null
    }

    internal suspend fun workspaceChangeError(candidate:String?):String?{
        val normalized=candidate?.trim().orEmpty()
        if(normalized.isBlank())return null
        if(!SyncIntegrityRules.validWorkspaceId(normalized))return "SYNC_WORKSPACE_INVALID"
        val state=read()
        if(state.error!=null)return state.error
        if(state.identity!=null)return state.identity.takeIf{it.workspaceId!=normalized}?.let{LocalSyncIdentityRules.WORKSPACE_MISMATCH}
        val history=LocalSyncIdentityRules.historicalWorkspace(historicalWorkspaces())
        if(history.error!=null)return history.error
        return history.workspaceId?.takeIf{it!=normalized}?.let{LocalSyncIdentityRules.WORKSPACE_MISMATCH}
    }

    internal suspend fun selectWorkspaceForSession(accountId:String,currentWorkspace:String?,memberships:List<String>):SyncWorkspaceSelection{
        val state=read()
        if(state.error!=null)return SyncWorkspaceSelection(error=state.error)
        if(state.identity!=null)return LocalSyncIdentityRules.selectWorkspace(state.identity,accountId,currentWorkspace,memberships)
        val history=LocalSyncIdentityRules.historicalWorkspace(historicalWorkspaces())
        if(history.error!=null)return history
        val historical=history.workspaceId
        val effectiveCurrent=historical?:currentWorkspace
        val selected=LocalSyncIdentityRules.selectWorkspace(null,accountId,effectiveCurrent,memberships)
        if(historical!=null&&selected.workspaceId!=historical)return SyncWorkspaceSelection(error=LocalSyncIdentityRules.MEMBERSHIP_MISMATCH)
        return selected
    }

    internal suspend fun bindOrValidate(accountId:String?,workspaceId:String?):String?=db.withTransaction{
        val account=accountId?.trim()?.takeIf{it.isNotBlank()}?:return@withTransaction "SYNC_IDENTITY_NOT_CONFIGURED"
        val workspace=workspaceId?.trim()?.takeIf{SyncIntegrityRules.validWorkspaceId(it)}?:return@withTransaction "SYNC_IDENTITY_NOT_CONFIGURED"
        val state=read()
        if(state.error!=null)return@withTransaction state.error
        if(state.identity==null){
            val history=LocalSyncIdentityRules.historicalWorkspace(historicalWorkspaces())
            if(history.error!=null)return@withTransaction history.error
            if(history.workspaceId!=null&&history.workspaceId!=workspace)return@withTransaction LocalSyncIdentityRules.WORKSPACE_MISMATCH
        }
        val mismatch=LocalSyncIdentityRules.mismatch(state.identity,account,workspace)
        if(mismatch!=null)return@withTransaction mismatch
        if(state.identity==null){
            val identity=LocalSyncIdentity(account,workspace)
            db.coreDao().upsertSetting(AppSettingEntity(SETTING_KEY,encrypt(encode(identity)),System.currentTimeMillis()))
        }
        null
    }


    private suspend fun historicalWorkspaces():Set<String>{
        val dao=db.coreDao()
        val cursor=dao.getSettingsByPrefix("sync.pull.cursor.").mapNotNull{row->
            row.key.removePrefix("sync.pull.cursor.").takeIf{SyncIntegrityRules.validWorkspaceId(it)}
        }
        val server=dao.getSettingsByPrefix("sync.server.").mapNotNull{row->
            val rest=row.key.removePrefix("sync.server.")
            val entity=listOf("Teacher","AttendancePolicy","University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord","AttendanceAppeal").firstOrNull{rest.contains(".$it.")}
            entity?.let{rest.substringBefore(".$it.").takeIf{workspace->SyncIntegrityRules.validWorkspaceId(workspace)}}
        }
        return (cursor+server).toSet()
    }

    private suspend fun read():LocalSyncIdentityRead{
        val row=db.coreDao().getSetting(SETTING_KEY)?:return LocalSyncIdentityRead()
        val raw=runCatching{decrypt(row.valueCiphertext)}.getOrNull()?:return LocalSyncIdentityRead(error=LocalSyncIdentityRules.IDENTITY_CORRUPT)
        val root=runCatching{JSONObject(raw)}.getOrNull()?:return LocalSyncIdentityRead(error=LocalSyncIdentityRules.IDENTITY_CORRUPT)
        val account=root.optString("accountId").trim()
        val workspace=root.optString("workspaceId").trim()
        if(account.isBlank()||!SyncIntegrityRules.validWorkspaceId(workspace))return LocalSyncIdentityRead(error=LocalSyncIdentityRules.IDENTITY_CORRUPT)
        return LocalSyncIdentityRead(LocalSyncIdentity(account,workspace))
    }

    private fun encode(identity:LocalSyncIdentity)=JSONObject()
        .put("accountId",identity.accountId)
        .put("workspaceId",identity.workspaceId)
        .toString()
}
