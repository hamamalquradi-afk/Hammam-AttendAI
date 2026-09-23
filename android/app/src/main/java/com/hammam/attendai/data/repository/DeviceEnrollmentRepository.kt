package com.hammam.attendai.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.withTransaction
import com.hammam.attendai.ble.RotatingPresenceToken
import com.hammam.attendai.ble.BleProtocol
import com.hammam.attendai.ble.DeviceActiveInvariantRules
import com.hammam.attendai.ble.DeviceActiveState
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.DeviceStatus
import com.hammam.attendai.domain.model.StudentStatus
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.security.KeystoreCipher
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/** App identity only. No MAC, IMEI, or forbidden hardware identifier is used. */
class DeviceEnrollmentRepository(private val db:HammamDatabase,private val cipher:KeystoreCipher,private val authorization:AuthorizationRepository?=null){
    private val dao=db.coreDao()

    suspend fun enroll(studentId:String,actorId:String):StudentDeviceEntity=db.withTransaction{
        val student=dao.getStudentById(studentId)?:error("STUDENT_NOT_FOUND")
        check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"}
        if(authorization!=null && !authorization.canAccessStudent(actorId,studentId))error("STUDENT_SCOPE_REQUIRED")
        val active=dao.getActiveDevicesForStudent(studentId)
        check(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(active.map{it.id},student.registeredDeviceId))){"REPLACEMENT_APPROVAL_REQUIRED"}
        val pending=createIdentity(studentId)
        val device=pending.toDeviceForStudent(studentId,System.currentTimeMillis())
        dao.insertStudentDevice(device)
        dao.updateStudent(student.copy(registeredDeviceId=device.id,updatedAt=device.registeredAt,version=student.version+1))
        audit(actorId,"DEVICE_ENROLLED",studentId,null,"{\"deviceId\":\"${device.id}\",\"status\":\"ACTIVE\"}","Initial device enrollment",device.registeredAt)
        device
    }

    suspend fun requestReplacement(studentId:String,actorId:String):DeviceReplacementRequestEntity=db.withTransaction{
        val student=dao.getStudentById(studentId)?:error("STUDENT_NOT_FOUND")
        check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"}
        if(authorization!=null && !authorization.canAccessStudent(actorId,studentId))error("STUDENT_SCOPE_REQUIRED")
        val active=dao.getActiveDevicesForStudent(studentId)
        val activeId=DeviceActiveInvariantRules.activeDeviceIdOrNull(DeviceActiveState(active.map{it.id},student.registeredDeviceId))?:error("ACTIVE_DEVICE_STATE_INVALID")
        dao.getPendingDeviceReplacementRequestForStudent(studentId)?.let{return@withTransaction it}
        val identity=createIdentity(studentId);val now=System.currentTimeMillis()
        val request=DeviceReplacementRequestEntity(UUID.randomUUID().toString(),studentId,activeId,identity.publicId,identity.publicKey,identity.secretCiphertext,"PENDING",now,null,null,null,1)
        dao.insertDeviceReplacementRequest(request)
        audit(actorId,"DEVICE_REPLACEMENT_REQUESTED",studentId,null,"{\"requestId\":\"${request.id}\"}","Student requested device replacement",now)
        request
    }

    suspend fun approveReplacement(requestId:String,reviewerId:String,decisionNote:String):StudentDeviceEntity=db.withTransaction{
        val request=dao.getDeviceReplacementRequest(requestId)?:error("REQUEST_NOT_FOUND")
        check(request.status=="PENDING"){"REQUEST_ALREADY_REVIEWED"}
        if(authorization!=null && !authorization.hasScopedPermission(reviewerId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",request.studentId))error("DEVICE_ENROLLMENT_PERMISSION_REQUIRED")
        val student=dao.getStudentById(request.studentId)?:error("STUDENT_NOT_FOUND");check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"};val now=System.currentTimeMillis()
        val activeDevices=dao.getActiveDevicesForStudent(student.id)
        val activeState=DeviceActiveState(activeDevices.map{it.id},student.registeredDeviceId)
        check(DeviceActiveInvariantRules.replacementMatches(activeState,request.oldDeviceId)){"DEVICE_REPLACEMENT_STATE_MISMATCH"}
        val oldDevice=activeDevices.single()
        dao.updateStudentDevice(oldDevice.copy(status=DeviceStatus.REPLACED,version=oldDevice.version+1))
        val device=StudentDeviceEntity(UUID.randomUUID().toString(),student.id,request.newDevicePublicId,request.newPublicKey,request.newPresenceSecretCiphertext,now,null,DeviceStatus.ACTIVE,1)
        dao.insertStudentDevice(device);dao.updateStudent(student.copy(registeredDeviceId=device.id,updatedAt=now,version=student.version+1))
        dao.updateDeviceReplacementRequest(request.copy(status="APPROVED",reviewedBy=reviewerId,reviewedAt=now,decisionNote=decisionNote.trim(),version=request.version+1))
        audit(reviewerId,"DEVICE_REPLACEMENT_APPROVED",student.id,"{\"oldDeviceId\":${request.oldDeviceId?.let{"\"$it\""}?:"null"}}","{\"newDeviceId\":\"${device.id}\"}",decisionNote,now)
        device
    }

    suspend fun rejectReplacement(requestId:String,reviewerId:String,decisionNote:String)=db.withTransaction{
        val request=dao.getDeviceReplacementRequest(requestId)?:error("REQUEST_NOT_FOUND");check(request.status=="PENDING"){"REQUEST_ALREADY_REVIEWED"}
        if(authorization!=null && !authorization.hasScopedPermission(reviewerId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",request.studentId))error("DEVICE_ENROLLMENT_PERMISSION_REQUIRED")
        val now=System.currentTimeMillis();dao.updateDeviceReplacementRequest(request.copy(status="REJECTED",reviewedBy=reviewerId,reviewedAt=now,decisionNote=decisionNote.trim(),version=request.version+1))
        audit(reviewerId,"DEVICE_REPLACEMENT_REJECTED",request.studentId,null,"{\"requestId\":\"${request.id}\"}",decisionNote,now)
    }

    data class PairingOffer(val device:StudentDeviceEntity,val payload:String,val expiresAt:Long)
    private data class PairingState(val status:String,val studentId:String,val publicId:String,val expiresAt:Long)

    suspend fun createPairingOffer(studentId:String,actorId:String,ttlMillis:Long=5*60_000L):PairingOffer=db.withTransaction{
        val student=dao.getStudentById(studentId)?:error("STUDENT_NOT_FOUND")
        check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"}
        if(authorization!=null && !authorization.canAccessStudent(actorId,studentId))error("STUDENT_SCOPE_REQUIRED")
        val active=dao.getActiveDevicesForStudent(studentId)
        check(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(active.map{it.id},student.registeredDeviceId))){"REPLACEMENT_APPROVAL_REQUIRED"}
        val identity=createIdentity(studentId);val now=System.currentTimeMillis();val expires=now+ttlMillis.coerceIn(60_000L,10*60_000L)
        val device=identity.toDeviceForStudent(studentId,now)
        val body=listOf(BleProtocol.PAIRING_PREFIX,expires.toString(),studentId,identity.publicId,b64Url(identity.publicKey),b64Url(identity.secretBase64)).joinToString("|")
        val signature=sign(identity.keyAlias,body.toByteArray(Charsets.UTF_8))
        val payload="$body|${b64Url(Base64.encodeToString(signature,Base64.NO_WRAP))}"
        savePairingState(payload,PairingState("PENDING",studentId,identity.publicId,expires),now)
        audit(actorId,"DEVICE_PAIRING_OFFER_CREATED",studentId,null,"{\"devicePublicId\":\"${identity.publicId.takeLast(8)}\",\"status\":\"PENDING\",\"expiresAt\":$expires}","One-time device pairing",now)
        PairingOffer(device,payload,expires)
    }

    suspend fun acceptPairingOffer(payload:String,reviewerId:String):StudentDeviceEntity=db.withTransaction{
        val parsed=parsePairingOffer(payload);val now=System.currentTimeMillis()
        val state=loadPairingState(payload)?:error("PAIRING_OFFER_NOT_PENDING")
        check(PairingOfferRules.stateMatches(state.studentId,state.publicId,state.expiresAt,parsed.studentId,parsed.publicId,parsed.expiresAt)){"PAIRING_STATE_MISMATCH"}
        if(PairingOfferRules.isExpired(state.expiresAt,now))error("PAIRING_PAYLOAD_EXPIRED")
        if(authorization!=null && !authorization.hasScopedPermission(reviewerId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",parsed.studentId))error("DEVICE_ENROLLMENT_PERMISSION_REQUIRED")
        val student=dao.getStudentById(parsed.studentId)?:error("STUDENT_NOT_FOUND")
        check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"}
        check(state.status=="PENDING"){"PAIRING_OFFER_NOT_PENDING"}
        val active=dao.getActiveDevicesForStudent(parsed.studentId);check(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(active.map{it.id},student.registeredDeviceId))){"ACTIVE_DEVICE_EXISTS"}
        val device=StudentDeviceEntity(UUID.randomUUID().toString(),parsed.studentId,parsed.publicId,parsed.publicKey,cipher.encrypt(parsed.secretB64),now,null,DeviceStatus.ACTIVE,1)
        dao.insertStudentDevice(device);dao.updateStudent(student.copy(registeredDeviceId=device.id,updatedAt=now,version=student.version+1))
        savePairingState(payload,state.copy(status="ACCEPTED"),now)
        audit(reviewerId,"DEVICE_PAIRING_ACCEPTED",parsed.studentId,null,"{\"deviceId\":\"${device.id}\",\"status\":\"ACTIVE\"}","One-time pairing accepted",now);device
    }

    suspend fun rejectPairingOffer(payload:String,reviewerId:String){db.withTransaction{
        val parsed=parsePairingOffer(payload);val now=System.currentTimeMillis()
        val state=loadPairingState(payload)?:error("PAIRING_OFFER_NOT_PENDING")
        check(PairingOfferRules.stateMatches(state.studentId,state.publicId,state.expiresAt,parsed.studentId,parsed.publicId,parsed.expiresAt)){"PAIRING_STATE_MISMATCH"}
        if(PairingOfferRules.isExpired(state.expiresAt,now))error("PAIRING_PAYLOAD_EXPIRED")
        if(authorization!=null && !authorization.hasScopedPermission(reviewerId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",parsed.studentId))error("DEVICE_ENROLLMENT_PERMISSION_REQUIRED")
        dao.getStudentById(parsed.studentId)?:error("STUDENT_NOT_FOUND");check(state.status=="PENDING"){"PAIRING_OFFER_NOT_PENDING"}
        savePairingState(payload,state.copy(status="REJECTED"),now)
        audit(reviewerId,"DEVICE_PAIRING_REJECTED",parsed.studentId,null,"{\"devicePublicId\":\"${parsed.publicId.takeLast(8)}\",\"status\":\"REJECTED\"}","One-time pairing rejected",now)
    }}

    private data class ParsedPairingOffer(val studentId:String,val publicId:String,val publicKey:String,val secretB64:String,val expiresAt:Long)
    private fun parsePairingOffer(payload:String):ParsedPairingOffer=try{
        val parts=payload.split("|");require(parts.size==7 && parts[0]==BleProtocol.PAIRING_PREFIX){"PAIRING_PAYLOAD_INVALID"}
        val expires=parts[1].toLongOrNull()?:error("PAIRING_PAYLOAD_INVALID")
        val publicKey=unB64Url(parts[4]);val signature=Base64.decode(unB64Url(parts[6]),Base64.NO_WRAP);val body=parts.take(6).joinToString("|")
        check(verify(publicKey,body.toByteArray(Charsets.UTF_8),signature)){"PAIRING_SIGNATURE_INVALID"}
        ParsedPairingOffer(parts[2],parts[3],publicKey,unB64Url(parts[5]),expires)
    }catch(e:Exception){
        if(e.message in setOf("PAIRING_PAYLOAD_INVALID","PAIRING_SIGNATURE_INVALID"))throw e
        throw IllegalArgumentException("PAIRING_PAYLOAD_INVALID")
    }
    private fun pairingKey(payload:String):String{
        val digest=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        return "device.pairing.$digest"
    }
    private suspend fun savePairingState(payload:String,state:PairingState,now:Long){
        val plain=listOf(state.status,state.studentId,state.publicId,state.expiresAt.toString()).joinToString("|")
        dao.upsertSetting(AppSettingEntity(pairingKey(payload),cipher.encrypt(plain),now))
    }
    private suspend fun loadPairingState(payload:String):PairingState?{
        val plain=dao.getSetting(pairingKey(payload))?.valueCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}?:return null
        val parts=plain.split("|");if(parts.size!=4)return null
        return PairingState(parts[0],parts[1],parts[2],parts[3].toLongOrNull()?:return null)
    }

    suspend fun setStatus(deviceId:String,status:DeviceStatus,actorId:String){db.withTransaction{
        val device=dao.getStudentDevice(deviceId)?:error("DEVICE_NOT_FOUND")
        if(authorization!=null && !authorization.hasScopedPermission(actorId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",device.studentId))error("DEVICE_ENROLLMENT_PERMISSION_REQUIRED")
        val now=System.currentTimeMillis();val student=dao.getStudentById(device.studentId)?:error("STUDENT_NOT_FOUND")
        if(status==DeviceStatus.ACTIVE){
            check(student.status==StudentStatus.ACTIVE){"STUDENT_NOT_ACTIVE"}
            check(dao.getActiveDevicesForStudent(device.studentId).none{it.id!=deviceId}){"ACTIVE_DEVICE_EXISTS"}
        }
        dao.updateStudentDevice(device.copy(status=status,version=device.version+1))
        if(status==DeviceStatus.ACTIVE && student.registeredDeviceId!=deviceId)dao.updateStudent(student.copy(registeredDeviceId=deviceId,updatedAt=now,version=student.version+1))
        else if(student.registeredDeviceId==deviceId && status!=DeviceStatus.ACTIVE)dao.updateStudent(student.copy(registeredDeviceId=null,updatedAt=now,version=student.version+1))
        audit(actorId,"DEVICE_CHANGED",device.studentId,"{\"status\":\"${device.status}\"}","{\"status\":\"$status\"}","Device status workflow",now)
    }}

    suspend fun rotatingToken(deviceId:String,nowMillis:Long=System.currentTimeMillis()):String?{
        val device=dao.getStudentDevice(deviceId)?.takeIf{it.status==DeviceStatus.ACTIVE}?:return null
        val student=dao.getStudentById(device.studentId)?.takeIf{it.status==StudentStatus.ACTIVE && it.registeredDeviceId==device.id}?:return null
        val encoded=device.presenceSecretCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}?:return null
        val secret=runCatching{Base64.decode(encoded,Base64.NO_WRAP)}.getOrNull()?:return null
        return RotatingPresenceToken.token(secret,device.devicePublicId,nowMillis)
    }

    private data class PendingIdentity(val publicId:String,val publicKey:String,val secretCiphertext:String,val secretBase64:String,val keyAlias:String)
    private fun createIdentity(studentId:String):PendingIdentity{
        val publicId=UUID.randomUUID().toString();val identityId=UUID.randomUUID().toString();val signing=generateSigningIdentity(identityId)
        val secret=ByteArray(32).also{SecureRandom().nextBytes(it)};val secretB64=Base64.encodeToString(secret,Base64.NO_WRAP);val encrypted=cipher.encrypt(secretB64)
        return PendingIdentity(publicId,signing.second,encrypted,secretB64,signing.first)
    }
    private fun PendingIdentity.toDeviceForStudent(studentId:String,now:Long)=StudentDeviceEntity(UUID.randomUUID().toString(),studentId,publicId,publicKey,secretCiphertext,now,null,DeviceStatus.ACTIVE,1)

    private suspend fun audit(actorId:String,action:String,studentId:String,oldData:String?,newData:String?,reason:String?,now:Long){
        val role=authorization?.roleNames(actorId)?.firstOrNull();dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,action,"Student",studentId,oldData,newData,reason,now,role))
    }

    private fun b64Url(value:String)=Base64.encodeToString(value.toByteArray(Charsets.UTF_8),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unB64Url(value:String)=String(Base64.decode(value,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8)
    private fun sign(alias:String,data:ByteArray):ByteArray{val keyStore=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val privateKey=keyStore.getKey(alias,null) as java.security.PrivateKey;return Signature.getInstance("SHA256withECDSA").run{initSign(privateKey);update(data);sign()}}
    private fun verify(publicKeyB64:String,data:ByteArray,signature:ByteArray):Boolean=runCatching{val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64,Base64.NO_WRAP)));Signature.getInstance("SHA256withECDSA").run{initVerify(key);update(data);verify(signature)}}.getOrDefault(false)

    private fun generateSigningIdentity(identityId:String):Pair<String,String>{
        val alias="hammam_device_${identityId.replace("-","")}";val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        val spec=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build()
        generator.initialize(spec);val pair=generator.generateKeyPair();return alias to Base64.encodeToString(pair.public.encoded,Base64.NO_WRAP)
    }
}

internal object PairingOfferRules {
    fun isExpired(expiresAt:Long,now:Long)=now>expiresAt
    fun canAccept(status:String,expiresAt:Long,now:Long)=status=="PENDING" && !isExpired(expiresAt,now)
    fun stateMatches(storedStudentId:String,storedPublicId:String,storedExpiresAt:Long,payloadStudentId:String,payloadPublicId:String,payloadExpiresAt:Long):Boolean=
        storedStudentId==payloadStudentId&&storedPublicId==payloadPublicId&&storedExpiresAt==payloadExpiresAt
}
