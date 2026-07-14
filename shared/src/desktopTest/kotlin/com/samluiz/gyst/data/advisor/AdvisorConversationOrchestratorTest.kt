package com.samluiz.gyst.data.advisor

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.data.repository.DatabaseHolder
import com.samluiz.gyst.data.repository.SqlConversationRepository
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.ConversationExchange
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.repository.ConversationRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiProviderResponse
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import com.samluiz.gyst.domain.service.AiTokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class AdvisorConversationOrchestratorTest {
    private lateinit var driver: SqlDriver
    private lateinit var conversations: SqlConversationRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        val holder =
            DatabaseHolder(
                initialDriver = driver,
                driverFactory =
                    object : SqlDriverFactory {
                        override fun createDriver(): SqlDriver = error("No reload in this test")
                    },
            )
        conversations = SqlConversationRepository(holder)
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun streamingPersistsPlaceholderDeltasAndCompletion() =
        runTest {
            createConversation()
            val provider =
                ScriptedProvider(
                    stream = {
                        flow {
                            emit(AiStreamEvent.Started("provider-message"))
                            emit(AiStreamEvent.ContentDelta("Useful "))
                            emit(AiStreamEvent.ContentDelta("answer"))
                            emit(AiStreamEvent.Completed("provider-message", AiTokenUsage(10, 4, 14)))
                        }
                    },
                )
            val outcome = AdvisorConversationOrchestrator(conversations, provider).generate(command(streaming = true))

            assertIs<AdvisorGenerationOutcome.Completed>(outcome)
            val messages = conversations.messages(CONVERSATION_ID)
            assertEquals(listOf(0L, 1L), messages.map { it.sequence })
            assertEquals("Useful answer", messages.last().content)
            assertEquals(ConversationMessageStatus.COMPLETED, messages.last().status)
            assertEquals(14L, messages.last().inputTokens?.plus(messages.last().outputTokens ?: 0L))
        }

    @Test
    fun failedMessageRetriesInPlaceWithoutDuplicateTurns() =
        runTest {
            createConversation()
            val provider = ScriptedProvider(textFailure = AiProviderException(AiProviderFailureCode.NETWORK))
            val orchestrator = AdvisorConversationOrchestrator(conversations, provider)

            assertIs<AdvisorGenerationOutcome.Failed>(orchestrator.generate(command(streaming = false)))
            val failed = conversations.messages(CONVERSATION_ID).last()
            assertEquals(ConversationMessageStatus.FAILED, failed.status)

            provider.textFailure = null
            provider.textResponse = AiProviderResponse("Recovered")
            assertIs<AdvisorGenerationOutcome.Completed>(
                orchestrator.retry(
                    conversationId = CONVERSATION_ID,
                    assistantMessageId = failed.id,
                    config = config(streaming = false),
                    apiKey = "key",
                    instructions = "system",
                ),
            )

            val messages = conversations.messages(CONVERSATION_ID)
            assertEquals(2, messages.size)
            assertEquals(failed.id, messages.last().id)
            assertEquals("Recovered", messages.last().content)
            assertEquals(1L, messages.last().retryCount)
        }

    @Test
    fun cancellationPreservesPartialContent() =
        runTest {
            createConversation()
            val emitted = CompletableDeferred<Unit>()
            val provider =
                ScriptedProvider(
                    stream = {
                        flow {
                            emit(AiStreamEvent.ContentDelta("Partial guidance"))
                            emitted.complete(Unit)
                            awaitCancellation()
                        }
                    },
                )
            val orchestrator = AdvisorConversationOrchestrator(conversations, provider)
            val generation = async { orchestrator.generate(command(streaming = true)) }
            emitted.await()

            orchestrator.cancel(CONVERSATION_ID)

            assertIs<AdvisorGenerationOutcome.Cancelled>(generation.await())
            val cancelled = conversations.messages(CONVERSATION_ID).last()
            assertEquals(ConversationMessageStatus.CANCELLED, cancelled.status)
            assertEquals("Partial guidance", cancelled.content)
        }

    @Test
    fun concurrentDifferentPromptIsRejectedBeforeEitherExchangeCanBecomeAnOrphan() =
        runTest {
            createConversation()
            val blockingRepository = BlockingStartConversationRepository(conversations)
            val orchestrator = AdvisorConversationOrchestrator(blockingRepository, ScriptedProvider())
            val first = async { orchestrator.generate(command(streaming = false)) }
            blockingRepository.startEntered.await()

            assertEquals(emptyList(), conversations.messages(CONVERSATION_ID))

            val second =
                orchestrator.generate(
                    command(
                        streaming = false,
                        exchangeId = "exchange-2",
                        userMessageId = "user-2",
                        assistantMessageId = "assistant-2",
                        userContent = "Give me a completely different plan",
                    ),
                )

            assertIs<AdvisorGenerationOutcome.AlreadyRunning>(second)
            assertEquals(emptyList(), conversations.messages(CONVERSATION_ID))
            blockingRepository.releaseStart.complete(Unit)
            assertIs<AdvisorGenerationOutcome.Completed>(first.await())
            val persisted = conversations.messages(CONVERSATION_ID)
            assertEquals(listOf("user-1", "assistant-1"), persisted.map { it.id })
            assertEquals(listOf("Help me plan", "Answer"), persisted.map { it.content })
            assertEquals(1, blockingRepository.startCount)
        }

    private suspend fun createConversation() {
        val now = Instant.parse("2026-07-14T12:00:00Z")
        conversations.create(
            AdvisorConversation(
                id = CONVERSATION_ID,
                title = null,
                titleSource = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun command(
        streaming: Boolean,
        exchangeId: String = "exchange-1",
        userMessageId: String = "user-1",
        assistantMessageId: String = "assistant-1",
        userContent: String = "Help me plan",
    ) = AdvisorGenerationCommand(
        exchange =
            StartConversationExchange(
                conversationId = CONVERSATION_ID,
                exchangeId = exchangeId,
                userMessageId = userMessageId,
                assistantMessageId = assistantMessageId,
                userContent = userContent,
                providerProfileId = "profile-1",
                providerId = "provider-1",
                modelId = "model-1",
                createdAt = Instant.parse("2026-07-14T12:01:00Z"),
            ),
        config = config(streaming),
        apiKey = "key",
        instructions = "system",
    )

    private fun config(streaming: Boolean) =
        AdvisorConfig(
            baseUrl = "https://example.test/v1",
            model = "model-1",
            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
            providerId = "provider-1",
            profileId = "profile-1",
            capabilities =
                buildSet {
                    add(AiCapability.TEXT_GENERATION)
                    if (streaming) add(AiCapability.STREAMING)
                },
        )

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
    }
}

private class BlockingStartConversationRepository(
    private val delegate: ConversationRepository,
) : ConversationRepository by delegate {
    val startEntered = CompletableDeferred<Unit>()
    val releaseStart = CompletableDeferred<Unit>()
    var startCount: Int = 0
        private set

    override suspend fun startExchange(command: StartConversationExchange): ConversationExchange {
        startCount++
        startEntered.complete(Unit)
        releaseStart.await()
        return delegate.startExchange(command)
    }
}

private class ScriptedProvider(
    var textResponse: AiProviderResponse = AiProviderResponse("Answer"),
    var textFailure: AiProviderException? = null,
    var textBlock: (suspend () -> AiProviderResponse)? = null,
    val stream: () -> Flow<AiStreamEvent> = { flow { emit(AiStreamEvent.Completed()) } },
) : AiProviderClient {
    override suspend fun generateText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): AiProviderResponse {
        textFailure?.let { throw it }
        textBlock?.let { return it() }
        return textResponse
    }

    override fun streamText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): Flow<AiStreamEvent> = stream()

    override suspend fun generateStructured(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse = error("Not used")
}
