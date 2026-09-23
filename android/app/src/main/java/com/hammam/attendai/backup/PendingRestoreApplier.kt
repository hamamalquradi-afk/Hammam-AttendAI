package com.hammam.attendai.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Applies an already validated staged restore before Room is opened. */
object PendingRestoreApplier{
    private const val DB_NAME="hammam_attendai.db"
    private const val PENDING="pending_restore.db"
    private const val MARKER="pending_restore.ready"

    fun applyIfPresent(context:Context):Boolean{
        val dir=context.noBackupFilesDir;val pending=File(dir,PENDING);val marker=File(dir,MARKER)
        // Deterministically clean incomplete staging, never touching the current DB.
        if(marker.exists()&&!pending.exists()){marker.delete();return false}
        if(pending.exists()&&!marker.exists()){pending.delete();return false}
        if(!marker.exists())return false
        val target=context.getDatabasePath(DB_NAME);target.parentFile?.mkdirs()
        val rollback=File(target.parentFile,"$DB_NAME.pre_restore")
        val install=File(target.parentFile,"$DB_NAME.restore_install")
        return try{
            // A rollback file only proves a previous attempt started. Re-apply the staged DB instead of
            // assuming a healthy current target is the restored target (the process may have died before swap).
            if(rollback.exists()&&!target.exists()&&sqliteHealthy(rollback))copyDurably(rollback,target)
            require(sqliteHealthy(pending)){"RESTORE_PENDING_DB_INVALID"}
            install.delete();copyDurably(pending,install);require(sqliteHealthy(install)){"RESTORE_INSTALL_DB_INVALID"}
            rollback.delete()
            if(target.exists()&&sqliteHealthy(target)){checkpointWal(target);copyDurably(target,rollback)}
            cleanupSidecars(target)
            if(target.exists()&&!target.delete())error("RESTORE_TARGET_REPLACE_FAILED")
            if(!install.renameTo(target)){copyDurably(install,target);install.delete()}
            require(sqliteHealthy(target)){"RESTORE_INSTALLED_DB_INVALID"}
            pending.delete();marker.delete();rollback.delete();cleanupSidecars(target);true
        }catch(_:Throwable){
            install.delete()
            if(rollback.exists()&&sqliteHealthy(rollback))runCatching{if(target.exists())target.delete();copyDurably(rollback,target);cleanupSidecars(target)}
            // A handled restore failure is terminal for this staged request. Do not retry destructively on every startup.
            pending.delete();marker.delete();rollback.delete()
            false
        }
    }

    private fun checkpointWal(target:File){runCatching{SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use{cursor->while(cursor.moveToNext())Unit}}}}
    private fun cleanupSidecars(target:File){File(target.path+"-wal").delete();File(target.path+"-shm").delete()}
    private fun sqliteHealthy(file:File):Boolean=runCatching{SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery("PRAGMA quick_check(1)",null).use{c->c.moveToFirst()&&c.getString(0).equals("ok",true)}}}.getOrDefault(false)
    private fun copyDurably(from:File,to:File){to.parentFile?.mkdirs();FileInputStream(from).use{input->FileOutputStream(to).use{out->input.copyTo(out);out.flush();out.fd.sync()}}}
    fun pendingFile(context:Context)=File(context.noBackupFilesDir,PENDING)
    fun markerFile(context:Context)=File(context.noBackupFilesDir,MARKER)
}
