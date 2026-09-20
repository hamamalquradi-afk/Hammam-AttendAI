package com.hammam.attendai.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BlePresenceDetector(private val context:Context):PresenceDetector {
    private val _obs=MutableSharedFlow<PresenceObservation>(extraBufferCapacity=128)
    override val observations=_obs.asSharedFlow()
    private val _state=MutableStateFlow<DetectorState>(DetectorState.Idle)
    override val state=_state.asStateFlow()
    private var scanner:BluetoothLeScanner?=null
    private var callback:ScanCallback?=null
    private val lifecycleMutex=Mutex()
    private val serviceUuid=UUID.fromString("86c90000-7b29-4b4a-9f19-bd856f5c12b1")

    @SuppressLint("MissingPermission")
    override suspend fun start(sessionId:String)=lifecycleMutex.withLock{
        if(!hasPermission()){_state.value=DetectorState.Error("PERMISSION_REQUIRED");return@withLock}
        try{
            val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if(adapter==null){_state.value=DetectorState.Error("BLE_UNAVAILABLE");return@withLock}
            if(!adapter.isEnabled){_state.value=DetectorState.Error("BLUETOOTH_DISABLED");return@withLock}
            val nextScanner=adapter.bluetoothLeScanner ?: run{_state.value=DetectorState.Error("SCANNER_UNAVAILABLE");return@withLock}
            val previousCallback=callback
            val previousScanner=scanner
            callback=null;scanner=null
            runCatching{previousCallback?.let{previousScanner?.stopScan(it)}}
            scanner=nextScanner
            _state.value=DetectorState.Starting
            val newCallback=object:ScanCallback(){
                override fun onScanResult(callbackType:Int,result:ScanResult){
                    if(callback!==this)return
                    val bytes=result.scanRecord?.serviceData?.entries?.firstOrNull{it.key.uuid==serviceUuid}?.value ?: return
                    if(bytes.size!=8)return
                    val token=bytes.joinToString(""){"%02x".format(it.toInt() and 0xff)}
                    if(!_obs.tryEmit(PresenceObservation(token,result.rssi,System.currentTimeMillis())))
                        android.util.Log.w("BlePresenceDetector","BLE_OBSERVATION_DROPPED")
                }
                override fun onScanFailed(errorCode:Int){if(callback===this)_state.value=DetectorState.Error("SCAN_$errorCode")}
            }
            callback=newCallback
            val filter=ScanFilter.Builder().setServiceData(android.os.ParcelUuid(serviceUuid),byteArrayOf()).build()
            val settings=ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(0).build()
            scanner?.startScan(listOf(filter),settings,callback)
            _state.value=DetectorState.Active
        }catch(_:SecurityException){
            callback=null;scanner=null;_state.value=DetectorState.Error("PERMISSION_REVOKED")
        }catch(_:IllegalStateException){
            callback=null;scanner=null;_state.value=DetectorState.Error("SCANNER_STATE_ERROR")
        }catch(e:Exception){
            runCatching{callback?.let{scanner?.stopScan(it)}}
            callback=null;scanner=null;_state.value=DetectorState.Error(e.message?.takeIf{it.isNotBlank()}?:"SCANNER_RUNTIME_ERROR")
        }
    }
    @SuppressLint("MissingPermission")
    override suspend fun stop()=lifecycleMutex.withLock{
        val ownedCallback=callback
        val ownedScanner=scanner
        callback=null;scanner=null
        runCatching{ownedCallback?.let{ownedScanner?.stopScan(it)}}
        _state.value=DetectorState.Idle
    }
    private fun hasPermission():Boolean = if(Build.VERSION.SDK_INT>=31)
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
}
