package com.hammam.attendai.importexport

import java.io.IOException

enum class FileIoFailure {
    CANCELLED,
    PICKER_UNAVAILABLE,
    ACCESS_DENIED,
    INVALID_REQUEST,
    PLATFORM_FAILURE,
    NULL_STREAM,
    UNREADABLE_URI,
}

data class FileIoDecision(val tryFallback:Boolean,val messageCode:String)

/** Pure decision/mapping logic so launcher failures can be regression-tested without faking Android Activities. */
object FileIoSafety {
    fun fromExceptionClass(simpleName:String):FileIoFailure=when(simpleName){
        "ActivityNotFoundException"->FileIoFailure.PICKER_UNAVAILABLE
        "SecurityException"->FileIoFailure.ACCESS_DENIED
        "IllegalArgumentException"->FileIoFailure.INVALID_REQUEST
        else->FileIoFailure.PLATFORM_FAILURE
    }

    fun isError(failure:FileIoFailure):Boolean=failure!=FileIoFailure.CANCELLED

    fun decision(failure:FileIoFailure):FileIoDecision=when(failure){
        FileIoFailure.CANCELLED->FileIoDecision(false,"FILE_ACTION_CANCELLED")
        FileIoFailure.PICKER_UNAVAILABLE->FileIoDecision(true,"FILE_PICKER_UNAVAILABLE")
        FileIoFailure.ACCESS_DENIED->FileIoDecision(true,"FILE_PICKER_ACCESS_DENIED")
        FileIoFailure.INVALID_REQUEST->FileIoDecision(true,"FILE_PICKER_INVALID_REQUEST")
        FileIoFailure.PLATFORM_FAILURE->FileIoDecision(true,"FILE_PICKER_PLATFORM_FAILURE")
        FileIoFailure.NULL_STREAM->FileIoDecision(false,"FILE_OUTPUT_STREAM_UNAVAILABLE")
        FileIoFailure.UNREADABLE_URI->FileIoDecision(false,"FILE_URI_UNREADABLE")
    }

    fun <T:Any> requireOpened(value:T?,code:String):T=value?:throw IOException(code)
}
