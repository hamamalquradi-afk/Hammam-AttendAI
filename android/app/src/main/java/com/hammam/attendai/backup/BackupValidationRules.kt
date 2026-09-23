package com.hammam.attendai.backup

object BackupValidationRules {
    fun validateVersion(declared:Int?,actual:Int,current:Int):String? = when {
        actual !in 1..current -> "UNSUPPORTED_DATABASE_VERSION"
        declared!=null && declared!=actual -> "BACKUP_DATABASE_VERSION_MISMATCH"
        else -> null
    }

    fun safeError(error:Throwable):String = when(error){
        is javax.crypto.AEADBadTagException -> "BACKUP_AUTHENTICATION_FAILED"
        is java.io.EOFException -> "INVALID_BACKUP"
        is java.io.FileNotFoundException -> "BACKUP_FILE_UNAVAILABLE"
        is java.lang.SecurityException -> "BACKUP_FILE_PERMISSION_DENIED"
        else -> error.message?.takeIf{it.matches(Regex("[A-Z][A-Z0-9_]{2,80}"))} ?: "BACKUP_OPERATION_FAILED"
    }
}
