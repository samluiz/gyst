package com.samluiz.gyst.domain.model

import kotlin.time.Instant

enum class ConversationTitleSource {
    FALLBACK,
    AI,
    MANUAL,
}

data class AdvisorConversation(
    val id: String,
    val title: String?,
    val titleSource: ConversationTitleSource?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val providerProfileId: String? = null,
    val lastProviderId: String? = null,
    val lastModelId: String? = null,
    val systemPromptSnapshot: String? = null,
    val lastMessagePreview: String? = null,
    val archived: Boolean = false,
    val nextSequence: Long = 0,
)

enum class ConversationMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class ConversationMessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val sequence: Long,
    val exchangeId: String?,
    val retryOfMessageId: String?,
    val role: ConversationMessageRole,
    val content: String,
    val status: ConversationMessageStatus,
    val providerId: String? = null,
    val modelId: String? = null,
    val providerMessageId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val attachmentMetadata: String? = null,
    val retryCount: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StartConversationExchange(
    val conversationId: String,
    val exchangeId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val userContent: String,
    val providerProfileId: String?,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Instant,
)

data class AppendConversationMessage(
    val id: String,
    val conversationId: String,
    val exchangeId: String?,
    val retryOfMessageId: String? = null,
    val role: ConversationMessageRole,
    val content: String,
    val status: ConversationMessageStatus,
    val providerProfileId: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val attachmentMetadata: String? = null,
    val createdAt: Instant,
)

data class ConversationExchange(
    val user: ConversationMessage,
    val assistant: ConversationMessage,
    val reused: Boolean,
)

fun fallbackConversationTitle(
    firstUserMessage: String,
    maxLength: Int = 48,
): String {
    val normalized = firstUserMessage.trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= maxLength) return normalized

    val shortened = normalized.take(maxLength + 1).substringBeforeLast(' ', missingDelimiterValue = normalized.take(maxLength))
    return shortened.trimEnd().take(maxLength).trimEnd() + "…"
}

fun conversationPreview(
    content: String,
    maxLength: Int = 96,
): String {
    val normalized = content.trim().replace(Regex("\\s+"), " ")
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "…"
}
