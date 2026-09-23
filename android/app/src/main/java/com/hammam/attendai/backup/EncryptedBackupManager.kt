package com.hammam.attendai.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hammam.attendai.BuildConfig
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.BackupHistoryEntity
import com.hammam.attendai.importexport.LocalAtomicFile
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
    private data class Decoded(val plain:ByteArray,val format:Int,val declaredDbVersion:Int?)

    fun export(databaseFile:File,target:File,passphrase:CharArray){
        require(passphrase.size>=8){"BACKUP_PASSPHRASE_TOO_SHORT"}
        require(databaseFile.isFile&&databaseFile.canRead()){"BACKUP_DATABASE_UNAVAILABLE"}
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        target.parentFile?.mkdirs()
        val partial=File(target.parentFile?:context.cacheDir,".${target.name}.${UUID.randomUUID()}.partial")
        try{
            val bytes=databaseFile.readBytes();val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
            val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,derive(passphrase,salt),GCMParameterSpec(128,iv))}
            FileOutputStream(partial).use{fos->DataOutputStream(BufferedOutputStream(fos)).use{out->
                out.write(MAGIC);out.writeInt(FORMAT);out.writeInt(BuildConfig.DATABASE_VERSION);out.write(salt);out.write(iv);out.writeInt(digest.size);out.write(digest);out.write(cipher.doFinal(bytes));out.flush()
            };fos.fd.sync()}
            LocalAtomicFile.replaceFrom(partial,target,"BACKUP_TARGET_REPLACE_FAILED")
        }catch(t:Throwable){partial.delete();throw t}
    }

    suspend fun createBackup(databaseFile:File,target:File,passphrase:CharArray){
        runCatching{
            export(databaseFile,target,passphrase);val hash=sha256(target)
            db.coreDao().insertBackupHistory(BackupHistoryEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),target.name,target.length(),BuildConfig.DATABASE_VERSION,"SUCCESS",hash,null))
        }.getOrElse{e->
            target.takeIf{it.exists()&&it.length()==0L}?.delete()
            val code=BackupValidationRules.safeError(e)
            runCatching{db.coreDao().insertBackupHistory(BackupHistoryEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),target.name,target.takeIf{it.exists()}?.length()?:0L,BuildConfig.DATABASE_VERSION,"FAILED",null,code))}
            throw IllegalStateException(code,e)
        }
    }

    fun validate(source:File,passphrase:CharArray):Validation = validateDecoded(source,passphrase)

    /** Stages only cryptographically and structurally validated DB bytes. Swap occurs on the next process start before Room opens. */
    fun stageRestore(source:File,passphrase:CharArray):Validation{
        val decoded=try{decode(source,passphrase)}catch(t:Throwable){return Validation(false,null,source.length(),null,BackupValidationRules.safeError(t))}
        val checked=validatePlain(decoded,source.length())
        if(!checked.valid)return checked
        val pending=PendingRestoreApplier.pendingFile(context);val marker=PendingRestoreApplier.markerFile(context)
        val pendingPartial=File(pending.parentFile,"${pending.name}.partial");val markerPartial=File(marker.parentFile,"${marker.name}.partial")
        return try{
            if(marker.exists()&&pending.exists())error("RESTORE_ALREADY_STAGED")
            if(marker.exists()&&!pending.exists())marker.delete()
            if(pending.exists()&&!marker.exists())pending.delete()
            pendingPartial.delete();markerPartial.delete()
            writeDurably(pendingPartial,decoded.plain)
            // Re-open staged bytes before advertising readiness.
            requireSqliteHealthy(pendingPartial,decoded.declaredDbVersion)
            if(pending.exists()&&!pending.delete())error("RESTORE_STAGING_REPLACE_FAILED")
            if(!pendingPartial.renameTo(pending)){copyDurably(pendingPartial,pending);pendingPartial.delete()}
            writeDurably(markerPartial,"version=${checked.databaseVersion}\ncreated=${System.currentTimeMillis()}\n".toByteArray())
            if(marker.exists()&&!marker.delete())error("RESTORE_MARKER_REPLACE_FAILED")
            if(!markerPartial.renameTo(marker)){copyDurably(markerPartial,marker);markerPartial.delete()}
            checked
        }catch(t:Throwable){pendingPartial.delete();markerPartial.delete();if(!marker.exists())pending.delete();Validation(false,null,source.length(),null,BackupValidationRules.safeError(t))}
    }

    private fun validateDecoded(source:File,passphrase:CharArray):Validation = try{
        val decoded=decode(source,passphrase);validatePlain(decoded,source.length())
    }catch(t:Throwable){Validation(false,null,source.length(),null,BackupValidationRules.safeError(t))}

    private fun validatePlain(decoded:Decoded,sourceSize:Long):Validation{
        val tmp=File(context.cacheDir,"backup-validate-${UUID.randomUUID()}.db")
        return try{
            writeDurably(tmp,decoded.plain)
            val dbVersion=requireSqliteHealthy(tmp,decoded.declaredDbVersion)
            Validation(true,dbVersion,sourceSize,sha256Bytes(decoded.plain),null)
        }catch(t:Throwable){Validation(false,null,sourceSize,null,BackupValidationRules.safeError(t))}
        finally{tmp.delete()}
    }

    private fun decode(source:File,passphrase:CharArray):Decoded=DataInputStream(BufferedInputStream(FileInputStream(source))).use{input->
        require(passphrase.size>=8){"BACKUP_PASSPHRASE_TOO_SHORT"}
        val magic=ByteArray(MAGIC.size).also{input.readFully(it)};require(magic.contentEquals(MAGIC)){"INVALID_BACKUP"}
        val version=input.readInt();require(version in 1..FORMAT){"UNSUPPORTED_BACKUP_FORMAT"}
        val declared=if(version>=3)input.readInt() else null
        val salt=ByteArray(16).also{input.readFully(it)};val iv=ByteArray(12).also{input.readFully(it)}
        val n=input.readInt();require(n==32){"INVALID_BACKUP_DIGEST"};val expected=ByteArray(n).also{input.readFully(it)}
        val encrypted=input.readBytes();require(encrypted.isNotEmpty()){"INVALID_BACKUP"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,derive(passphrase,salt),GCMParameterSpec(128,iv))}
        val plain=cipher.doFinal(encrypted)
        require(MessageDigest.getInstance("SHA-256").digest(plain).contentEquals(expected)){"BACKUP_INTEGRITY_FAILED"}
        Decoded(plain,version,declared)
    }

    private fun requireSqliteHealthy(file:File,declared:Int?):Int{
        val sqlite=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY)
        return sqlite.use{opened->
            val version=opened.rawQuery("PRAGMA user_version",null).use{c->if(c.moveToFirst())c.getInt(0) else 0}
            BackupValidationRules.validateVersion(declared,version,BuildConfig.DATABASE_VERSION)?.let{error(it)}
            val quick=opened.rawQuery("PRAGMA quick_check(1)",null).use{c->if(c.moveToFirst())c.getString(0) else null}
            require(quick.equals("ok",ignoreCase=true)){"BACKUP_SQLITE_INTEGRITY_FAILED"}
            version
        }
    }

    private fun writeDurably(file:File,bytes:ByteArray){file.parentFile?.mkdirs();FileOutputStream(file).use{out->out.write(bytes);out.flush();out.fd.sync()}}
    private fun copyDurably(from:File,to:File){FileInputStream(from).use{input->FileOutputStream(to).use{out->input.copyTo(out);out.flush();out.fd.sync()}}}
    private fun derive(passphrase:CharArray,salt:ByteArray)=SecretKeySpec(Pbkdf2Sha256.derive(passphrase,salt,ITER,256),"AES")
    private fun sha256(file:File)=FileInputStream(file).use{input->val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)};md.digest().joinToString(""){"%02x".format(it)}}
    private fun sha256Bytes(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
