package com.hammam.attendai.ble

import android.util.Base64
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.domain.model.StudentStatus

class RoomPresenceTokenResolver(private val db:HammamDatabase,private val cipher:KeystoreCipher):PresenceTokenResolver{
    override suspend fun resolve(rotatingId:String,timestampMillis:Long):ResolvedPresence?{
        val dao=db.coreDao()
        for(device in dao.getActiveStudentDevices()){
            val student=dao.getStudentById(device.studentId)?.takeIf{it.status==StudentStatus.ACTIVE && it.registeredDeviceId==device.id}?:continue
            val encrypted=device.presenceSecretCiphertext ?: continue
            val plain=runCatching{cipher.decrypt(encrypted)}.getOrNull() ?: continue
            val secret=runCatching{Base64.decode(plain,Base64.NO_WRAP)}.getOrElse{plain.toByteArray(Charsets.UTF_8)}
            if(RotatingPresenceToken.accepts(secret,device.devicePublicId,rotatingId,timestampMillis)) return ResolvedPresence(device.studentId,device.id,true)
        }
        return null
    }
}
