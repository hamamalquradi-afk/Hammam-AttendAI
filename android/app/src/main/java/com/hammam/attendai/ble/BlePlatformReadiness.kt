package com.hammam.attendai.ble

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BlePlatformReadiness {
    fun manifestPermission(kind:BlePermissionKind):String=when(kind){
        BlePermissionKind.FINE_LOCATION->Manifest.permission.ACCESS_FINE_LOCATION
        BlePermissionKind.SCAN->Manifest.permission.BLUETOOTH_SCAN
        BlePermissionKind.CONNECT->Manifest.permission.BLUETOOTH_CONNECT
        BlePermissionKind.ADVERTISE->Manifest.permission.BLUETOOTH_ADVERTISE
    }

    fun missingPermissions(context:Context,operation:BleOperation):List<String> =
        BlePermissionPolicy.required(Build.VERSION.SDK_INT,operation)
            .map(::manifestPermission)
            .filter{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}

    @SuppressLint("MissingPermission")
    fun scanError(context:Context):String?{
        if(missingPermissions(context,BleOperation.SCAN).isNotEmpty())return "BLE_PERMISSION_REQUIRED"
        val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return "BLE_UNAVAILABLE"
        val adapter=runCatching{manager.adapter}.getOrNull() ?: return "BLE_UNAVAILABLE"
        if(!runCatching{adapter.isEnabled}.getOrDefault(false))return "BLUETOOTH_DISABLED"
        return if(runCatching{adapter.bluetoothLeScanner}.getOrNull()==null)"SCANNER_UNAVAILABLE" else null
    }

    @SuppressLint("MissingPermission")
    fun advertiseError(context:Context):String?{
        if(missingPermissions(context,BleOperation.ADVERTISE).isNotEmpty())return "BLE_PERMISSION_REQUIRED"
        val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return "BLE_UNAVAILABLE"
        val adapter=runCatching{manager.adapter}.getOrNull() ?: return "BLE_UNAVAILABLE"
        if(!runCatching{adapter.isEnabled}.getOrDefault(false))return "BLUETOOTH_DISABLED"
        return if(runCatching{adapter.bluetoothLeAdvertiser}.getOrNull()==null)"BLE_ADVERTISE_UNAVAILABLE" else null
    }
}
