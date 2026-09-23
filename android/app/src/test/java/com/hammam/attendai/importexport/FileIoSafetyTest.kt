package com.hammam.attendai.importexport

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FileIoSafetyTest {
    @Test fun cancellationIsNotAnError(){
        assertFalse(FileIoSafety.isError(FileIoFailure.CANCELLED))
        assertFalse(FileIoSafety.decision(FileIoFailure.CANCELLED).tryFallback)
    }

    @Test fun unavailablePickerRequestsSafeFallback(){
        val d=FileIoSafety.decision(FileIoSafety.fromExceptionClass("ActivityNotFoundException"))
        assertTrue(d.tryFallback)
        assertEquals("FILE_PICKER_UNAVAILABLE",d.messageCode)
    }

    @Test fun vendorPlatformFailureIsControlled(){
        val d=FileIoSafety.decision(FileIoSafety.fromExceptionClass("VendorPickerRuntimeException"))
        assertTrue(d.tryFallback)
        assertEquals("FILE_PICKER_PLATFORM_FAILURE",d.messageCode)
    }

    @Test fun nullOutputStreamBecomesControlledIOException(){
        try{
            FileIoSafety.requireOpened<Any>(null,"FILE_OUTPUT_STREAM_UNAVAILABLE")
            fail("Expected IOException")
        }catch(e:IOException){
            assertEquals("FILE_OUTPUT_STREAM_UNAVAILABLE",e.message)
        }
    }

    @Test fun unreadableUriMapsToControlledFailure(){
        val d=FileIoSafety.decision(FileIoFailure.UNREADABLE_URI)
        assertFalse(d.tryFallback)
        assertEquals("FILE_URI_UNREADABLE",d.messageCode)
    }
}
