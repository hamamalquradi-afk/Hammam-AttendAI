package com.hammam.attendai.sync

import org.junit.Assert.*
import org.junit.Test

class LocalSyncIdentityRulesTest {
    @Test fun bindingRejectsCrossWorkspaceReplay(){
        val binding=LocalSyncIdentity("acct-a","workspace-a")
        assertNull(LocalSyncIdentityRules.mismatch(binding,"acct-a","workspace-a"))
        assertEquals(LocalSyncIdentityRules.WORKSPACE_MISMATCH,LocalSyncIdentityRules.mismatch(binding,"acct-a","workspace-b"))
        assertEquals(LocalSyncIdentityRules.ACCOUNT_MISMATCH,LocalSyncIdentityRules.mismatch(binding,"acct-b","workspace-a"))
    }

    @Test fun renewalNeverSilentlySwitchesBoundWorkspace(){
        val binding=LocalSyncIdentity("acct-a","workspace-a")
        assertEquals("workspace-a",LocalSyncIdentityRules.selectWorkspace(binding,"acct-a","workspace-b",listOf("workspace-a","workspace-b")).workspaceId)
        assertEquals(LocalSyncIdentityRules.MEMBERSHIP_MISMATCH,LocalSyncIdentityRules.selectWorkspace(binding,"acct-a","workspace-b",listOf("workspace-b")).error)
        assertEquals(LocalSyncIdentityRules.ACCOUNT_MISMATCH,LocalSyncIdentityRules.selectWorkspace(binding,"acct-b","workspace-a",listOf("workspace-a")).error)
    }

    @Test fun existingWorkspaceHistoryFailsClosedWhenAmbiguous(){
        assertEquals("workspace-a",LocalSyncIdentityRules.historicalWorkspace(setOf("workspace-a")).workspaceId)
        assertNull(LocalSyncIdentityRules.historicalWorkspace(emptySet()).error)
        assertEquals(LocalSyncIdentityRules.HISTORY_AMBIGUOUS,LocalSyncIdentityRules.historicalWorkspace(setOf("workspace-a","workspace-b")).error)
    }

    @Test fun unboundIdentityRequiresExplicitChoiceWhenMembershipIsAmbiguous(){
        assertEquals("workspace-a",LocalSyncIdentityRules.selectWorkspace(null,"acct-a","workspace-a",listOf("workspace-a","workspace-b")).workspaceId)
        assertEquals("workspace-a",LocalSyncIdentityRules.selectWorkspace(null,"acct-a",null,listOf("workspace-a")).workspaceId)
        assertEquals(LocalSyncIdentityRules.SELECTION_REQUIRED,LocalSyncIdentityRules.selectWorkspace(null,"acct-a",null,listOf("workspace-a","workspace-b")).error)
        assertEquals("MEMBERSHIP_REQUIRED",LocalSyncIdentityRules.selectWorkspace(null,"acct-a",null,emptyList()).error)
    }
}
