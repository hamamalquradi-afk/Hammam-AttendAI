package com.hammam.attendai

import com.hammam.attendai.ble.*
import org.junit.Assert.*
import org.junit.Test

class BleRuntimePolicyTest {
    @Test fun api30ScanRequiresFineLocationOnly(){
        assertEquals(setOf(BlePermissionKind.FINE_LOCATION),BlePermissionPolicy.required(30,BleOperation.SCAN))
        assertTrue(BlePermissionPolicy.required(30,BleOperation.ADVERTISE).isEmpty())
    }

    @Test fun api31UsesNearbyDevicePermissionsWithoutLocation(){
        assertEquals(setOf(BlePermissionKind.SCAN,BlePermissionKind.CONNECT),BlePermissionPolicy.required(31,BleOperation.SCAN))
        assertEquals(setOf(BlePermissionKind.ADVERTISE,BlePermissionKind.CONNECT),BlePermissionPolicy.required(31,BleOperation.ADVERTISE))
        assertFalse(BlePermissionKind.FINE_LOCATION in BlePermissionPolicy.required(31,BleOperation.SCAN))
    }

    @Test fun notificationPermissionIsSeparateFromBluetooth(){
        assertFalse(BlePermissionPolicy.notificationRuntimePermissionRequired(32))
        assertTrue(BlePermissionPolicy.notificationRuntimePermissionRequired(33))
        assertFalse(BlePermissionPolicy.required(33,BleOperation.SCAN).any{it.name.contains("NOTIFICATION")})
    }

    @Test fun presenceServiceContextRequiresAllIdentifiers(){
        assertTrue(PresenceStartContextRules.valid("student","device","lecture"))
        assertFalse(PresenceStartContextRules.valid(null,"device","lecture"))
        assertFalse(PresenceStartContextRules.valid("student","","lecture"))
        assertFalse(PresenceStartContextRules.valid("student","device","   "))
    }

    @Test fun staleOrDisabledStudentSessionFailsClosed(){
        assertFalse(StudentDeviceSessionRules.usable(true,false,"Student",true,true))
        assertFalse(StudentDeviceSessionRules.usable(true,true,"Representative",true,true))
        assertFalse(StudentDeviceSessionRules.usable(true,true,"Student",false,true))
        assertFalse(StudentDeviceSessionRules.usable(true,true,"Student",true,false))
        assertTrue(StudentDeviceSessionRules.usable(true,true,"Student",true,true))
    }

    @Test fun activeDeviceInvariantRequiresExactlyOneRegisteredActiveDevice(){
        assertEquals("d1",DeviceActiveInvariantRules.activeDeviceIdOrNull(DeviceActiveState(listOf("d1"),"d1")))
        assertNull(DeviceActiveInvariantRules.activeDeviceIdOrNull(DeviceActiveState(listOf("d1","d2"),"d1")))
        assertNull(DeviceActiveInvariantRules.activeDeviceIdOrNull(DeviceActiveState(listOf("d1"),"other")))
        assertTrue(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(emptyList(),null)))
        assertFalse(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(emptyList(),"stale")))
        assertFalse(DeviceActiveInvariantRules.canInitialEnroll(DeviceActiveState(listOf("d1"),"d1")))
    }

    @Test fun replacementRequestMustMatchCurrentRegisteredActiveDevice(){
        val valid=DeviceActiveState(listOf("old"),"old")
        assertTrue(DeviceActiveInvariantRules.replacementMatches(valid,"old"))
        assertFalse(DeviceActiveInvariantRules.replacementMatches(valid,"other"))
        assertFalse(DeviceActiveInvariantRules.replacementMatches(DeviceActiveState(listOf("a","b"),"a"),"a"))
    }
}
