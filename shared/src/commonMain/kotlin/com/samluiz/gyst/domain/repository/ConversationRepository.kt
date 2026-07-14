package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.AppendConversationMessage
import com.samluiz.gyst.domain.model.ConversationExchange
import com.samluiz.gyst.domain.model.ConversationMessage
import com.samluiz.gyst.domain.model.ConversationTitleSource
import com.samluiz.gyst.domain.model.StartConversationExchange
import kotlin.time.Instant

data class CompleteConversationMessage(
    val messageId: String,
    val content: String,
    val providerMessageId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val updatedAt: Instant,
)

interface ConversationRepository {
    suspend fun create(conversation: AdvisorConversation)

    suspend fun get(id: String): AdvisorConversation?

    suspend fun list(includeArchived: Boolean = false): List<AdvisorConversation>

    suspend fun messages(conversationId: String): List<ConversationMessage>

    suspend fun startExchange(command: StartConversationExchange): ConversationExchange

    /** Supports persisted assistant-only opening messages without manufacturing a user turn. */
    suspend fun appendMessage(command: AppendConversationMessage): ConversationMessage

    suspend fun rename(
        id: String,
        title: String,
        updatedAt: Instant,
    )

    suspend fun setGeneratedTitle(
        id: String,
        title: String,
        source: ConversationTitleSource,
        updatedAt: Instant,
    )

    suspend fun setArchived(
        id: String,
        archived: Boolean,
        updatedAt: Instant,
    )

    suspend fun delete(id: String)

    suspend fun updateStreamingContent(
        messageId: String,
        content: String,
        updatedAt: Instant,
    )

    suspend fun complete(command: CompleteConversationMessage)

    suspend fun fail(
        messageId: String,
        partialContent: String,
        errorType: String,
        safeErrorMessage: String?,
        updatedAt: Instant,
    )

    suspend fun cancel(
        messageId: String,
        partialContent: String,
        updatedAt: Instant,
    )

    suspend fun retry(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage

    /**
     * Starts a fresh generation for an existing assistant-only message while retaining its last
     * useful content until the replacement succeeds. This is intentionally separate from retrying
     * a failed chat exchange, which clears partial output.
     */
    suspend fun beginRegeneration(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage

    suspend fun recoverInterrupted(updatedAt: Instant)
}
