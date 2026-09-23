package com.hammam.attendai.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
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
    private val callbackLock=Any()
    private val serviceUuid=UUID.fromString(BleProtocol.SERVICE_UUID)

    @SuppressLint("MissingPermission")
    override suspend fun start(sessionId:String)=lifecycleMutex.withLock{
        val readiness=BlePlatformReadiness.scanError(context)
        if(readiness!=null){_state.value=DetectorState.Error(readiness);return@withLock}
        try{
            val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val nextScanner=manager?.adapter?.bluetoothLeScanner ?: run{_state.value=DetectorState.Error("SCANNER_UNAVAILABLE");return@withLock}
            cleanupOwnedScan()
            _state.value=DetectorState.Starting
            val newCallback=object:ScanCallback(){
                override fun onScanResult(callbackType:Int,result:ScanResult){
                    if(!synchronized(callbackLock){callback===this})return
                    val bytes=result.scanRecord?.serviceData?.entries?.firstOrNull{it.key.uuid==serviceUuid}?.value ?: return
                    if(bytes.size!=BleProtocol.ROTATING_TOKEN_BYTES)return
                    val token=bytes.joinToString(""){"%02x".format(it.toInt() and 0xff)}
                    if(!_obs.tryEmit(PresenceObservation(token,result.rssi,System.currentTimeMillis())))
                        android.util.Log.w("BlePresenceDetector","BLE_OBSERVATION_DROPPED")
                }
                override fun onScanFailed(errorCode:Int){
                    val owned=synchronized(callbackLock){
                        if(callback===this){callback=null;scanner=null;true}else false
                    }
                    if(owned)_state.value=DetectorState.Error("SCAN_$errorCode")
                }
            }
            synchronized(callbackLock){scanner=nextScanner;callback=newCallback}
            val filter=ScanFilter.Builder().setServiceData(android.os.ParcelUuid(serviceUuid),byteArrayOf()).build()
            val settings=ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(0).build()
            nextScanner.startScan(listOf(filter),settings,newCallback)
            _state.value=DetectorState.Active
        }catch(_:SecurityException){
            cleanupOwnedScan();_state.value=DetectorState.Error("PERMISSION_REVOKED")
        }catch(_:IllegalStateException){
            cleanupOwnedScan();_state.value=DetectorState.Error("SCANNER_STATE_ERROR")
        }catch(_:Exception){
            cleanupOwnedScan();_state.value=DetectorState.Error("SCANNER_RUNTIME_ERROR")
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupOwnedScan(){
        val owned=synchronized(callbackLock){
            Pair(scanner,callback).also{scanner=null;callback=null}
        }
        runCatching{owned.second?.let{owned.first?.stopScan(it)}}
    }

    override suspend fun stop()=lifecycleMutex.withLock{
        cleanupOwnedScan()
        _state.value=DetectorState.Idle
    }

    override suspend fun fail(code:String)=lifecycleMutex.withLock{
        cleanupOwnedScan()
        _state.value=DetectorState.Error(code)
    }
}
