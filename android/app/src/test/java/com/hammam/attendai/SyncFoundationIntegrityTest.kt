package com.hammam.attendai

import com.hammam.attendai.domain.model.QueueStatus
import com.hammam.attendai.sync.BackendAuthRules
import com.hammam.attendai.sync.BackendResult
import com.hammam.attendai.sync.BackendSessionRules
import com.hammam.attendai.sync.BackendSecretKeys
import com.hammam.attendai.sync.SyncIntegrityRules
import org.junit.Assert.*
import org.junit.Test

class SyncFoundationIntegrityTest {
    @Test fun pendingAndFailedAreClaimable(){assertTrue(SyncIntegrityRules.claimable(QueueStatus.PENDING));assertTrue(SyncIntegrityRules.claimable(QueueStatus.FAILED))}
    @Test fun sentAndCancelledAreNotClaimable(){assertFalse(SyncIntegrityRules.claimable(QueueStatus.SENT));assertFalse(SyncIntegrityRules.claimable(QueueStatus.CANCELLED))}
    @Test fun phase5gSupportsAcademicGraphButNotSecurityOrRuntimeEntities(){
        for(type in listOf("Teacher","AttendancePolicy","University","AcademicYear","Faculty","Department","Level","Semester","Batch","Section","Group","Student","Subject","Lecture","AttendanceRecord","AttendanceAppeal"))assertTrue(type,SyncIntegrityRules.supported(type,"UPSERT"))
        for(type in listOf("StudentDevice","DeviceReplacementRequest","AttendanceSession","PresenceInterval","PresenceEvent","User","Role","Permission"))assertFalse(type,SyncIntegrityRules.supported(type,"UPSERT"))
        assertFalse(SyncIntegrityRules.supported("AttendanceAppeal","DELETE"))
    }
    @Test fun backendAckWithoutPersistenceIsNotSuccess(){assertFalse(SyncIntegrityRules.persistenceConfirmed(BackendResult(true,responseBody="""{"ok":true,"accepted":true}""")))}
    @Test fun persistedResponseCanBeConfirmed(){assertTrue(SyncIntegrityRules.persistenceConfirmed(BackendResult(true,responseBody="""{"ok":true,"persisted":true}""")))}
    @Test fun persistenceNotImplementedIsPermanent(){assertTrue(SyncIntegrityRules.permanent(BackendResult(false,retryable=true,error="SYNC_PERSISTENCE_NOT_IMPLEMENTED")))}
    @Test fun poisonedStoredMutationIsPermanent(){assertTrue(SyncIntegrityRules.permanent(BackendResult(false,retryable=true,error="SYNC_STORED_MUTATION_INVALID")))}
    @Test fun serverFailureCanRemainTransient(){assertFalse(SyncIntegrityRules.permanent(BackendResult(false,retryable=true,error="HTTP 503")))}
    @Test fun validWorkspaceIdentityRequiresWorkspaceAndToken(){assertTrue(SyncIntegrityRules.syncIdentityConfigured("workspace-1","token"));assertFalse(SyncIntegrityRules.syncIdentityConfigured(null,"token"));assertFalse(SyncIntegrityRules.syncIdentityConfigured("workspace-1",null));assertFalse(SyncIntegrityRules.syncIdentityConfigured("bad workspace","token"))}
    @Test fun authenticatedBearerIsInjectedOnlyForNonBlankSession(){assertEquals("Bearer session-secret",BackendAuthRules.authorizationHeader(" session-secret "));assertNull(BackendAuthRules.authorizationHeader(null));assertNull(BackendAuthRules.authorizationHeader("   "))}
    @Test fun invalidExpiredAndRevokedSessionsRequireAuthentication(){for(code in listOf("AUTH_REQUIRED","AUTH_INVALID","AUTH_EXPIRED","AUTH_REVOKED","ACCOUNT_NOT_FOUND"))assertTrue(code,BackendAuthRules.authenticationRequired(code));assertFalse(BackendAuthRules.authenticationRequired("MEMBERSHIP_REQUIRED"))}
    @Test fun provisioningRequiredUsesIdentityRecoveryWithoutInfiniteRetry(){assertTrue(BackendAuthRules.provisioningRequired("PROVISIONING_REQUIRED"));assertTrue(BackendAuthRules.identityRecoveryRequired("PROVISIONING_REQUIRED"));assertFalse(BackendAuthRules.identityRecoveryRequired("MEMBERSHIP_REQUIRED"));assertFalse(SyncIntegrityRules.permanent(BackendResult(false,retryable=false,error="PROVISIONING_REQUIRED")))}
    @Test fun authFailuresAreNotPermanentProtocolFailures(){assertFalse(SyncIntegrityRules.permanent(BackendResult(false,retryable=false,error="AUTH_EXPIRED")));assertFalse(SyncIntegrityRules.permanent(BackendResult(false,retryable=false,error="AUTH_REVOKED")))}
    @Test fun sessionReplacementAndLogoutAreExplicit(){assertEquals("new-session",BackendSessionRules.replacementToken(" new-session "));assertNull(BackendSessionRules.replacementToken(" "));assertNull(BackendSessionRules.clearedToken())}
    @Test fun authFailurePreservesOfflineQueueForRetry(){assertEquals(QueueStatus.PENDING,SyncIntegrityRules.queueStatusAfterAuthFailure())}
    @Test fun backendSecretsHaveSeparateSecureStoreKeys(){assertNotEquals(BackendSecretKeys.DEPLOYMENT_CREDENTIAL,BackendSecretKeys.ACCOUNT_CREDENTIAL);assertNotEquals(BackendSecretKeys.ACCOUNT_CREDENTIAL,BackendSecretKeys.SESSION_TOKEN);assertEquals("backend.session.token",BackendSecretKeys.SESSION_TOKEN)}

    @Test fun workspaceValidationHasNoImplicitDevelopmentFallback(){assertTrue(SyncIntegrityRules.validWorkspaceId("development-default"));assertFalse(SyncIntegrityRules.validWorkspaceId(""));assertFalse(SyncIntegrityRules.validWorkspaceId("a"))}
}
