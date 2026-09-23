package com.hammam.attendai.backup

import org.junit.Assert.*
import org.junit.Test

class BackupValidationRulesTest {
    @Test fun validOlderDatabaseMayBeStagedWithoutClaimingMigration(){
        assertNull(BackupValidationRules.validateVersion(3,3,5))
    }

    @Test fun declaredVersionMustMatchActualSqliteVersion(){
        assertEquals("BACKUP_DATABASE_VERSION_MISMATCH",BackupValidationRules.validateVersion(4,3,5))
    }

    @Test fun unsupportedNewerDatabaseFailsClosed(){
        assertEquals("UNSUPPORTED_DATABASE_VERSION",BackupValidationRules.validateVersion(6,6,5))
    }

    @Test fun unknownExceptionTextIsNotSurfaced(){
        assertEquals("BACKUP_OPERATION_FAILED",BackupValidationRules.safeError(IllegalStateException("/data/user/0/private/path")))
    }
}
