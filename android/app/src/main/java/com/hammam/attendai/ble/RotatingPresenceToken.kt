package com.hammam.attendai.ble

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RotatingPresenceToken {
    private const val SLOT_MS=30_000L
    fun slot(timestampMillis:Long)=timestampMillis/SLOT_MS
    fun token(secret:ByteArray, devicePublicId:String, timestampMillis:Long):String {
        val input="$devicePublicId:${slot(timestampMillis)}".toByteArray()
        val mac=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(secret,"HmacSHA256"))}.doFinal(input)
        return mac.take(8).joinToString(""){"%02x".format(it)}
    }
    fun accepts(secret:ByteArray, devicePublicId:String, received:String, nowMillis:Long):Boolean {
        if(received.length!=16 || !received.all{it in '0'..'9' || it in 'a'..'f'})return false
        return listOf(nowMillis-SLOT_MS,nowMillis,nowMillis+SLOT_MS).any{ constantTimeEquals(token(secret,devicePublicId,it),received) }
    }
    private fun constantTimeEquals(a:String,b:String):Boolean {
        if(a.length!=b.length)return false
        var d=0; a.indices.forEach{d=d or (a[it].code xor b[it].code)}; return d==0
    }
}

internal object DynamicQrPresencePayload {
    private const val PREFIX="HAD1|"
    fun extractToken(payload:String):String? {
        val value=payload.trim()
        if(!value.startsWith(PREFIX))return null
        val token=value.removePrefix(PREFIX)
        return token.takeIf{it.length==16 && it.all{c->c in '0'..'9' || c in 'a'..'f'}}
    }
}
