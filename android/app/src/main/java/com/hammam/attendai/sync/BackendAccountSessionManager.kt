package com.hammam.attendai.sync

import com.hammam.attendai.data.settings.AppPreferences
import com.hammam.attendai.security.SecureSecretStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

internal object BackendSecretKeys{
    const val DEPLOYMENT_CREDENTIAL="backend.auth.token"
    const val ACCOUNT_CREDENTIAL="backend.account.credential"
    const val SESSION_TOKEN="backend.session.token"
}

enum class BackendAccountState{PROVISIONING_REQUIRED,UNAUTHENTICATED,AUTHENTICATED,EXPIRED_OR_REVOKED}

data class BackendAccountAction(val ok:Boolean,val state:BackendAccountState,val error:String?=null)

class BackendAccountSessionManager(
    private val preferences:AppPreferences,
    private val secretStore:SecureSecretStore,
    private val backend:BackendClient,
){
    suspend fun refreshState():BackendAccountState{
        val local=localState()
        if(local==BackendAccountState.AUTHENTICATED)return local
        val r=backend.postWithBearer("/api/v1/auth/status","{}","auth-status:${UUID.randomUUID()}",null)
        if(!r.ok)return local
        val provisioning=runCatching{JSONObject(r.responseBody.orEmpty()).optBoolean("provisioningRequired",false)}.getOrDefault(false)
        val state=when{
            provisioning->BackendAccountState.PROVISIONING_REQUIRED
            preferences.backendAccountId.first().isNullOrBlank()->BackendAccountState.UNAUTHENTICATED
            else->local
        }
        if(provisioning){
            secretStore.put(BackendSecretKeys.SESSION_TOKEN,BackendSessionRules.clearedToken())
            preferences.setBackendSession(null,state.name)
        }else preferences.setBackendAuthState(state.name)
        return state
    }

    suspend fun provisionCurrentAccount(displayName:String,requestedWorkspaceId:String?):BackendAccountAction{
        val deploymentCredential=secretStore.get(BackendSecretKeys.DEPLOYMENT_CREDENTIAL)
            ?:return failure("PROVISIONING_CREDENTIAL_REQUIRED",localState())
        val status=backend.postWithBearer("/api/v1/auth/status","{}","auth-status:${UUID.randomUUID()}",null)
        if(!status.ok)return failure(status.error?:"AUTH_STATUS_FAILED",localState())
        val provisioningRequired=runCatching{JSONObject(status.responseBody.orEmpty()).optBoolean("provisioningRequired",false)}.getOrDefault(false)
        val payload=JSONObject().put("displayName",displayName.trim().ifBlank{"Hammam AttendAI Account"})
        requestedWorkspaceId?.trim()?.takeIf{it.isNotBlank()}?.let{payload.put("workspaceId",it)}
        val path=if(provisioningRequired)"/api/v1/auth/bootstrap" else "/api/v1/auth/provision"
        val r=backend.postWithBearer(path,payload.toString(),"auth-provision:${UUID.randomUUID()}",deploymentCredential)
        if(!r.ok)return failure(r.error?:"PROVISIONING_FAILED",if(r.error=="PROVISIONING_REQUIRED")BackendAccountState.PROVISIONING_REQUIRED else localState())
        val root=runCatching{JSONObject(r.responseBody.orEmpty())}.getOrNull()?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val accountId=root.optString("accountId").takeIf{it.isNotBlank()}?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val workspaceId=root.optString("workspaceId").takeIf{SyncIntegrityRules::validWorkspaceId}?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val accountCredential=root.optString("accountCredential").takeIf{it.isNotBlank()}?:return failure("AUTH_PROTOCOL_INVALID",localState())
        secretStore.put(BackendSecretKeys.ACCOUNT_CREDENTIAL,accountCredential)
        secretStore.put(BackendSecretKeys.SESSION_TOKEN,null)
        preferences.setBackendAccountMetadata(accountId,workspaceId,null,BackendAccountState.UNAUTHENTICATED.name)
        return renewSession()
    }

    suspend fun renewSession():BackendAccountAction{
        val accountId=preferences.backendAccountId.first()?.takeIf{it.isNotBlank()}?:return failure("ACCOUNT_NOT_PROVISIONED",BackendAccountState.UNAUTHENTICATED)
        val credential=secretStore.get(BackendSecretKeys.ACCOUNT_CREDENTIAL)?:return failure("ACCOUNT_CREDENTIAL_MISSING",BackendAccountState.PROVISIONING_REQUIRED)
        val r=backend.postWithBearer("/api/v1/auth/session",JSONObject().put("accountId",accountId).toString(),"auth-session:${UUID.randomUUID()}",credential)
        if(!r.ok){
            val state=when{
                r.error=="AUTH_INVALID"->BackendAccountState.PROVISIONING_REQUIRED
                BackendAuthRules.authenticationRequired(r.error)->BackendAccountState.EXPIRED_OR_REVOKED
                else->localState()
            }
            if(BackendAuthRules.authenticationRequired(r.error))secretStore.put(BackendSecretKeys.SESSION_TOKEN,BackendSessionRules.clearedToken())
            preferences.setBackendAuthState(state.name)
            return BackendAccountAction(false,state,if(r.error=="AUTH_INVALID")"ACCOUNT_CREDENTIAL_INVALID" else r.error?:"SESSION_ISSUE_FAILED")
        }
        val root=runCatching{JSONObject(r.responseBody.orEmpty())}.getOrNull()?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val sessionToken=BackendSessionRules.replacementToken(root.optString("sessionToken"))?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val expiresAt=root.optLong("expiresAt",0L).takeIf{it>System.currentTimeMillis()}?:return failure("AUTH_PROTOCOL_INVALID",localState())
        val workspaces=root.optJSONArray("workspaces")
        val memberships=buildList{if(workspaces!=null)for(i in 0 until workspaces.length()){workspaces.optString(i).takeIf{SyncIntegrityRules::validWorkspaceId}?.let(::add)}}
        val currentWorkspace=preferences.syncWorkspaceId.first()
        val selected=when{currentWorkspace in memberships->currentWorkspace;memberships.isNotEmpty()->memberships.first();else->null}
            ?:return failure("MEMBERSHIP_REQUIRED",BackendAccountState.UNAUTHENTICATED)
        secretStore.put(BackendSecretKeys.SESSION_TOKEN,sessionToken)
        preferences.setBackendAccountMetadata(accountId,selected,expiresAt,BackendAccountState.AUTHENTICATED.name)
        return BackendAccountAction(true,BackendAccountState.AUTHENTICATED)
    }

    suspend fun logout():BackendAccountAction{
        val session=secretStore.get(BackendSecretKeys.SESSION_TOKEN)
        if(!session.isNullOrBlank())backend.postWithBearer("/api/v1/auth/logout","{}","auth-logout:${UUID.randomUUID()}",session)
        secretStore.put(BackendSecretKeys.SESSION_TOKEN,BackendSessionRules.clearedToken())
        preferences.setBackendSession(null,BackendAccountState.UNAUTHENTICATED.name)
        return BackendAccountAction(true,BackendAccountState.UNAUTHENTICATED)
    }

    suspend fun sessionTokenForSync():String?{
        val token=secretStore.get(BackendSecretKeys.SESSION_TOKEN)?:return null
        val expiry=preferences.backendSessionExpiresAt.first()
        if(expiry!=null&&expiry<=System.currentTimeMillis()){
            secretStore.put(BackendSecretKeys.SESSION_TOKEN,null)
            preferences.setBackendSession(null,BackendAccountState.EXPIRED_OR_REVOKED.name)
            return null
        }
        return token
    }

    suspend fun handleAuthFailure(code:String?){
        if(!BackendAuthRules.identityRecoveryRequired(code))return
        val state=if(BackendAuthRules.provisioningRequired(code))BackendAccountState.PROVISIONING_REQUIRED else BackendAccountState.EXPIRED_OR_REVOKED
        secretStore.put(BackendSecretKeys.SESSION_TOKEN,BackendSessionRules.clearedToken())
        preferences.setBackendSession(null,state.name)
    }

    private suspend fun localState():BackendAccountState{
        val stored=runCatching{BackendAccountState.valueOf(preferences.backendAuthState.first())}.getOrDefault(BackendAccountState.UNAUTHENTICATED)
        val accountId=preferences.backendAccountId.first()
        if(accountId.isNullOrBlank())return stored.takeIf{it==BackendAccountState.PROVISIONING_REQUIRED}?:BackendAccountState.UNAUTHENTICATED
        val token=secretStore.get(BackendSecretKeys.SESSION_TOKEN)
        val expiry=preferences.backendSessionExpiresAt.first()
        if(token.isNullOrBlank())return stored.takeIf{it==BackendAccountState.EXPIRED_OR_REVOKED||it==BackendAccountState.PROVISIONING_REQUIRED}?:BackendAccountState.UNAUTHENTICATED
        if(expiry!=null&&expiry<=System.currentTimeMillis()){
            secretStore.put(BackendSecretKeys.SESSION_TOKEN,null);preferences.setBackendSession(null,BackendAccountState.EXPIRED_OR_REVOKED.name)
            return BackendAccountState.EXPIRED_OR_REVOKED
        }
        if(stored!=BackendAccountState.AUTHENTICATED)preferences.setBackendAuthState(BackendAccountState.AUTHENTICATED.name)
        return BackendAccountState.AUTHENTICATED
    }

    private suspend fun failure(error:String,state:BackendAccountState):BackendAccountAction{preferences.setBackendAuthState(state.name);return BackendAccountAction(false,state,error)}
}
