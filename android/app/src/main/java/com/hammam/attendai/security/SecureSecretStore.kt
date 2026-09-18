package com.hammam.attendai.security

import android.content.Context
import java.io.File
import java.util.Properties

/** Secrets live outside Room/DataStore/backups. Values are encrypted with Android Keystore before disk. */
class SecureSecretStore(context:Context,private val cipher:KeystoreCipher){
    private val file=File(context.noBackupFilesDir,"provider_secrets.properties")
    @Synchronized fun put(key:String,value:String?){
        val p=load();if(value.isNullOrBlank())p.remove(key) else p.setProperty(key,cipher.encrypt(value.trim()));save(p)
    }
    @Synchronized fun get(key:String):String?=load().getProperty(key)?.let{runCatching{cipher.decrypt(it)}.getOrNull()}
    @Synchronized fun configured(key:String)=load().containsKey(key)
    @Synchronized fun maskedSuffix(key:String):String?=get(key)?.takeLast(4)?.let{"••••$it"}
    private fun load()=Properties().apply{if(file.exists())file.inputStream().use(::load)}
    private fun save(p:Properties){file.parentFile?.mkdirs();file.outputStream().use{p.store(it,"Hammam AttendAI secure provider secrets - encrypted")}}
}
