package com.hammam.attendai.appeals

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

data class AttachmentInfo(val uri:Uri,val displayName:String,val mimeType:String?,val sizeBytes:Long)
sealed interface AttachmentValidationResult {
    data class Valid(val info:AttachmentInfo):AttachmentValidationResult
    data class Invalid(val reason:String):AttachmentValidationResult
}

class AttachmentValidator(private val resolver:ContentResolver){
    companion object {
        const val MAX_BYTES:Long=10L*1024L*1024L
        private val ALLOWED_PREFIXES=listOf("image/","application/pdf")
    }
    fun validate(uri:Uri):AttachmentValidationResult{
        if(uri.scheme!=ContentResolver.SCHEME_CONTENT)return AttachmentValidationResult.Invalid("UNSUPPORTED_URI")
        val type=resolver.getType(uri)?:return AttachmentValidationResult.Invalid("UNSUPPORTED_TYPE")
        if(ALLOWED_PREFIXES.none{type.startsWith(it)}) return AttachmentValidationResult.Invalid("UNSUPPORTED_TYPE")
        val meta=query(uri)?:return AttachmentValidationResult.Invalid("FILE_NOT_FOUND")
        if(meta.sizeBytes<0) return AttachmentValidationResult.Invalid("UNKNOWN_SIZE")
        if(meta.sizeBytes>MAX_BYTES) return AttachmentValidationResult.Invalid("FILE_TOO_LARGE")
        val readable=runCatching{resolver.openInputStream(uri)?.use{it.read(ByteArray(1))}?:throw IllegalStateException()}.isSuccess
        if(!readable)return AttachmentValidationResult.Invalid("FILE_NOT_READABLE")
        return AttachmentValidationResult.Valid(meta.copy(mimeType=type))
    }
    private fun query(uri:Uri):AttachmentInfo?{
        var name=uri.lastPathSegment ?: "attachment"
        var size=-1L
        val cursor:Cursor=runCatching{resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)}.getOrNull()?:return null
        cursor.use{
            if(!it.moveToFirst())return null
            val ni=it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(ni>=0)name=it.getString(ni)?:name
            val si=it.getColumnIndex(OpenableColumns.SIZE); if(si>=0&&!it.isNull(si))size=it.getLong(si)
        }
        return AttachmentInfo(uri,name,null,size)
    }
}
