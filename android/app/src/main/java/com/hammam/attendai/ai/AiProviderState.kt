package com.hammam.attendai.ai

object AiProviderStatus {
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val MODEL_REQUIRED = "MODEL_REQUIRED"
    const val CONFIGURED_NOT_TESTED = "CONFIGURED_NOT_TESTED"
    const val CONNECTED = "CONNECTED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val UNSUPPORTED_MODEL = "UNSUPPORTED_MODEL"
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    const val TIMEOUT = "TIMEOUT"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val PROVIDER_SERVER_FAILURE = "PROVIDER_SERVER_FAILURE"
    const val MALFORMED_RESPONSE = "MALFORMED_RESPONSE"
    const val CANCELLED = "CANCELLED"
    const val CONNECTION_FAILED = "CONNECTION_FAILED"
}

object AiProviderStateRules {
    fun configurationStatus(configured:Boolean, selectedModel:String?, storedStatus:String?):String {
        if(!configured) return AiProviderStatus.NOT_CONFIGURED
        if(selectedModel.isNullOrBlank()) return AiProviderStatus.MODEL_REQUIRED
        return storedStatus?.takeIf{it in knownStatuses} ?: AiProviderStatus.CONFIGURED_NOT_TESTED
    }

    fun afterConfigurationMutation(configured:Boolean, selectedModel:String?):String = when {
        !configured -> AiProviderStatus.NOT_CONFIGURED
        selectedModel.isNullOrBlank() -> AiProviderStatus.MODEL_REQUIRED
        else -> AiProviderStatus.CONFIGURED_NOT_TESTED
    }

    fun failureStatus(error:String?):String {
        val v=error.orEmpty().uppercase()
        return when {
            "CANCEL" in v -> AiProviderStatus.CANCELLED
            "SOCKETTIMEOUT" in v || "TIMEOUT" in v || "HTTP_408" in v || "HTTP 408" in v -> AiProviderStatus.TIMEOUT
            "UNKNOWNHOST" in v || "CONNECTEXCEPTION" in v || "NETWORK" in v || "BACKEND NOT CONFIGURED" in v || "HTTPS_REQUIRED" in v -> AiProviderStatus.NETWORK_UNAVAILABLE
            "429" in v || "RATE" in v && "LIMIT" in v -> AiProviderStatus.RATE_LIMITED
            "401" in v || "403" in v || "AUTH" in v || "API_KEY" in v || "CREDENTIAL" in v -> AiProviderStatus.AUTH_FAILED
            ("MODEL" in v && ("UNSUPPORTED" in v || "INVALID" in v || "NOT_FOUND" in v)) || "HTTP_404" in v || Regex("_(400|404)$").containsMatchIn(v) -> AiProviderStatus.UNSUPPORTED_MODEL
            "MALFORMED" in v || "EMPTY_PROVIDER_RESPONSE" in v -> AiProviderStatus.MALFORMED_RESPONSE
            Regex("(?:HTTP[_ ]?|_)(5\\d\\d)").containsMatchIn(v) || "SERVER" in v -> AiProviderStatus.PROVIDER_SERVER_FAILURE
            else -> AiProviderStatus.CONNECTION_FAILED
        }
    }

    val knownStatuses=setOf(
        AiProviderStatus.NOT_CONFIGURED,AiProviderStatus.MODEL_REQUIRED,AiProviderStatus.CONFIGURED_NOT_TESTED,
        AiProviderStatus.CONNECTED,AiProviderStatus.AUTH_FAILED,AiProviderStatus.UNSUPPORTED_MODEL,
        AiProviderStatus.NETWORK_UNAVAILABLE,AiProviderStatus.TIMEOUT,AiProviderStatus.RATE_LIMITED,
        AiProviderStatus.PROVIDER_SERVER_FAILURE,AiProviderStatus.MALFORMED_RESPONSE,AiProviderStatus.CANCELLED,
        AiProviderStatus.CONNECTION_FAILED,
    )
}
