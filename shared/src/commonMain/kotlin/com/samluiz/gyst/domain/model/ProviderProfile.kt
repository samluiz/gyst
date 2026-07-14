package com.samluiz.gyst.domain.model

import kotlin.time.Instant

/**
 * Non-secret provider configuration. API keys are deliberately absent and
 * remain in the platform secure-secret store, keyed by [id].
 */
data class ProviderProfile(
    val id: String,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val apiFormat: String,
    val capabilities: ProviderCapabilities,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant? = null,
)

data class ProviderCapabilities(
    val textGeneration: Boolean = true,
    val visionInput: Boolean = false,
    val structuredOutput: Boolean = false,
    val streaming: Boolean = false,
    val toolCalling: Boolean = false,
) {
    fun supports(required: ProviderCapabilities): Boolean =
        (!required.textGeneration || textGeneration) &&
            (!required.visionInput || visionInput) &&
            (!required.structuredOutput || structuredOutput) &&
            (!required.streaming || streaming) &&
            (!required.toolCalling || toolCalling)
}
