package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.Advisor_conversation
import com.samluiz.gyst.db.Advisor_message
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
import kotlin.time.Instant

class SqlConversationRepository(
    private val holder: DatabaseHolder,
) : ConversationRepository {
    override suspend fun create(conversation: AdvisorConversation) {
        holder.withDatabase { database ->
            database.financeQueries.insertAdvisorConversation(
                id = conversation.id,
                title = conversation.title,
                title_source = conversation.titleSource?.name,
                created_at = conversation.createdAt.toString(),
                updated_at = conversation.updatedAt.toString(),
                provider_profile_id = conversation.providerProfileId,
                last_provider_id = conversation.lastProviderId,
                last_model_id = conversation.lastModelId,
                system_prompt_snapshot = conversation.systemPromptSnapshot,
                last_message_preview = conversation.lastMessagePreview,
                archived = conversation.archived.asLong(),
                next_sequence = conversation.nextSequence,
            )
        }
    }

    override suspend fun get(id: String): AdvisorConversation? =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorConversationById(id).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun list(includeArchived: Boolean): List<AdvisorConversation> =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorConversations(includeArchived.asLong()).executeAsList().map {
                it.toDomain()
            }
        }

    override suspend fun messages(conversationId: String): List<ConversationMessage> =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorMessagesByConversation(conversationId).executeAsList().map {
                it.toDomain()
            }
        }

    override suspend fun startExchange(command: StartConversationExchange): ConversationExchange =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val existingUser =
                    queries.selectAdvisorMessageByExchangeAndRole(
                        conversationId = command.conversationId,
                        exchangeId = command.exchangeId,
                        role = ConversationMessageRole.USER.name,
                    ).executeAsOneOrNull()
                val existingAssistant =
                    queries.selectAdvisorMessageByExchangeAndRole(
                        conversationId = command.conversationId,
                        exchangeId = command.exchangeId,
                        role = ConversationMessageRole.ASSISTANT.name,
                    ).executeAsOneOrNull()
                if (existingUser != null || existingAssistant != null) {
                    check(existingUser != null && existingAssistant != null) {
                        "Conversation exchange is incomplete"
                    }
                    return@transactionWithResult ConversationExchange(
                        user = existingUser.toDomain(),
                        assistant = existingAssistant.toDomain(),
                        reused = true,
                    )
                }

                val conversation =
                    requireNotNull(
                        queries.selectAdvisorConversationById(command.conversationId).executeAsOneOrNull(),
                    ) { "Conversation does not exist" }
                val now = command.createdAt.toString()
                queries.insertAdvisorMessage(
                    id = command.userMessageId,
                    conversation_id = command.conversationId,
                    sequence_number = conversation.next_sequence,
                    exchange_id = command.exchangeId,
                    retry_of_message_id = null,
                    role = ConversationMessageRole.USER.name,
                    content = command.userContent.trim(),
                    status = ConversationMessageStatus.COMPLETED.name,
                    provider_id = command.providerId,
                    model_id = command.modelId,
                    provider_message_id = null,
                    input_tokens = null,
                    output_tokens = null,
                    error_type = null,
                    error_message = null,
                    attachment_metadata = null,
                    retry_count = 0,
                    created_at = now,
                    updated_at = now,
                )
                queries.insertAdvisorMessage(
                    id = command.assistantMessageId,
                    conversation_id = command.conversationId,
                    sequence_number = conversation.next_sequence + 1,
                    exchange_id = command.exchangeId,
                    retry_of_message_id = null,
                    role = ConversationMessageRole.ASSISTANT.name,
                    content = "",
                    status = ConversationMessageStatus.PENDING.name,
                    provider_id = command.providerId,
                    model_id = command.modelId,
                    provider_message_id = null,
                    input_tokens = null,
                    output_tokens = null,
                    error_type = null,
                    error_message = null,
                    attachment_metadata = null,
                    retry_count = 0,
                    created_at = now,
                    updated_at = now,
                )
                queries.advanceAdvisorConversationSequence(
                    count = 2,
                    updatedAt = now,
                    id = command.conversationId,
                )
                if (conversation.title == null) {
                    queries.setAdvisorConversationGeneratedTitleIfAllowed(
                        title = fallbackConversationTitle(command.userContent),
                        titleSource = ConversationTitleSource.FALLBACK.name,
                        updatedAt = now,
                        id = command.conversationId,
                    )
                }
                queries.updateAdvisorConversationActivity(
                    updatedAt = now,
                    providerProfileId = command.providerProfileId,
                    providerId = command.providerId,
                    modelId = command.modelId,
                    preview = conversationPreview(command.userContent),
                    id = command.conversationId,
                )
                ConversationExchange(
                    user = checkNotNull(queries.selectAdvisorMessageById(command.userMessageId).executeAsOneOrNull()).toDomain(),
                    assistant =
                        checkNotNull(
                            queries.selectAdvisorMessageById(command.assistantMessageId).executeAsOneOrNull(),
                        ).toDomain(),
                    reused = false,
                )
            }
        }

    override suspend fun appendMessage(command: AppendConversationMessage): ConversationMessage =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val existing =
                    command.exchangeId?.let { exchangeId ->
                        queries.selectAdvisorMessageByExchangeAndRole(
                            conversationId = command.conversationId,
                            exchangeId = exchangeId,
                            role = command.role.name,
                        ).executeAsOneOrNull()
                    }
                if (existing != null) return@transactionWithResult existing.toDomain()

                val conversation =
                    requireNotNull(
                        queries.selectAdvisorConversationById(command.conversationId).executeAsOneOrNull(),
                    ) { "Conversation does not exist" }
                val now = command.createdAt.toString()
                queries.insertAdvisorMessage(
                    id = command.id,
                    conversation_id = command.conversationId,
                    sequence_number = conversation.next_sequence,
                    exchange_id = command.exchangeId,
                    retry_of_message_id = command.retryOfMessageId,
                    role = command.role.name,
                    content = command.content,
                    status = command.status.name,
                    provider_id = command.providerId,
                    model_id = command.modelId,
                    provider_message_id = null,
                    input_tokens = null,
                    output_tokens = null,
                    error_type = null,
                    error_message = null,
                    attachment_metadata = command.attachmentMetadata,
                    retry_count = 0,
                    created_at = now,
                    updated_at = now,
                )
                queries.advanceAdvisorConversationSequence(1, now, command.conversationId)
                queries.updateAdvisorConversationActivity(
                    updatedAt = now,
                    providerProfileId = command.providerProfileId,
                    providerId = command.providerId,
                    modelId = command.modelId,
                    preview = conversationPreview(command.content),
                    id = command.conversationId,
                )
                checkNotNull(queries.selectAdvisorMessageById(command.id).executeAsOneOrNull()).toDomain()
            }
        }

    override suspend fun rename(
        id: String,
        title: String,
        updatedAt: Instant,
    ) {
        require(title.isNotBlank()) { "Conversation title cannot be blank" }
        holder.withDatabase { database ->
            database.financeQueries.setAdvisorConversationManualTitle(title.trim(), updatedAt.toString(), id)
        }
    }

    override suspend fun setGeneratedTitle(
        id: String,
        title: String,
        source: ConversationTitleSource,
        updatedAt: Instant,
    ) {
        require(source != ConversationTitleSource.MANUAL)
        require(title.isNotBlank())
        holder.withDatabase { database ->
            database.financeQueries.setAdvisorConversationGeneratedTitleIfAllowed(
                title.trim(),
                source.name,
                updatedAt.toString(),
                id,
            )
        }
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.setAdvisorConversationArchived(archived.asLong(), updatedAt.toString(), id)
        }
    }

    override suspend fun delete(id: String) {
        holder.withDatabase { database -> database.financeQueries.deleteAdvisorConversation(id) }
    }

    override suspend fun updateStreamingContent(
        messageId: String,
        content: String,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.updateAdvisorMessageProgress(content, updatedAt.toString(), messageId)
        }
    }

    override suspend fun complete(command: CompleteConversationMessage) {
        holder.withDatabase { database ->
            database.transaction {
                val queries = database.financeQueries
                val message = queries.selectAdvisorMessageById(command.messageId).executeAsOneOrNull()
                queries.completeAdvisorMessage(
                    content = command.content,
                    providerMessageId = command.providerMessageId,
                    inputTokens = command.inputTokens,
                    outputTokens = command.outputTokens,
                    updatedAt = command.updatedAt.toString(),
                    id = command.messageId,
                )
                if (message != null) {
                    val conversation =
                        queries.selectAdvisorConversationById(message.conversation_id).executeAsOneOrNull()
                    queries.updateAdvisorConversationActivity(
                        updatedAt = command.updatedAt.toString(),
                        providerProfileId = conversation?.provider_profile_id,
                        providerId = message.provider_id,
                        modelId = message.model_id,
                        preview = conversationPreview(command.content),
                        id = message.conversation_id,
                    )
                }
            }
        }
    }

    override suspend fun fail(
        messageId: String,
        partialContent: String,
        errorType: String,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.failAdvisorMessage(
                partialContent,
                errorType,
                safeErrorMessage,
                updatedAt.toString(),
                messageId,
            )
        }
    }

    override suspend fun cancel(
        messageId: String,
        partialContent: String,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.cancelAdvisorMessage(partialContent, updatedAt.toString(), messageId)
        }
    }

    override suspend fun retry(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage =
        holder.withDatabase { database ->
            database.financeQueries.resetAdvisorMessageForRetry(
                providerId,
                modelId,
                updatedAt.toString(),
                messageId,
            )
            requireNotNull(database.financeQueries.selectAdvisorMessageById(messageId).executeAsOneOrNull()).toDomain()
        }

    override suspend fun beginRegeneration(
        messageId: String,
        providerId: String?,
        modelId: String?,
        updatedAt: Instant,
    ): ConversationMessage =
        holder.withDatabase { database ->
            database.financeQueries.beginAdvisorMessageRegeneration(
                providerId = providerId,
                modelId = modelId,
                updatedAt = updatedAt.toString(),
                id = messageId,
            )
            requireNotNull(database.financeQueries.selectAdvisorMessageById(messageId).executeAsOneOrNull()).toDomain()
        }

    override suspend fun recoverInterrupted(updatedAt: Instant) {
        holder.withDatabase { database ->
            database.financeQueries.recoverInterruptedAdvisorMessages(updatedAt.toString())
        }
    }
}

private fun Advisor_conversation.toDomain(): AdvisorConversation =
    AdvisorConversation(
        id = id,
        title = title,
        titleSource = title_source?.let(ConversationTitleSource::valueOf),
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
        providerProfileId = provider_profile_id,
        lastProviderId = last_provider_id,
        lastModelId = last_model_id,
        systemPromptSnapshot = system_prompt_snapshot,
        lastMessagePreview = last_message_preview,
        archived = archived == 1L,
        nextSequence = next_sequence,
    )

private fun Advisor_message.toDomain(): ConversationMessage =
    ConversationMessage(
        id = id,
        conversationId = conversation_id,
        sequence = sequence_number,
        exchangeId = exchange_id,
        retryOfMessageId = retry_of_message_id,
        role = ConversationMessageRole.valueOf(role),
        content = content,
        status = ConversationMessageStatus.valueOf(status),
        providerId = provider_id,
        modelId = model_id,
        providerMessageId = provider_message_id,
        inputTokens = input_tokens,
        outputTokens = output_tokens,
        errorType = error_type,
        errorMessage = error_message,
        attachmentMetadata = attachment_metadata,
        retryCount = retry_count,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )
