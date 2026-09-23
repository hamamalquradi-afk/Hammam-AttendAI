package com.hammam.attendai.security

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Pbkdf2Sha256 {
    fun derive(password:CharArray,salt:ByteArray,iterations:Int,keyLengthBits:Int):ByteArray {
        require(iterations>0 && keyLengthBits>0 && keyLengthBits%8==0)
        return runCatching {
            val spec=PBEKeySpec(password,salt,iterations,keyLengthBits)
            try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }.getOrElse { deriveFallback(password,salt,iterations,keyLengthBits/8) }
    }

    internal fun deriveFallback(password:CharArray,salt:ByteArray,iterations:Int,keyLengthBytes:Int):ByteArray {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(String(password).toByteArray(Charsets.UTF_8),"HmacSHA256"))
        val hLen=mac.macLength
        val blocks=(keyLengthBytes+hLen-1)/hLen
        val out=ByteArray(blocks*hLen)
        for(block in 1..blocks){
            mac.reset()
            mac.update(salt)
            var u=mac.doFinal(ByteBuffer.allocate(4).putInt(block).array())
            val t=u.copyOf()
            repeat(iterations-1){
                u=mac.doFinal(u)
                for(i in t.indices)t[i]=(t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t,0,out,(block-1)*hLen,hLen)
        }
        return out.copyOf(keyLengthBytes)
    }
}
