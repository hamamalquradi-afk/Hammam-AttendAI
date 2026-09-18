package com.hammam.attendai.data.repository

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.FeatureFlagEntity
import kotlinx.coroutines.flow.Flow

class FeatureFlagRepository(private val db:HammamDatabase){
    private val dao=db.coreDao()
    fun observe():Flow<List<FeatureFlagEntity>> = dao.observeFeatureFlags()
    suspend fun seedDefaults(){
        val now=System.currentTimeMillis()
        mapOf(
            "BLE_ATTENDANCE" to true,
            "QR_ATTENDANCE" to true,
            "NFC_ATTENDANCE" to false,
            "AI_ASSISTANT" to true,
            "WHATSAPP" to false,
            "EMAIL" to false,
            "AUTO_REPORTS" to true,
            "CLOUD_SYNC" to false,
            "REMOTE_BACKUP" to false,
        ).forEach{(code,enabled)->
            if(dao.isFeatureEnabled(code)==null)dao.upsertFeatureFlag(FeatureFlagEntity("flag:$code",code,enabled,now))
        }
    }
    suspend fun set(code:String,enabled:Boolean){dao.upsertFeatureFlag(FeatureFlagEntity("flag:$code",code,enabled,System.currentTimeMillis()))}
}
