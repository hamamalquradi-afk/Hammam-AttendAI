package com.hammam.attendai.importexport

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalAtomicFileTest {
    @Test fun successfulReplacementInstallsNewContent(){
        val dir=createTempDir(prefix="atomic-file-")
        try{
            val target=File(dir,"target.txt").apply{writeText("old")}
            val temp=File(dir,"temp.txt").apply{writeText("new")}
            LocalAtomicFile.replaceFrom(temp,target,"INSTALL_FAILED")
            assertEquals("new",target.readText())
            assertFalse(temp.exists())
        }finally{dir.deleteRecursively()}
    }
}
