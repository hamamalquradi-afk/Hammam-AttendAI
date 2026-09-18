package com.hammam.attendai.backup

import android.content.Context
import java.io.File

/** Applies an already validated restore before Room is opened. */
object PendingRestoreApplier{
    private const val DB_NAME="hammam_attendai.db"
    private const val PENDING="pending_restore.db"
    private const val MARKER="pending_restore.ready"
    fun applyIfPresent(context:Context):Boolean{
        val dir=context.noBackupFilesDir;val pending=File(dir,PENDING);val marker=File(dir,MARKER)
        if(!marker.exists()||!pending.exists())return false
        val target=context.getDatabasePath(DB_NAME);target.parentFile?.mkdirs();val rollback=File(target.parentFile,"$DB_NAME.pre_restore")
        return runCatching{
            File(target.path+"-wal").delete();File(target.path+"-shm").delete();rollback.delete()
            if(target.exists()&&!target.renameTo(rollback))error("RESTORE_ROLLBACK_COPY_FAILED")
            if(!pending.renameTo(target)){pending.copyTo(target,true);pending.delete()}
            marker.delete();rollback.delete();true
        }.getOrElse{if(!target.exists()&&rollback.exists())rollback.renameTo(target);false}
    }
    fun pendingFile(context:Context)=File(context.noBackupFilesDir,PENDING)
    fun markerFile(context:Context)=File(context.noBackupFilesDir,MARKER)
}
