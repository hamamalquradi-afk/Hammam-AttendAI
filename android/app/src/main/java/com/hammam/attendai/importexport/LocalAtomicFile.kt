package com.hammam.attendai.importexport

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Installs an app-owned temporary file without sacrificing an existing valid target on failure. */
object LocalAtomicFile {
    fun replaceFrom(temp:File,target:File,failureCode:String){
        target.parentFile?.mkdirs()
        val previous=File(target.parentFile?:temp.parentFile,".${target.name}.${UUID.randomUUID()}.previous")
        var backedUp=false
        try{
            if(target.exists()){
                if(target.renameTo(previous))backedUp=true else{
                    copyDurably(target,previous);backedUp=true
                    if(!target.delete())error(failureCode)
                }
            }
            if(!temp.renameTo(target)){copyDurably(temp,target);temp.delete()}
            if(previous.exists())previous.delete()
        }catch(t:Throwable){
            runCatching{
                if(target.exists())target.delete()
                if(backedUp&&previous.exists()){
                    if(!previous.renameTo(target)){copyDurably(previous,target);previous.delete()}
                }
            }
            throw t
        }finally{
            temp.delete()
            if(!backedUp)previous.delete()
        }
    }

    private fun copyDurably(from:File,to:File){
        to.parentFile?.mkdirs()
        FileInputStream(from).use{input->FileOutputStream(to).use{out->input.copyTo(out);out.flush();out.fd.sync()}}
    }
}
