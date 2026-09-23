package com.hammam.attendai

import com.hammam.attendai.ble.DynamicQrPresencePayload
import com.hammam.attendai.ble.RotatingPresenceToken
import com.hammam.attendai.data.repository.PairingOfferRules
import com.hammam.attendai.data.repository.PresenceAdmissionRules
import org.junit.Assert.*
import org.junit.Test

class BleQrPairingIntegrityTest {
    @Test fun expiredPairingOfferCannotBeAccepted(){ assertFalse(PairingOfferRules.canAccept("PENDING",999,1000)) }
    @Test fun usedPairingOfferCannotBeAccepted(){ assertFalse(PairingOfferRules.canAccept("ACCEPTED",2000,1000));assertFalse(PairingOfferRules.canAccept("REJECTED",2000,1000)) }
    @Test fun pairingStateMismatchIsRejected(){ assertFalse(PairingOfferRules.stateMatches("s1","p1",2000,"s1","p2",2000));assertFalse(PairingOfferRules.stateMatches("s1","p1",2000,"s1","p1",3000));assertTrue(PairingOfferRules.stateMatches("s1","p1",2000,"s1","p1",2000)) }
    @Test fun rawTokenIsRejectedByQrCodec(){ assertNull(DynamicQrPresencePayload.extractToken("0123456789abcdef")) }
    @Test fun malformedQrPayloadIsRejected(){ assertNull(DynamicQrPresencePayload.extractToken("HAD1|not-a-token"));assertNull(DynamicQrPresencePayload.extractToken("BAD1|0123456789abcdef"));assertNull(DynamicQrPresencePayload.extractToken("HAD1|0123456789ABCDEf")) }
    @Test fun prefixedQrTokenIsAccepted(){ assertEquals("0123456789abcdef",DynamicQrPresencePayload.extractToken("HAD1|0123456789abcdef")) }
    @Test fun deviceStudentMismatchIsRejected(){ assertFalse(PresenceAdmissionRules.allowed(true,true,true,false,true,true)) }
    @Test fun studentOutsideLectureGroupIsRejected(){ assertFalse(PresenceAdmissionRules.allowed(true,false,true,true,true,true)) }
    @Test fun inactiveDeviceIsRejected(){ assertFalse(PresenceAdmissionRules.allowed(true,true,false,true,true,true)) }
    @Test fun bleCannotCreateRecordOutsideOriginalRoster(){ assertFalse(PresenceAdmissionRules.allowed(true,true,true,true,true,false)) }
    @Test fun replacementRequiresRegisteredActiveDevice(){ assertFalse(PresenceAdmissionRules.allowed(true,true,true,true,false,true)) }

    @Test fun rotatingTokenAcceptsOnlyCurrentAdjacentSlots(){
        val secret=ByteArray(32){it.toByte()}; val device="device-1"; val now=300_000L
        assertTrue(RotatingPresenceToken.accepts(secret,device,RotatingPresenceToken.token(secret,device,now),now))
        assertTrue(RotatingPresenceToken.accepts(secret,device,RotatingPresenceToken.token(secret,device,now-30_000L),now))
        assertTrue(RotatingPresenceToken.accepts(secret,device,RotatingPresenceToken.token(secret,device,now+30_000L),now))
        assertFalse(RotatingPresenceToken.accepts(secret,device,RotatingPresenceToken.token(secret,device,now-60_000L),now))
        assertFalse(RotatingPresenceToken.accepts(secret,device,"BADTOKEN",now))
    }
}
