package com.hammam.attendai.security

import org.junit.Assert.*
import org.junit.Test

class LocalAccessRulesTest {
    @Test fun missingDeletedAndDisabledUsersFailClosed(){
        assertEquals(LocalSessionStatus.MISSING_USER,LocalAccessRules.resolve(null,false,false,emptyList()).status)
        assertEquals(LocalSessionStatus.MISSING_USER,LocalAccessRules.resolve("u",false,false,emptyList()).status)
        assertEquals(LocalSessionStatus.DISABLED_USER,LocalAccessRules.resolve("u",true,false,listOf("Administrator")).status)
        assertNull(LocalAccessRules.resolve("u",true,false,listOf("Administrator")).activeUserId)
    }

    @Test fun rolePriorityMakesSystemOwnerAuthoritativeWhenUserHasUpgradeRoleToo(){
        val s=LocalAccessRules.resolve("u",true,true,listOf("Administrator","SYSTEM_OWNER"))
        assertEquals(LocalSessionStatus.READY,s.status)
        assertEquals("SYSTEM_OWNER",s.primaryRole)
    }

    @Test fun emptyAdminPermissionsNeverFallBackToData(){
        assertTrue(LocalAccessRules.allowedManagementSections(emptySet()).isEmpty())
        assertFalse(LocalAccessRules.hasManagementAccess(emptySet()))
        assertEquals(listOf(ManagementSectionKey.AI),LocalAccessRules.allowedManagementSections(setOf("MANAGE_AI_PROVIDER")))
    }

    @Test fun appLockConfigurationKeepsExistingRolePolicy(){
        assertTrue(LocalAccessRules.canConfigureAppLock("SYSTEM_OWNER"))
        assertTrue(LocalAccessRules.canConfigureAppLock("Administrator"))
        assertTrue(LocalAccessRules.canConfigureAppLock("Representative"))
        assertFalse(LocalAccessRules.canConfigureAppLock("Student"))
    }

    @Test fun firstRunBootstrapCannotDuplicateAnInitializedDatabase(){
        assertTrue(FirstRunBootstrapRules.canCreateBootstrapOwner(0,false))
        assertFalse(FirstRunBootstrapRules.canCreateBootstrapOwner(1,false))
        assertFalse(FirstRunBootstrapRules.canCreateBootstrapOwner(0,true))
        assertTrue(FirstRunBootstrapRules.mayOfferAdministratorRecovery(2,false,true))
        assertFalse(FirstRunBootstrapRules.mayOfferAdministratorRecovery(2,true,true))
    }

    @Test fun rawExceptionTextIsNotAcceptedAsUiCode(){
        assertEquals("LOCAL_SESSION_FAILED",LocalAccessRules.safeErrorCode("socket failed at 10.0.0.1","LOCAL_SESSION_FAILED"))
        assertEquals("USER_DISABLED",LocalAccessRules.safeErrorCode("USER_DISABLED","LOCAL_SESSION_FAILED"))
    }

    @Test fun selectedManagementSectionIsRevalidatedAfterPermissionChange(){
        assertEquals(ManagementSectionKey.AI,LocalAccessRules.revalidateManagementSection(ManagementSectionKey.DATA,setOf("MANAGE_AI_PROVIDER")))
        assertNull(LocalAccessRules.revalidateManagementSection(ManagementSectionKey.DATA,emptySet()))
    }

    @Test fun appLockPinRuleRequiresFourToTwelveDigits(){
        assertTrue(LocalAccessRules.validPin("1234"))
        assertTrue(LocalAccessRules.validPin("123456789012"))
        assertFalse(LocalAccessRules.validPin("123"))
        assertFalse(LocalAccessRules.validPin("1234567890123"))
        assertFalse(LocalAccessRules.validPin("12a4"))
    }
}
