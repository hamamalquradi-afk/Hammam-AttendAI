package com.hammam.attendai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCipher(private val alias:String = "hammam_attendai_local_aes") {
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return kg.generateKey()
    }
    fun encrypt(plain:String):String{
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        val body=c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv+body,Base64.NO_WRAP)
    }
    fun decrypt(encoded:String):String{
        val all=Base64.decode(encoded,Base64.NO_WRAP)
        require(all.size>12)
        val iv=all.copyOfRange(0,12); val body=all.copyOfRange(12,all.size)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
        return c.doFinal(body).toString(Charsets.UTF_8)
    }
}
