package com.hammam.attendai.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderStateTest {
    @Test fun missingProviderConfigurationWinsOverStoredSuccess(){
        assertEquals(AiProviderStatus.NOT_CONFIGURED,AiProviderStateRules.configurationStatus(false,"model-x",AiProviderStatus.CONNECTED))
    }
    @Test fun missingModelIsDistinctFromMissingCredential(){
        assertEquals(AiProviderStatus.MODEL_REQUIRED,AiProviderStateRules.configurationStatus(true,null,AiProviderStatus.CONNECTED))
    }
    @Test fun modelOrCredentialMutationRequiresRetest(){
        assertEquals(AiProviderStatus.CONFIGURED_NOT_TESTED,AiProviderStateRules.afterConfigurationMutation(true,"model-x"))
    }
    @Test fun providerFailuresRemainUserSafeAndDistinct(){
        assertEquals(AiProviderStatus.AUTH_FAILED,AiProviderStateRules.failureStatus("OPENAI_HTTP_401"))
        assertEquals(AiProviderStatus.RATE_LIMITED,AiProviderStateRules.failureStatus("GEMINI_HTTP_429"))
        assertEquals(AiProviderStatus.NETWORK_UNAVAILABLE,AiProviderStateRules.failureStatus("UnknownHostException"))
        assertEquals(AiProviderStatus.TIMEOUT,AiProviderStateRules.failureStatus("SocketTimeoutException"))
        assertEquals(AiProviderStatus.UNSUPPORTED_MODEL,AiProviderStateRules.failureStatus("UNSUPPORTED_MODEL"))
        assertEquals(AiProviderStatus.PROVIDER_SERVER_FAILURE,AiProviderStateRules.failureStatus("CLAUDE_HTTP_503"))
        assertEquals(AiProviderStatus.CANCELLED,AiProviderStateRules.failureStatus("CancellationException"))
    }
}
