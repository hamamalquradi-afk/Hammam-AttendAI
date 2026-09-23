package com.hammam.attendai.importexport

import java.io.File

/** Bounded cleanup for app-owned share/export artifacts. Never touches a file outside [directory]. */
object LocalFileRetention {
    fun prune(directory:File,keepNewest:Int,prefix:String?=null){
        if(keepNewest<0)return
        val files=directory.listFiles()?.filter{it.isFile && (prefix==null || it.name.startsWith(prefix))}.orEmpty()
            .sortedByDescending{it.lastModified()}
        files.drop(keepNewest).forEach{runCatching{it.delete()}}
    }
}
