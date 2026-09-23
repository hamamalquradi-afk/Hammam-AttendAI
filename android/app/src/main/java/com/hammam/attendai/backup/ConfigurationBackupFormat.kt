package com.hammam.attendai.backup

import java.io.IOException

/** Stable HAMMAM_CONFIG_V1 framing and URL-safe Base64 encoding with no padding. */
object ConfigurationBackupFormat {
    const val HEADER="HAMMAM_CONFIG_V1"
    val allowedSections:Set<String> = setOf("POLICY","REPORT","FLAG","ROLEPERM","YEAR","SEMESTER","UI")
    val forbiddenSecretSections:Set<String> = setOf("USER","USERS","STUDENT","STUDENTS","ATTENDANCE","AI_KEY","PROVIDER_SECRET","BACKEND_CREDENTIAL","PIN","KEYSTORE")

    private const val ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val reverse=IntArray(128){-1}.also{table->ALPHABET.forEachIndexed{i,c->table[c.code]=i}}

    fun encode(value:String?):String{
        val bytes=(value?:"").toByteArray(Charsets.UTF_8)
        if(bytes.isEmpty())return ""
        val out=StringBuilder((bytes.size*4+2)/3)
        var i=0
        while(i+2<bytes.size){
            val n=((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i+1].toInt() and 0xff) shl 8) or (bytes[i+2].toInt() and 0xff)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i+=3
        }
        val remaining=bytes.size-i
        if(remaining==1){
            val n=(bytes[i].toInt() and 0xff) shl 16
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
        }else if(remaining==2){
            val n=((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i+1].toInt() and 0xff) shl 8)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63])
        }
        return out.toString()
    }

    fun decode(value:String):String{
        val input=value.trimEnd('=')
        if(input.isEmpty())return ""
        if(input.length%4==1)throw IOException("INVALID_BASE64")
        val out=ByteArray((input.length*6)/8)
        var outIndex=0
        var buffer=0
        var bits=0
        input.forEach{ch->
            val code=ch.code
            val v=if(code<reverse.size)reverse[code] else -1
            if(v<0)throw IOException("INVALID_BASE64")
            buffer=(buffer shl 6) or v
            bits+=6
            if(bits>=8){
                bits-=8
                out[outIndex++]=((buffer ushr bits) and 0xff).toByte()
            }
        }
        return String(out.copyOf(outIndex),Charsets.UTF_8)
    }

    fun headerIssue(lines:List<String>):String?=if(lines.firstOrNull()==HEADER)null else "INVALID_CONFIG_FORMAT"
    fun isAllowedSection(section:String):Boolean=section in allowedSections
}
