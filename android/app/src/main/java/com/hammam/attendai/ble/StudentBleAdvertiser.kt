package com.hammam.attendai.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

class StudentBleAdvertiser(private val context:Context) {
    private val serviceUuid=ParcelUuid(UUID.fromString("86c90000-7b29-4b4a-9f19-bd856f5c12b1"))
    private var callback:AdvertiseCallback?=null
    @SuppressLint("MissingPermission")
    fun start(rotatingToken:String):Boolean{
        if(Build.VERSION.SDK_INT>=31 && ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)return false
        return runCatching{
            val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return@runCatching false
            val advertiser=adapter.bluetoothLeAdvertiser ?: return@runCatching false
            stop()
            val cb=object:AdvertiseCallback(){}
            val settings=AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(false).setTimeout(0).build()
            val data=AdvertiseData.Builder().addServiceUuid(serviceUuid).addServiceData(serviceUuid,rotatingToken.take(16).toByteArray()).setIncludeDeviceName(false).build()
            advertiser.startAdvertising(settings,data,cb); callback=cb; true
        }.getOrDefault(false)
    }
    @SuppressLint("MissingPermission")
    fun stop(){
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val cb=callback ?: return
        if(Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED) runCatching{adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)}
        callback=null
    }
}
