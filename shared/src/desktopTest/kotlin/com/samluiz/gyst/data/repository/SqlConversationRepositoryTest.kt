package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.AppendConversationMessage
import com.samluiz.gyst.domain.model.ConversationMessageRole
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.ConversationTitleSource
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.repository.CompleteConversationMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlConversationRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var holder: DatabaseHolder
    private lateinit var repository: SqlConversationRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        holder = DatabaseHolder(driver, NoReloadDriverFactory)
        repository = SqlConversationRepository(holder)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun conversationsPersistInOrderAndRemainIsolatedAcrossRepositoryRecreation() =
        runTest {
            repository.create(conversation("first"))
            repository.create(conversation("second"))
            val first = repository.startExchange(exchange("first", "one", "First question"))
            repository.complete(
                CompleteConversationMessage(
                    messageId = first.assistant.id,
                    content = "First answer",
                    updatedAt = instant(2),
                ),
            )
            repository.startExchange(exchange("second", "two", "Second question").copy(createdAt = instant(3)))

            val recreated = SqlConversationRepository(holder)
            assertEquals(listOf("one-user", "one-assistant"), recreated.messages("first").map { it.id })
            assertEquals(listOf("two-user", "two-assistant"), recreated.messages("second").map { it.id })
            assertEquals("second", recreated.list().first().id)
        }

    @Test
    fun duplicateExchangeAndRetryReuseTheSameAssistantMessage() =
        runTest {
            repository.create(conversation("conversation"))
            val command = exchange("conversation", "stable", "Can I afford this?")
            val created = repository.startExchange(command)
            val reused = repository.startExchange(command.copy(userMessageId = "other-user", assistantMessageId = "other-assistant"))
            assertTrue(reused.reused)
            assertEquals(created.assistant.id, reused.assistant.id)
            assertEquals(2, repository.messages("conversation").size)

            repository.fail(created.assistant.id, "Partial", "NETWORK", null, instant(2))
            val retried = repository.retry(created.assistant.id, "openai", "model", instant(3))
            assertEquals(created.assistant.id, retried.id)
            assertEquals(ConversationMessageStatus.PENDING, retried.status)
            assertEquals(1, retried.retryCount)
            assertEquals(2, repository.messages("conversation").size)
        }

    @Test
    fun manualRenameSurvivesGeneratedTitleAndDeleteCascadesMessages() =
        runTest {
            repository.create(conversation("conversation"))
            repository.startExchange(exchange("conversation", "one", "A deterministic fallback title"))
            repository.rename("conversation", "My plan", instant(2))
            repository.setGeneratedTitle("conversation", "Generated", ConversationTitleSource.AI, instant(3))
            assertEquals("My plan", repository.get("conversation")?.title)

            repository.delete("conversation")
            assertNull(repository.get("conversation"))
            assertTrue(repository.messages("conversation").isEmpty())
        }

    @Test
    fun interruptedMessagesRecoverAsFailed() =
        runTest {
            repository.create(conversation("conversation"))
            val exchange = repository.startExchange(exchange("conversation", "one", "Question"))
            repository.updateStreamingContent(exchange.assistant.id, "Useful partial answer", instant(2))

            repository.recoverInterrupted(instant(3))

            val recovered = repository.messages("conversation").last()
            assertEquals(ConversationMessageStatus.FAILED, recovered.status)
            assertEquals("Useful partial answer", recovered.content)
            assertEquals("INTERRUPTED", recovered.errorType)
        }

    @Test
    fun openingOverviewIsIdempotentWithoutAFakeUserMessage() =
        runTest {
            repository.create(conversation("conversation"))
            val command =
                AppendConversationMessage(
                    id = "overview",
                    conversationId = "conversation",
                    exchangeId = "opening-overview",
                    role = ConversationMessageRole.ASSISTANT,
                    content = "Your financial overview",
                    status = ConversationMessageStatus.COMPLETED,
                    createdAt = instant(1),
                )
            val first = repository.appendMessage(command)
            val second = repository.appendMessage(command.copy(id = "duplicate"))

            assertEquals(first.id, second.id)
            assertEquals(listOf(ConversationMessageRole.ASSISTANT), repository.messages("conversation").map { it.role })
        }

    @Test
    fun cancelledAssistantPreservesPartialContentAndCanRetry() =
        runTest {
            repository.create(conversation("conversation"))
            val exchange = repository.startExchange(exchange("conversation", "one", "Question"))
            repository.cancel(exchange.assistant.id, "Partial answer", instant(2))

            val cancelled = repository.messages("conversation").last()
            assertEquals(ConversationMessageStatus.CANCELLED, cancelled.status)
            assertEquals("Partial answer", cancelled.content)
            assertEquals(ConversationMessageStatus.PENDING, repository.retry(cancelled.id, null, null, instant(3)).status)
        }

    @Test
    fun concurrentExchangesAllocateUniqueDeterministicSequences() =
        runTest {
            repository.create(conversation("conversation"))
            (0 until 20).map { index ->
                async { repository.startExchange(exchange("conversation", "exchange-$index", "Question $index")) }
            }.awaitAll()

            val messages = repository.messages("conversation")
            assertEquals(40, messages.size)
            assertEquals((0L until 40L).toList(), messages.map { it.sequence })
        }

    private fun conversation(id: String) =
        AdvisorConversation(
            id = id,
            title = null,
            titleSource = null,
            createdAt = instant(0),
            updatedAt = instant(0),
        )

    private fun exchange(
        conversationId: String,
        exchangeId: String,
        prompt: String,
    ) = StartConversationExchange(
        conversationId = conversationId,
        exchangeId = exchangeId,
        userMessageId = "$exchangeId-user",
        assistantMessageId = "$exchangeId-assistant",
        userContent = prompt,
        providerProfileId = null,
        providerId = "test",
        modelId = "test-model",
        createdAt = instant(1),
    )

    private fun instant(second: Int): Instant = Instant.parse("2026-07-14T00:00:${second.toString().padStart(2, '0')}Z")

    private object NoReloadDriverFactory : SqlDriverFactory {
        override fun createDriver(): SqlDriver = error("Not used")
    }
}
