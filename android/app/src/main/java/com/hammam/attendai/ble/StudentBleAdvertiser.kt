package com.hammam.attendai.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

class StudentBleAdvertiser(private val context:Context) {
    private val serviceUuid=ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID))
    private val lock=Any()
    private var callback:AdvertiseCallback?=null

    @SuppressLint("MissingPermission")
    fun start(rotatingToken:String,onFailure:(Int)->Unit={}):Boolean{
        if(BlePlatformReadiness.advertiseError(context)!=null)return false
        val tokenBytes=decodeToken(rotatingToken)?:return false
        return runCatching{
            val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return@runCatching false
            val advertiser=manager.adapter?.bluetoothLeAdvertiser ?: return@runCatching false
            stop()
            val cb=object:AdvertiseCallback(){
                override fun onStartFailure(errorCode:Int){
                    val owned=synchronized(lock){
                        if(callback===this){callback=null;true}else false
                    }
                    if(owned)onFailure(errorCode)
                }
            }
            val settings=AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(false).setTimeout(0).build()
            val data=AdvertiseData.Builder().addServiceData(serviceUuid,tokenBytes).setIncludeDeviceName(false).build()
            synchronized(lock){callback=cb}
            advertiser.startAdvertising(settings,data,cb)
            true
        }.getOrElse{synchronized(lock){callback=null};false}
    }

    private fun decodeToken(token:String):ByteArray?{
        if(token.length!=BleProtocol.ROTATING_TOKEN_HEX_LENGTH || !token.all{it in '0'..'9'||it in 'a'..'f'})return null
        return ByteArray(BleProtocol.ROTATING_TOKEN_BYTES){i->token.substring(i*2,i*2+2).toInt(16).toByte()}
    }

    @SuppressLint("MissingPermission")
    fun stop(){
        val cb=synchronized(lock){callback.also{callback=null}} ?: return
        runCatching{
            val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        }
    }
}
