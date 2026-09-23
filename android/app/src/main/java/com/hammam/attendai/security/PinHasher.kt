package com.hammam.attendai.security

import android.util.Base64
import java.security.SecureRandom

object PinHasher {
    fun hash(pin:CharArray, iterations:Int=180_000):String {
        require(pin.size in 4..12)
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val out=Pbkdf2Sha256.derive(pin,salt,iterations,256)
        return listOf(iterations.toString(),Base64.encodeToString(salt,Base64.NO_WRAP),Base64.encodeToString(out,Base64.NO_WRAP)).joinToString(":")
    }
    fun verify(pin:CharArray, stored:String):Boolean {
        val p=stored.split(":"); if(p.size!=3)return false
        val it=p[0].toIntOrNull()?:return false
        val salt=Base64.decode(p[1],Base64.NO_WRAP); val expected=Base64.decode(p[2],Base64.NO_WRAP)
        val actual=Pbkdf2Sha256.derive(pin,salt,it,expected.size*8)
        var diff=actual.size xor expected.size
        for(i in 0 until minOf(actual.size,expected.size)) diff=diff or (actual[i].toInt() xor expected[i].toInt())
        return diff==0
    }
}
