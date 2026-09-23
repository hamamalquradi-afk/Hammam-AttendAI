package com.hammam.attendai.backup

import org.junit.Assert.*
import org.junit.Test

class ConfigurationBackupFormatTest {
    @Test fun headerRemainsVersionOne(){
        assertEquals("HAMMAM_CONFIG_V1",ConfigurationBackupFormat.HEADER)
        assertNull(ConfigurationBackupFormat.headerIssue(listOf(ConfigurationBackupFormat.HEADER,"UI|x|y")))
    }

    @Test fun invalidHeaderIsRejected(){
        assertEquals("INVALID_CONFIG_FORMAT",ConfigurationBackupFormat.headerIssue(listOf("HAMMAM_CONFIG_V2")))
        assertEquals("INVALID_CONFIG_FORMAT",ConfigurationBackupFormat.headerIssue(emptyList()))
    }

    @Test fun secretBearingSectionsAreNotExportableSections(){
        assertTrue(ConfigurationBackupFormat.allowedSections.intersect(ConfigurationBackupFormat.forbiddenSecretSections).isEmpty())
        listOf("USERS","STUDENTS","ATTENDANCE","AI_KEY","PROVIDER_SECRET","BACKEND_CREDENTIAL","PIN","KEYSTORE").forEach{
            assertFalse(ConfigurationBackupFormat.isAllowedSection(it))
        }
    }

    @Test fun knownUrlSafeVectorMatchesLegacyFormat(){
        assertEquals("SGFtbWFtIEF0dGVuZEFJ",ConfigurationBackupFormat.encode("Hammam AttendAI"))
        assertEquals("2LnYsdio2Yo",ConfigurationBackupFormat.encode("عربي"))
    }

    @Test fun encodingRoundTripsDeterministically(){
        val value="العربية | English | 100%"
        val first=ConfigurationBackupFormat.encode(value)
        val second=ConfigurationBackupFormat.encode(value)
        assertEquals(first,second)
        assertEquals(value,ConfigurationBackupFormat.decode(first))
    }
}
