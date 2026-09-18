package com.hammam.attendai.ble

interface QrPresenceVerifier { fun verify(payload:String, nowMillis:Long):Boolean }
interface NfcPresenceVerifier { fun verify(payload:ByteArray, nowMillis:Long):Boolean }

class TimeBoundQrVerifier(private val secret:ByteArray):QrPresenceVerifier {
    fun generate(nowMillis:Long):String { val slot=nowMillis/30_000L; return "$slot.${RotatingPresenceToken.token(secret,"QR",slot*30_000L)}" }
    override fun verify(payload:String,nowMillis:Long):Boolean{
        val parts=payload.split("."); if(parts.size!=2)return false
        val slot=parts[0].toLongOrNull()?:return false
        if(kotlin.math.abs(slot-(nowMillis/30_000L))>1)return false
        val expected=RotatingPresenceToken.token(secret,"QR",slot*30_000L)
        return expected==parts[1]
    }
}
