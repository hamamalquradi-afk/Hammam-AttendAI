package com.hammam.attendai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("hammam_preferences")
class AppPreferences(private val context:Context){
    private val FIRST_RUN=booleanPreferencesKey("first_run_complete")
    private val ROLE=stringPreferencesKey("local_role")
    private val USER_ID=stringPreferencesKey("local_user_id")
    private val THEME=stringPreferencesKey("theme_mode")
    private val LANGUAGE=stringPreferencesKey("language")
    private val BACKEND_BASE_URL=stringPreferencesKey("backend_base_url")
    private val SYNC_WORKSPACE_ID=stringPreferencesKey("sync_workspace_id")
    private val BACKEND_ACCOUNT_ID=stringPreferencesKey("backend_account_id")
    private val BACKEND_SESSION_EXPIRES_AT=longPreferencesKey("backend_session_expires_at")
    private val BACKEND_AUTH_STATE=stringPreferencesKey("backend_auth_state")
    private val ACADEMIC_WEEK_START=stringPreferencesKey("academic_week_start")
    private val OWNER_SETUP_REQUIRED=booleanPreferencesKey("owner_setup_required")
    val firstRunComplete:Flow<Boolean> = context.dataStore.data.map{it[FIRST_RUN]?:false}
    val role:Flow<String?> = context.dataStore.data.map{it[ROLE]}
    val userId:Flow<String?> = context.dataStore.data.map{it[USER_ID]}
    val theme:Flow<String> = context.dataStore.data.map{it[THEME]?:"SYSTEM"}
    val language:Flow<String> = context.dataStore.data.map{it[LANGUAGE]?:"AR"}
    val backendBaseUrl:Flow<String?> = context.dataStore.data.map{it[BACKEND_BASE_URL]}
    val syncWorkspaceId:Flow<String?> = context.dataStore.data.map{it[SYNC_WORKSPACE_ID]}
    val backendAccountId:Flow<String?> = context.dataStore.data.map{it[BACKEND_ACCOUNT_ID]}
    val backendSessionExpiresAt:Flow<Long?> = context.dataStore.data.map{it[BACKEND_SESSION_EXPIRES_AT]}
    val backendAuthState:Flow<String> = context.dataStore.data.map{it[BACKEND_AUTH_STATE]?:"UNAUTHENTICATED"}
    val academicWeekStart:Flow<String> = context.dataStore.data.map{it[ACADEMIC_WEEK_START]?:"MONDAY"}
    val ownerSetupRequired:Flow<Boolean> = context.dataStore.data.map{it[OWNER_SETUP_REQUIRED]?:false}
    suspend fun completeFirstRun(userId:String,role:String,language:String="AR"){
        context.dataStore.edit{it[FIRST_RUN]=true;it[USER_ID]=userId;it[ROLE]=role;it[LANGUAGE]=language}
    }
    suspend fun setTheme(mode:String){context.dataStore.edit{it[THEME]=mode}}
    suspend fun setLanguage(language:String){context.dataStore.edit{it[LANGUAGE]=language}}
    suspend fun setLocalRole(role:String){context.dataStore.edit{it[ROLE]=role}}
    suspend fun setBackendBaseUrl(url:String?){context.dataStore.edit{prefs->if(url.isNullOrBlank())prefs.remove(BACKEND_BASE_URL) else prefs[BACKEND_BASE_URL]=url.trim()}}
    suspend fun setSyncWorkspaceId(workspaceId:String?){context.dataStore.edit{prefs->if(workspaceId.isNullOrBlank())prefs.remove(SYNC_WORKSPACE_ID) else prefs[SYNC_WORKSPACE_ID]=workspaceId.trim()}}
    suspend fun setBackendAccountMetadata(accountId:String,workspaceId:String,expiresAt:Long?,state:String){context.dataStore.edit{prefs->prefs[BACKEND_ACCOUNT_ID]=accountId;prefs[SYNC_WORKSPACE_ID]=workspaceId;if(expiresAt==null)prefs.remove(BACKEND_SESSION_EXPIRES_AT) else prefs[BACKEND_SESSION_EXPIRES_AT]=expiresAt;prefs[BACKEND_AUTH_STATE]=state}}
    suspend fun setBackendSession(expiresAt:Long?,state:String){context.dataStore.edit{prefs->if(expiresAt==null)prefs.remove(BACKEND_SESSION_EXPIRES_AT) else prefs[BACKEND_SESSION_EXPIRES_AT]=expiresAt;prefs[BACKEND_AUTH_STATE]=state}}
    suspend fun setBackendAuthState(state:String){context.dataStore.edit{it[BACKEND_AUTH_STATE]=state}}
    suspend fun setAcademicWeekStart(day:String){require(day in java.time.DayOfWeek.values().toList().map{it.name});context.dataStore.edit{it[ACADEMIC_WEEK_START]=day}}
    suspend fun setOwnerSetupRequired(required:Boolean){context.dataStore.edit{it[OWNER_SETUP_REQUIRED]=required}}
}
