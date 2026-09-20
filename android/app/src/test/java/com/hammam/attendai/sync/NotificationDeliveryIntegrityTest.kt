package com.hammam.attendai.sync

import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryIntegrityTest {
    @Test fun missingAndInvalidPhoneRejected(){
        assertNull(NotificationDeliveryRules.normalizePhone(null))
        assertNull(NotificationDeliveryRules.normalizePhone("12-3"))
        assertEquals("+967771234567",NotificationDeliveryRules.normalizePhone("+967 771-234-567"))
    }
    @Test fun invalidEmailRejected(){
        assertNull(NotificationDeliveryRules.validEmail("not-an-email"))
        assertNull(NotificationDeliveryRules.validEmail("a@localhost"))
        assertEquals("teacher@example.com",NotificationDeliveryRules.validEmail(" teacher@example.com "))
    }
    @Test fun onlyExternalChannelsUseRemoteProcessor(){
        assertTrue(NotificationDeliveryRules.externalChannel("WHATSAPP"))
        assertTrue(NotificationDeliveryRules.externalChannel("email"))
        assertFalse(NotificationDeliveryRules.externalChannel("IN_APP"))
    }
    @Test fun retryableAndPermanentProviderFailuresStayDistinct(){
        assertTrue(NotificationDeliveryRules.retryable(BackendResult(false,retryable=true,error="timeout")))
        assertTrue(NotificationDeliveryRules.permanent(BackendResult(false,retryable=false,error="invalid recipient")))
        assertFalse(NotificationDeliveryRules.retryable(BackendResult(true)))
    }
}
