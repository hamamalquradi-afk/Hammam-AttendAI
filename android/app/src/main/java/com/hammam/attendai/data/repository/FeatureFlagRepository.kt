package com.hammam.attendai.data.repository

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.data.local.entity.FeatureFlagEntity
import com.hammam.attendai.security.AuthorizationRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object FeatureFlagCatalog {
    val defaults=linkedMapOf(
        "BLE_ATTENDANCE" to true,
        "QR_ATTENDANCE" to true,
        "NFC_ATTENDANCE" to false,
        "AI_ASSISTANT" to true,
        "WHATSAPP" to false,
        "EMAIL" to false,
        "AUTO_REPORTS" to true,
        "CLOUD_SYNC" to false,
        "REMOTE_BACKUP" to false,
    )
    val codes:Set<String> get()=defaults.keys
}

class FeatureFlagRepository(private val db:HammamDatabase,private val authorization:AuthorizationRepository){
    private val dao=db.coreDao()
    fun observe():Flow<List<FeatureFlagEntity>> = dao.observeFeatureFlags()
    suspend fun seedDefaults(){val now=System.currentTimeMillis();FeatureFlagCatalog.defaults.forEach{(code,enabled)->if(dao.isFeatureEnabled(code)==null)dao.upsertFeatureFlag(FeatureFlagEntity("flag:$code",code,enabled,now))}}
    suspend fun set(code:String,enabled:Boolean,actorId:String)=db.withTransaction{
        require(authorization.hasPermission(actorId,"MANAGE_SETTINGS")){"MANAGE_SETTINGS_PERMISSION_REQUIRED"}
        require(code in FeatureFlagCatalog.codes){"UNKNOWN_FEATURE_FLAG"}
        val now=System.currentTimeMillis();dao.upsertFeatureFlag(FeatureFlagEntity("flag:$code",code,enabled,now))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"FEATURE_FLAG_UPDATED","FeatureFlag","flag:$code",null,"{\"code\":\"$code\",\"enabled\":$enabled}",null,now,authorization.roleNames(actorId).firstOrNull()))
    }
}
