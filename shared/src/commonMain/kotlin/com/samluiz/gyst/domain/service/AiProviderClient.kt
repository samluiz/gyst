package com.samluiz.gyst.domain.service

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class AiCapability {
    TEXT_GENERATION,
    VISION_INPUT,
    STRUCTURED_OUTPUT,
    STREAMING,
    TOOL_CALLING,
}

enum class AiMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class AiMessage(
    val role: AiMessageRole,
    val content: String,
)

data class AiImageInput(
    val sourceId: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class AiStructuredOutputSchema(
    val name: String,
    val jsonSchema: JsonObject,
)

data class AiTokenUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
)

data class AiProviderResponse(
    val content: String,
    val providerMessageId: String? = null,
    val tokenUsage: AiTokenUsage? = null,
)

sealed interface AiStreamEvent {
    data class Started(val providerMessageId: String? = null) : AiStreamEvent

    data class ContentDelta(val content: String) : AiStreamEvent

    data class Completed(
        val providerMessageId: String? = null,
        val tokenUsage: AiTokenUsage? = null,
    ) : AiStreamEvent
}

enum class AiProviderFailureCode {
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    UNSUPPORTED_CAPABILITY,
    CANCELLED,
    REQUEST_FAILED,
}

class AiProviderException(
    val code: AiProviderFailureCode,
    val retryAfterSeconds: Long? = null,
    val httpStatusCode: Int? = null,
    val providerErrorCode: String? = null,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface AiProviderClient {
    suspend fun generateText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): AiProviderResponse

    fun streamText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): Flow<AiStreamEvent>

    suspend fun generateStructured(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse
}

fun AdvisorConfig.supports(capability: AiCapability): Boolean = capability in capabilities
