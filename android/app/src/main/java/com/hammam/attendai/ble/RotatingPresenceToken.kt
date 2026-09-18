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
    fun accepts(secret:ByteArray, devicePublicId:String, received:String, nowMillis:Long):Boolean =
        listOf(nowMillis-SLOT_MS,nowMillis,nowMillis+SLOT_MS).any{ constantTimeEquals(token(secret,devicePublicId,it),received) }
    private fun constantTimeEquals(a:String,b:String):Boolean {
        if(a.length!=b.length)return false
        var d=0; a.indices.forEach{d=d or (a[it].code xor b[it].code)}; return d==0
    }
}
