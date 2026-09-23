package com.hammam.attendai.ble

enum class BleOperation { SCAN, ADVERTISE }
enum class BlePermissionKind { FINE_LOCATION, SCAN, CONNECT, ADVERTISE }

/** Pure API-level permission policy. Notification permission is intentionally separate from BLE authorization. */
object BlePermissionPolicy {
    fun required(apiLevel:Int,operation:BleOperation):Set<BlePermissionKind> = when {
        apiLevel>=31 && operation==BleOperation.SCAN -> setOf(BlePermissionKind.SCAN,BlePermissionKind.CONNECT)
        apiLevel>=31 && operation==BleOperation.ADVERTISE -> setOf(BlePermissionKind.ADVERTISE,BlePermissionKind.CONNECT)
        apiLevel in 24..30 && operation==BleOperation.SCAN -> setOf(BlePermissionKind.FINE_LOCATION)
        else -> emptySet()
    }
    fun notificationRuntimePermissionRequired(apiLevel:Int):Boolean=apiLevel>=33
}

object BleProtocol {
    const val SERVICE_UUID="86c90000-7b29-4b4a-9f19-bd856f5c12b1"
    const val ROTATING_TOKEN_HEX_LENGTH=16
    const val ROTATING_TOKEN_BYTES=8
    const val PAIRING_PREFIX="HAP1"
    const val ATTENDANCE_QR_PREFIX="HAD1|"
}

data class DeviceActiveState(
    val activeDeviceIds:List<String>,
    val registeredDeviceId:String?,
)

/** Fail-closed consistency rules used by enrollment and replacement transactions. */
object DeviceActiveInvariantRules {
    fun activeDeviceIdOrNull(state:DeviceActiveState):String? =
        state.activeDeviceIds.singleOrNull()?.takeIf{it==state.registeredDeviceId}

    fun canInitialEnroll(state:DeviceActiveState):Boolean=state.activeDeviceIds.isEmpty() && state.registeredDeviceId==null

    fun replacementMatches(state:DeviceActiveState,oldDeviceId:String?):Boolean =
        activeDeviceIdOrNull(state)?.let{it==oldDeviceId}==true
}

object StudentDeviceSessionRules {
    fun usable(userExists:Boolean,userActive:Boolean,primaryRole:String?,studentLinked:Boolean,studentActive:Boolean):Boolean=
        userExists&&userActive&&primaryRole=="Student"&&studentLinked&&studentActive
}
data class PresenceStartContext(val studentId:String,val deviceId:String,val lectureId:String)

object PresenceStartContextRules {
    fun resolve(studentId:String?,deviceId:String?,lectureId:String?):PresenceStartContext? {
        if(studentId.isNullOrBlank() || deviceId.isNullOrBlank() || lectureId.isNullOrBlank())return null
        return PresenceStartContext(studentId,deviceId,lectureId)
    }
    fun valid(studentId:String?,deviceId:String?,lectureId:String?):Boolean = resolve(studentId,deviceId,lectureId)!=null
}
