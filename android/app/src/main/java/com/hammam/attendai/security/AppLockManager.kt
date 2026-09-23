package com.hammam.attendai.security

/** Optional local app lock. PIN material is stored encrypted in no-backup storage via SecureSecretStore. */
class AppLockManager(private val store:SecureSecretStore){
    companion object{private const val PIN_HASH="app_lock_pin_hash";private const val BIOMETRIC="app_lock_biometric"}
    fun configured():Boolean=store.configured(PIN_HASH)
    fun biometricEnabled():Boolean=store.get(BIOMETRIC)=="true" && configured()
    fun setPin(pin:CharArray){store.put(PIN_HASH,PinHasher.hash(pin))}
    fun verify(pin:CharArray):Boolean=store.get(PIN_HASH)?.let{PinHasher.verify(pin,it)}==true
    fun clear(){store.put(PIN_HASH,null);store.put(BIOMETRIC,null)}
    fun setBiometric(enabled:Boolean){require(!enabled||configured()){"PIN_REQUIRED_BEFORE_BIOMETRIC"};store.put(BIOMETRIC,enabled.toString())}
}
