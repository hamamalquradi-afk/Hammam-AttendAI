package com.hammam.attendai.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hammam.attendai.BuildConfig
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.BackupHistoryEntity
import com.hammam.attendai.security.Pbkdf2Sha256
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedBackupManager(private val context:Context,private val db:HammamDatabase){
    companion object { private val MAGIC="HAMMAMATTEND1".toByteArray(); private const val ITER=220_000;private const val FORMAT=3 }
    data class Validation(val valid:Boolean,val databaseVersion:Int?,val sizeBytes:Long,val checksum:String?,val error:String?)

    fun export(databaseFile:File,target:File,passphrase:CharArray){
        require(passphrase.size>=8);db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close();target.parentFile?.mkdirs()
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val iv=ByteArray(12).also{SecureRandom().nextBytes(it)};val key=derive(passphrase,salt);val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv))}
        val bytes=databaseFile.readBytes();val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
        DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use{out->out.write(MAGIC);out.writeInt(FORMAT);out.writeInt(BuildConfig.DATABASE_VERSION);out.write(salt);out.write(iv);out.writeInt(digest.size);out.write(digest);out.write(cipher.doFinal(bytes))}
    }
    suspend fun createBackup(databaseFile:File,target:File,passphrase:CharArray){
        runCatching{export(databaseFile,target,passphrase);val hash=sha256(target);db.coreDao().insertBackupHistory(BackupHistoryEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),target.name,target.length(),BuildConfig.DATABASE_VERSION,"SUCCESS",hash,null))}.getOrElse{e->runCatching{db.coreDao().insertBackupHistory(BackupHistoryEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),target.name,target.length(),BuildConfig.DATABASE_VERSION,"FAILED",null,e.javaClass.simpleName))};throw e}
    }
    fun validate(source:File,passphrase:CharArray):Validation=runCatching{
        val decoded=decode(source,passphrase);val tmp=File(context.cacheDir,"backup-validate-${UUID.randomUUID()}.db");tmp.writeBytes(decoded.plain)
        val dbVersion=readDbVersion(tmp);val checksum=sha256Bytes(decoded.plain);tmp.delete();require(dbVersion in 1..BuildConfig.DATABASE_VERSION){"UNSUPPORTED_DATABASE_VERSION_$dbVersion"};Validation(true,dbVersion,source.length(),checksum,null)
    }.getOrElse{Validation(false,null,source.length(),null,it.message?:it.javaClass.simpleName)}

    /** Stages validated DB bytes. They are swapped atomically on the next process start, before Room opens. */
    fun stageRestore(source:File,passphrase:CharArray):Validation{
        val decoded=runCatching{decode(source,passphrase)}.getOrElse{return Validation(false,null,source.length(),null,it.message?:it.javaClass.simpleName)}
        val pending=PendingRestoreApplier.pendingFile(context);val marker=PendingRestoreApplier.markerFile(context)
        return runCatching{pending.writeBytes(decoded.plain);val dbVersion=readDbVersion(pending);require(dbVersion in 1..BuildConfig.DATABASE_VERSION){"UNSUPPORTED_DATABASE_VERSION_$dbVersion"};FileOutputStream(pending,true).fd.sync();marker.writeText("version=$dbVersion\ncreated=${System.currentTimeMillis()}");Validation(true,dbVersion,source.length(),sha256Bytes(decoded.plain),null)}.getOrElse{pending.delete();marker.delete();Validation(false,null,source.length(),null,it.message?:it.javaClass.simpleName)}
    }

    private data class Decoded(val plain:ByteArray,val declaredDbVersion:Int?)
    private fun decode(source:File,passphrase:CharArray):Decoded=DataInputStream(BufferedInputStream(FileInputStream(source))).use{input->
        val magic=ByteArray(MAGIC.size).also{input.readFully(it)};require(magic.contentEquals(MAGIC)){"INVALID_BACKUP"};val version=input.readInt();require(version in 1..FORMAT){"UNSUPPORTED_BACKUP_FORMAT"};val declared=if(version>=3)input.readInt() else null
        val salt=ByteArray(16).also{input.readFully(it)};val iv=ByteArray(12).also{input.readFully(it)};val n=input.readInt();require(n==32){"INVALID_BACKUP_DIGEST"};val expected=ByteArray(n).also{input.readFully(it)}
        val encrypted=input.readBytes();val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,derive(passphrase,salt),GCMParameterSpec(128,iv))};val plain=cipher.doFinal(encrypted);require(MessageDigest.getInstance("SHA-256").digest(plain).contentEquals(expected)){"BACKUP_INTEGRITY_FAILED"};Decoded(plain,declared)
    }
    private fun readDbVersion(file:File):Int{val sqlite=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY);return sqlite.use{db->db.rawQuery("PRAGMA user_version",null).use{c->if(c.moveToFirst())c.getInt(0) else 0}}}
    private fun derive(passphrase:CharArray,salt:ByteArray)=SecretKeySpec(Pbkdf2Sha256.derive(passphrase,salt,ITER,256),"AES")
    private fun sha256(file:File)=sha256Bytes(file.readBytes())
    private fun sha256Bytes(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
