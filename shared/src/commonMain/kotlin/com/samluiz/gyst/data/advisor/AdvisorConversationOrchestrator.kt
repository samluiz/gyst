package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.ConversationMessage
import com.samluiz.gyst.domain.model.ConversationMessageRole
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.repository.CompleteConversationMessage
import com.samluiz.gyst.domain.repository.ConversationRepository
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiTokenUsage
import com.samluiz.gyst.domain.service.supports
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val STREAM_PERSISTENCE_CHARACTER_BATCH = 512
private val STREAM_PERSISTENCE_INTERVAL = 250.milliseconds

data class AdvisorGenerationCommand(
    val exchange: StartConversationExchange,
    val config: AdvisorConfig,
    val apiKey: String,
    val instructions: String,
)

sealed interface AdvisorGenerationOutcome {
    data class Completed(val messageId: String) : AdvisorGenerationOutcome

    data class Failed(
        val messageId: String,
        val code: AiProviderFailureCode,
    ) : AdvisorGenerationOutcome

    data class Cancelled(val messageId: String) : AdvisorGenerationOutcome

    data class AlreadyRunning(val messageId: String) : AdvisorGenerationOutcome

    /** The provider result is unknown or persisted state could not be refreshed safely. */
    data class PersistenceFailed(val messageId: String) : AdvisorGenerationOutcome
}

class AdvisorConversationOrchestrator(
    private val conversationRepository: ConversationRepository,
    private val providerClient: AiProviderClient,
) {
    private val activeMutex = Mutex()
    private val activeByConversation = mutableMapOf<String, ActiveGeneration>()

    suspend fun generate(
        command: AdvisorGenerationCommand,
        onPersistedChange: suspend () -> Unit = {},
    ): AdvisorGenerationOutcome =
        runWithGenerationSlot(
            conversationId = command.exchange.conversationId,
            proposedMessageId = command.exchange.assistantMessageId,
            onPersistedChange = onPersistedChange,
        ) { active ->
            val exchange = persist { conversationRepository.startExchange(command.exchange) }
            active.messageId = exchange.assistant.id
            active.messagePersisted = true
            when (exchange.assistant.status) {
                ConversationMessageStatus.COMPLETED -> {
                    return@runWithGenerationSlot AdvisorGenerationOutcome.Completed(exchange.assistant.id)
                }
                ConversationMessageStatus.FAILED,
                ConversationMessageStatus.CANCELLED,
                -> {
                    persist {
                        conversationRepository.retry(
                            exchange.assistant.id,
                            command.config.providerId,
                            command.config.model,
                            Clock.System.now(),
                        )
                    }
                }
                ConversationMessageStatus.PENDING,
                ConversationMessageStatus.STREAMING,
                -> Unit
            }
            performGeneration(
                conversationId = command.exchange.conversationId,
                active = active,
                config = command.config,
                apiKey = command.apiKey,
                instructions = command.instructions,
                onPersistedChange = onPersistedChange,
            )
        }

    suspend fun retry(
        conversationId: String,
        assistantMessageId: String,
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        onPersistedChange: suspend () -> Unit = {},
    ): AdvisorGenerationOutcome =
        runWithGenerationSlot(
            conversationId = conversationId,
            proposedMessageId = assistantMessageId,
            onPersistedChange = onPersistedChange,
        ) { active ->
            persist {
                conversationRepository.retry(assistantMessageId, config.providerId, config.model, Clock.System.now())
            }
            active.messagePersisted = true
            persist { onPersistedChange() }
            performGeneration(
                conversationId = conversationId,
                active = active,
                config = config,
                apiKey = apiKey,
                instructions = instructions,
                onPersistedChange = onPersistedChange,
            )
        }

    suspend fun cancel(conversationId: String): Boolean {
        val active = activeMutex.withLock { activeByConversation[conversationId] } ?: return false
        active.job?.let { job ->
            job.cancel(CancellationException("Cancelled by user"))
            job.join()
        }
        return true
    }

    suspend fun cancelAll() {
        val jobs = activeMutex.withLock { activeByConversation.values.mapNotNull(ActiveGeneration::job) }
        jobs.forEach { job -> job.cancel(CancellationException("Database replacement requested")) }
        jobs.forEach { job -> job.join() }
    }

    private suspend fun runWithGenerationSlot(
        conversationId: String,
        proposedMessageId: String,
        onPersistedChange: suspend () -> Unit,
        generation: suspend (ActiveGeneration) -> AdvisorGenerationOutcome,
    ): AdvisorGenerationOutcome =
        supervisorScope {
            val active = ActiveGeneration(messageId = proposedMessageId)
            val request = async(start = CoroutineStart.LAZY) { generation(active) }
            active.job = request
            val registered =
                activeMutex.withLock {
                    if (activeByConversation.containsKey(conversationId)) {
                        false
                    } else {
                        activeByConversation[conversationId] = active
                        true
                    }
                }
            if (!registered) {
                request.cancel()
                return@supervisorScope AdvisorGenerationOutcome.AlreadyRunning(proposedMessageId)
            }

            request.start()
            try {
                request.await()
            } catch (cancelled: CancellationException) {
                val persistenceFailed =
                    active.messagePersisted &&
                        persistTerminalState {
                            conversationRepository.cancel(active.messageId, active.partialContent, Clock.System.now())
                            onPersistedChange()
                        }
                if (persistenceFailed) {
                    AdvisorGenerationOutcome.PersistenceFailed(active.messageId)
                } else {
                    AdvisorGenerationOutcome.Cancelled(active.messageId)
                }
            } catch (_: ConversationPersistenceException) {
                if (active.messagePersisted && !active.terminalPersisted) {
                    persistTerminalState {
                        conversationRepository.fail(
                            messageId = active.messageId,
                            partialContent = active.partialContent,
                            errorType = PERSISTENCE_ERROR_TYPE,
                            safeErrorMessage = PERSISTENCE_SAFE_MESSAGE,
                            updatedAt = Clock.System.now(),
                        )
                        onPersistedChange()
                    }
                }
                AdvisorGenerationOutcome.PersistenceFailed(active.messageId)
            } catch (error: Throwable) {
                val failure = error as? AiProviderException
                val code = failure?.code ?: AiProviderFailureCode.REQUEST_FAILED
                val persistenceFailed =
                    active.messagePersisted &&
                        persistTerminalState {
                            conversationRepository.fail(
                                messageId = active.messageId,
                                partialContent = active.partialContent,
                                errorType = code.name,
                                safeErrorMessage = code.safeStoredMessage(),
                                updatedAt = Clock.System.now(),
                            )
                            onPersistedChange()
                        }
                if (persistenceFailed) {
                    AdvisorGenerationOutcome.PersistenceFailed(active.messageId)
                } else {
                    AdvisorGenerationOutcome.Failed(active.messageId, code)
                }
            } finally {
                withContext(NonCancellable) {
                    activeMutex.withLock {
                        if (activeByConversation[conversationId] === active) activeByConversation.remove(conversationId)
                    }
                }
            }
        }

    private suspend fun performGeneration(
        conversationId: String,
        active: ActiveGeneration,
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        onPersistedChange: suspend () -> Unit,
    ): AdvisorGenerationOutcome {
        val persistedMessages = persist { conversationRepository.messages(conversationId) }
        val assistantSequence = messageSequence(persistedMessages, active.messageId)
        val contextMessages =
            persistedMessages
                .filter { message ->
                    message.sequence < assistantSequence && message.status == ConversationMessageStatus.COMPLETED
                }.mapNotNull(ConversationMessage::toAiMessage)
        if (config.supports(AiCapability.STREAMING)) {
            return generateStreaming(
                active = active,
                config = config,
                apiKey = apiKey,
                instructions = instructions,
                messages = contextMessages,
                onPersistedChange = onPersistedChange,
            )
        }
        val response = providerClient.generateText(config, apiKey, instructions, contextMessages)
        active.partialContent = response.content
        persist {
            conversationRepository.complete(
                CompleteConversationMessage(
                    messageId = active.messageId,
                    content = response.content,
                    providerMessageId = response.providerMessageId,
                    inputTokens = response.tokenUsage?.promptTokens,
                    outputTokens = response.tokenUsage?.completionTokens,
                    updatedAt = Clock.System.now(),
                ),
            )
        }
        active.terminalPersisted = true
        persist { onPersistedChange() }
        return AdvisorGenerationOutcome.Completed(active.messageId)
    }

    private suspend fun generateStreaming(
        active: ActiveGeneration,
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        onPersistedChange: suspend () -> Unit,
    ): AdvisorGenerationOutcome {
        persist { conversationRepository.updateStreamingContent(active.messageId, "", Clock.System.now()) }
        persist { onPersistedChange() }
        var persistedLength = 0
        var lastPersistence = TimeSource.Monotonic.markNow()
        var providerMessageId: String? = null
        var usage: AiTokenUsage? = null
        providerClient.streamText(config, apiKey, instructions, messages).collect { event ->
            when (event) {
                is AiStreamEvent.Started -> providerMessageId = event.providerMessageId ?: providerMessageId
                is AiStreamEvent.ContentDelta -> {
                    active.partialContent += event.content
                    val shouldPersist =
                        active.partialContent.length - persistedLength >= STREAM_PERSISTENCE_CHARACTER_BATCH ||
                            lastPersistence.elapsedNow() >= STREAM_PERSISTENCE_INTERVAL
                    if (shouldPersist) {
                        persist {
                            conversationRepository.updateStreamingContent(active.messageId, active.partialContent, Clock.System.now())
                        }
                        persistedLength = active.partialContent.length
                        lastPersistence = TimeSource.Monotonic.markNow()
                        persist { onPersistedChange() }
                    }
                }
                is AiStreamEvent.Completed -> {
                    providerMessageId = event.providerMessageId ?: providerMessageId
                    usage = event.tokenUsage ?: usage
                }
            }
        }
        persist {
            conversationRepository.complete(
                CompleteConversationMessage(
                    messageId = active.messageId,
                    content = active.partialContent,
                    providerMessageId = providerMessageId,
                    inputTokens = usage?.promptTokens,
                    outputTokens = usage?.completionTokens,
                    updatedAt = Clock.System.now(),
                ),
            )
        }
        active.terminalPersisted = true
        persist { onPersistedChange() }
        return AdvisorGenerationOutcome.Completed(active.messageId)
    }

    private suspend fun persistTerminalState(block: suspend () -> Unit): Boolean =
        withContext(NonCancellable) {
            try {
                persist { block() }
                false
            } catch (_: ConversationPersistenceException) {
                true
            }
        }

    private suspend fun <T> persist(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ConversationPersistenceException) {
            throw failure
        } catch (failure: Throwable) {
            AppLogger.e(LOG_TAG, "conversation_persistence_failed type=${failure::class.simpleName}")
            throw ConversationPersistenceException(failure)
        }
}

private data class ActiveGeneration(
    var messageId: String,
    var messagePersisted: Boolean = false,
    var terminalPersisted: Boolean = false,
    var job: Job? = null,
    var partialContent: String = "",
)

private class ConversationPersistenceException(
    cause: Throwable,
) : Exception("Conversation persistence failed", cause)

private const val PERSISTENCE_ERROR_TYPE = "DATABASE"
private const val PERSISTENCE_SAFE_MESSAGE = "Conversation persistence failed."
private const val LOG_TAG = "AdvisorConversation"

private fun messageSequence(
    messages: List<ConversationMessage>,
    messageId: String,
): Long = messages.firstOrNull { it.id == messageId }?.sequence ?: Long.MAX_VALUE

private fun ConversationMessage.toAiMessage(): AiMessage? {
    val aiRole =
        when (role) {
            ConversationMessageRole.SYSTEM -> AiMessageRole.SYSTEM
            ConversationMessageRole.USER -> AiMessageRole.USER
            ConversationMessageRole.ASSISTANT -> AiMessageRole.ASSISTANT
            ConversationMessageRole.TOOL -> AiMessageRole.TOOL
        }
    return content.takeIf(String::isNotBlank)?.let { AiMessage(aiRole, it) }
}

private fun AiProviderFailureCode.safeStoredMessage(): String =
    when (this) {
        AiProviderFailureCode.AUTHENTICATION -> "Provider authentication failed."
        AiProviderFailureCode.RATE_LIMITED -> "Provider rate limit reached."
        AiProviderFailureCode.NETWORK -> "Network request failed."
        AiProviderFailureCode.TIMEOUT -> "Provider request timed out."
        AiProviderFailureCode.INVALID_RESPONSE -> "Provider returned an invalid response."
        AiProviderFailureCode.UNSUPPORTED_CAPABILITY -> "The selected model does not support this operation."
        AiProviderFailureCode.CANCELLED -> "Request was cancelled."
        AiProviderFailureCode.REQUEST_FAILED -> "Provider request failed."
    }
