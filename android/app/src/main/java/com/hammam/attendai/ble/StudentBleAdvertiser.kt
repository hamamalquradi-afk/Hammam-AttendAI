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
    fun start(rotatingToken:String,onFailure:(Int)->Unit={}):Boolean{
        if(Build.VERSION.SDK_INT>=31 && ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)return false
        val tokenBytes=decodeToken(rotatingToken)?:return false
        return runCatching{
            val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return@runCatching false
            val advertiser=adapter.bluetoothLeAdvertiser ?: return@runCatching false
            stop()
            val cb=object:AdvertiseCallback(){override fun onStartFailure(errorCode:Int){callback=null;onFailure(errorCode)}}
            val settings=AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(false).setTimeout(0).build()
            val data=AdvertiseData.Builder().addServiceData(serviceUuid,tokenBytes).setIncludeDeviceName(false).build()
            callback=cb;advertiser.startAdvertising(settings,data,cb);true
        }.getOrElse{callback=null;false}
    }
    private fun decodeToken(token:String):ByteArray?{
        if(token.length!=16 || !token.all{it.isDigit()||it.lowercaseChar() in 'a'..'f'})return null
        return ByteArray(8){i->token.substring(i*2,i*2+2).toInt(16).toByte()}
    }
    @SuppressLint("MissingPermission")
    fun stop(){
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val cb=callback ?: return
        if(Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED) runCatching{adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)}
        callback=null
    }
}
