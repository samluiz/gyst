package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.AppendConversationMessage
import com.samluiz.gyst.domain.model.ConversationExchange
import com.samluiz.gyst.domain.model.ConversationMessage
import com.samluiz.gyst.domain.model.ConversationMessageRole
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.ConversationTitleSource
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.model.conversationPreview
import com.samluiz.gyst.domain.model.fallbackConversationTitle
import com.samluiz.gyst.domain.repository.CompleteConversationMessage
import com.samluiz.gyst.domain.repository.ConversationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

internal class FakeConversationRepository : ConversationRepository {
    private val mutex = Mutex()
    private val conversations = linkedMapOf<String, AdvisorConversation>()
    private val messages = linkedMapOf<String, ConversationMessage>()

    override suspend fun create(conversation: AdvisorConversation) {
        mutex.withLock {
            if (conversation.id !in conversations) conversations[conversation.id] = conversation
        }
    }

    override suspend fun get(id: String): AdvisorConversation? = mutex.withLock { conversations[id] }

    override suspend fun list(includeArchived: Boolean): List<AdvisorConversation> =
        mutex.withLock {
            conversations.values.filter { includeArchived || !it.archived }.sortedByDescending { it.updatedAt }
        }

    override suspend fun messages(conversationId: String): List<ConversationMessage> =
        mutex.withLock { messages.values.filter { it.conversationId == conversationId }.sortedBy { it.sequence } }

    override suspend fun startExchange(command: StartConversationExchange): ConversationExchange =
        mutex.withLock {
            val existing =
                messages.values.filter {
                    it.conversationId == command.conversationId && it.exchangeId == command.exchangeId
                }
            if (existing.isNotEmpty()) {
                return@withLock ConversationExchange(
                    user = existing.single { it.role == ConversationMessageRole.USER },
                    assistant = existing.single { it.role == ConversationMessageRole.ASSISTANT },
                    reused = true,
                )
            }
            val conversation = requireNotNull(conversations[command.conversationId])
            val user =
                message(
                    id = command.userMessageId,
                    conversationId = command.conversationId,
                    sequence = conversation.nextSequence,
                    exchangeId = command.exchangeId,
                    role = ConversationMessageRole.USER,
                    content = command.userContent,
                    status = ConversationMessageStatus.COMPLETED,
                    createdAt = command.createdAt,
                )
            val assistant =
                message(
                    id = command.assistantMessageId,
                    conversationId = command.conversationId,
                    sequence = conversation.nextSequence + 1,
                    exchangeId = command.exchangeId,
                    role = ConversationMessageRole.ASSISTANT,
                    content = "",
                    status = ConversationMessageStatus.PENDING,
                    createdAt = command.createdAt,
                )
            messages[user.id] = user
            messages[assistant.id] = assistant
            conversations[conversation.id] =
                conversation.copy(
                    title = conversation.title ?: fallbackConversationTitle(command.userContent),
                    titleSource = conversation.titleSource ?: ConversationTitleSource.FALLBACK,
                    nextSequence = conversation.nextSequence + 2,
                    updatedAt = command.createdAt,
                    lastMessagePreview = conversationPreview(command.userContent),
                )
            ConversationExchange(user, assistant, reused = false)
        }

    override suspend fun appendMessage(command: AppendConversationMessage): ConversationMessage =
        mutex.withLock {
            command.exchangeId?.let { exchange ->
                messages.values.firstOrNull {
                    it.conversationId == command.conversationId && it.exchangeId == exchange && it.role == command.role
                }?.let { return@withLock it }
            }
            val conversation = requireNotNull(conversations[command.conversationId])
            val message =
                message(
                    id = command.id,
                    conversationId = command.conversationId,
                    sequence = conversation.nextSequence,
                    exchangeId = command.exchangeId,
                    role = command.role,
                    content = command.content,
                    status = command.status,
                    createdAt = command.createdAt,
                ).copy(attachmentMetadata = command.attachmentMetadata)
            messages[message.id] = message
            conversations[conversation.id] = conversation.copy(nextSequence = conversation.nextSequence + 1)
            message
        }

    override suspend fun rename(
        id: String,
        title: String,
        updatedAt: Instant,
    ) {
        mutex.withLock {
            conversations[id] =
                requireNotNull(conversations[id]).copy(
                    title = title,
                    titleSource = ConversationTitleSource.MANUAL,
                )
        }
    }

    override suspend fun setGeneratedTitle(
        id: String,
        title: String,
        source: ConversationTitleSource,
        updatedAt: Instant,
    ) {
        mutex.withLock {
            val current = requireNotNull(conversations[id])
            if (current.titleSource != ConversationTitleSource.MANUAL) conversations[id] = current.copy(title = title, titleSource = source)
        }
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
        updatedAt: Instant,
    ) {
        mutex.withLock { conversations[id] = requireNotNull(conversations[id]).copy(archived = archived, updatedAt = updatedAt) }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            conversations.remove(id)
            messages.entries.removeAll { it.value.conversationId == id }
        }
    }

    override suspend fun updateStreamingContent(
        messageId: String,
        content: String,
        updatedAt: Instant,
    ) = updateMessage(messageId) { it.copy(content = content, status = ConversationMessageStatus.STREAMING, updatedAt = updatedAt) }

    override suspend fun complete(command: CompleteConversationMessage) =
        updateMessage(command.messageId) {
            it.copy(
                content = command.content,
                status = ConversationMessageStatus.COMPLETED,
                providerMessageId = command.providerMessageId,
                inputTokens = command.inputTokens,
                outputTokens = command.outputTokens,
                updatedAt = command.updatedAt,
            )
        }

    override suspend fun fail(
        messageId: String,
        partialContent: String,
        errorType: String,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ) = updateMessage(messageId) {
        it.copy(
            content = partialContent,
            status = ConversationMessageStatus.FAILED,
            errorType = errorType,
            errorMessage = safeErrorMessage,
            updatedAt = updatedAt,
        )
    }

    override suspend fun cancel(
        messageId: String,
        partialContent: String,
        updatedAt: Instant,
    ) = updateMessage(messageId) {
        it.copy(content = partialContent, status = ConversationMessageStatus.CANCELLED, updatedAt = updatedAt)
    }

    override suspend fun retry(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage {
        updateMessage(messageId) {
            it.copy(
                content = "",
                status = ConversationMessageStatus.PENDING,
                errorType = null,
                errorMessage = null,
                providerId = providerId,
                modelId = modelId,
                retryCount = it.retryCount + 1,
                updatedAt = updatedAt,
            )
        }
        return mutex.withLock { requireNotNull(messages[messageId]) }
    }

    override suspend fun beginRegeneration(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage {
        updateMessage(messageId) {
            it.copy(
                status = ConversationMessageStatus.PENDING,
                errorType = null,
                errorMessage = null,
                providerId = providerId,
                modelId = modelId,
                providerMessageId = null,
                inputTokens = null,
                outputTokens = null,
                retryCount = it.retryCount + 1,
                updatedAt = updatedAt,
            )
        }
        return mutex.withLock { requireNotNull(messages[messageId]) }
    }

    override suspend fun recoverInterrupted(updatedAt: Instant) {
        mutex.withLock {
            messages.keys.toList().forEach { messageId ->
                val message = requireNotNull(messages[messageId])
                if (message.status in setOf(ConversationMessageStatus.PENDING, ConversationMessageStatus.STREAMING)) {
                    messages[messageId] =
                        message.copy(
                            status = ConversationMessageStatus.FAILED,
                            errorType = "INTERRUPTED",
                            updatedAt = updatedAt,
                        )
                }
            }
        }
    }

    private suspend fun updateMessage(
        id: String,
        transform: (ConversationMessage) -> ConversationMessage,
    ) {
        mutex.withLock { messages[id] = transform(requireNotNull(messages[id])) }
    }
}

private fun message(
    id: String,
    conversationId: String,
    sequence: Long,
    exchangeId: String?,
    role: ConversationMessageRole,
    content: String,
    status: ConversationMessageStatus,
    createdAt: Instant,
) = ConversationMessage(
    id = id,
    conversationId = conversationId,
    sequence = sequence,
    exchangeId = exchangeId,
    retryOfMessageId = null,
    role = role,
    content = content,
    status = status,
    createdAt = createdAt,
    updatedAt = createdAt,
)
