package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.ConversationExchange
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.MonthlySummary
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.repository.ConversationRepository
import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFailureCode
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiProviderResponse
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AdvisorPersistenceFailureTest {
    @Test
    fun askClearsLoadingAndReportsDatabaseFailureWhenExchangePersistenceFails() =
        runTest {
            val delegate = FakeConversationRepository()
            val repository = FaultInjectingConversationRepository(delegate)
            val service = configuredService(repository, ScriptedTextProvider())
            service.createConversation()
            repository.failStartExchange = true

            service.ask("Help me plan", financialContext(), "en")

            assertFalse(service.state.value.isLoading)
            assertEquals(AdvisorFailureCode.DATABASE, service.state.value.lastError?.code)
            assertEquals(emptyList(), delegate.messages(checkNotNull(service.state.value.selectedConversationId)))
        }

    @Test
    fun askClearsLoadingAndReportsSecureStorageFailureWhenCredentialReadFails() =
        runTest {
            val repository = FakeConversationRepository()
            val secretStore = InMemoryAdvisorSecretStore()
            val service =
                configuredService(
                    repository = repository,
                    provider = ScriptedTextProvider(),
                    secretStore = secretStore,
                )
            service.createConversation()
            secretStore.failReads = true

            service.ask("Help me plan", financialContext(), "en")

            assertFalse(service.state.value.isLoading)
            assertEquals(AdvisorFailureCode.SECURE_STORAGE, service.state.value.lastError?.code)
            assertEquals(emptyList(), repository.messages(checkNotNull(service.state.value.selectedConversationId)))
        }

    @Test
    fun completedAskReportsDatabaseFailureAndClearsLoadingWhenRefreshFails() =
        runTest {
            val delegate = FakeConversationRepository()
            val repository = FaultInjectingConversationRepository(delegate)
            val provider = ScriptedTextProvider()
            val service = configuredService(repository, provider)
            val conversationId = service.createConversation()
            provider.beforeResponse = { repository.failList = true }

            service.ask("Help me plan", financialContext(), "en")

            assertFalse(service.state.value.isLoading)
            assertEquals(AdvisorFailureCode.DATABASE, service.state.value.lastError?.code)
            val persisted = delegate.messages(conversationId)
            assertEquals(2, persisted.size)
            assertEquals(ConversationMessageStatus.COMPLETED, persisted.last().status)
            assertEquals("A useful answer.", persisted.last().content)
        }

    @Test
    fun retryClearsLoadingAndReportsDatabaseFailureWhenRefreshFails() =
        runTest {
            val delegate = FakeConversationRepository()
            val repository = FaultInjectingConversationRepository(delegate)
            val provider =
                ScriptedTextProvider(
                    failure = AiProviderException(AiProviderFailureCode.NETWORK),
                )
            val service = configuredService(repository, provider)
            val conversationId = service.createConversation()
            service.ask("Help me recover", financialContext(), "en")
            val failedMessage = delegate.messages(conversationId).last()
            provider.failure = null
            repository.failList = true

            service.retryMessage(failedMessage.id, financialContext(), "en")

            assertFalse(service.state.value.isLoading)
            assertEquals(AdvisorFailureCode.DATABASE, service.state.value.lastError?.code)
            val retried = delegate.messages(conversationId).last()
            assertEquals(failedMessage.id, retried.id)
            assertEquals(1L, retried.retryCount)
            assertEquals(ConversationMessageStatus.FAILED, retried.status)
            assertEquals(AdvisorFailureCode.DATABASE.name, retried.errorType)
        }

    @Test
    fun failedForcedOverviewKeepsUsefulContentAndDoesNotCacheTheFailure() =
        runTest {
            val repository = FakeConversationRepository()
            val settings = InspectableAdvisorSettings()
            var requestCount = 0
            val provider =
                ScriptedTextProvider(
                    response = AiProviderResponse("Original useful overview."),
                    onRequest = {
                        requestCount++
                        when (requestCount) {
                            1 -> AiProviderResponse("Original useful overview.")
                            2 -> throw AiProviderException(AiProviderFailureCode.NETWORK)
                            else -> AiProviderResponse("Replacement overview with new facts.")
                        }
                    },
                )
            val service = configuredService(repository, provider, settings)
            val originalContext = financialContext()
            val changedContext = originalContext.copy(activeSubscriptions = 3)
            service.ensureOverview(originalContext, "en")
            val fingerprintBeforeFailure = settings.overviewFingerprint()

            service.ensureOverview(changedContext, "en", force = true)

            assertFalse(service.state.value.isOverviewLoading)
            assertEquals(AdvisorFailureCode.NETWORK, service.state.value.lastError?.code)
            assertEquals("Original useful overview.", service.state.value.overview?.content)
            assertEquals(ConversationMessageStatus.FAILED, service.state.value.overview?.status)
            assertEquals(fingerprintBeforeFailure, settings.overviewFingerprint())

            service.ensureOverview(changedContext, "en")

            assertEquals(3, requestCount)
            assertEquals("Replacement overview with new facts.", service.state.value.overview?.content)
            assertEquals(ConversationMessageStatus.COMPLETED, service.state.value.overview?.status)
            assertNotEquals(fingerprintBeforeFailure, settings.overviewFingerprint())
            assertEquals(1, repository.messages(checkNotNull(service.state.value.selectedConversationId)).size)
        }

    private suspend fun configuredService(
        repository: ConversationRepository,
        provider: AiProviderClient,
        settings: InspectableAdvisorSettings = InspectableAdvisorSettings(),
        secretStore: AdvisorSecretStore = InMemoryAdvisorSecretStore(),
    ): OpenAiCompatibleAdvisorService {
        val service =
            OpenAiCompatibleAdvisorService(
                settingsRepository = settings,
                secretStore = secretStore,
                conversationRepository = repository,
                providerClient = provider,
            )
        service.configure(
            AdvisorConfig(
                baseUrl = "https://example.test/v1",
                model = "advisor-model",
                apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
            ),
            "secret",
        )
        return service
    }

    private fun financialContext() =
        AdvisorFinancialContext(
            month = YearMonth(2026, 7),
            summary =
                MonthlySummary(
                    yearMonth = YearMonth(2026, 7),
                    totalIncomeCents = 500_000,
                    plannedTotalCents = 350_000,
                    spentTotalCents = 150_000,
                    remainingTotalCents = 250_000,
                    commitmentsCents = 100_000,
                    perCategory = emptyList(),
                ),
            forecast = emptyList(),
            activeSubscriptions = 1,
            activeInstallments = 1,
            nextFreedCashMonth = YearMonth(2026, 10),
            nextFreedCashCents = 50_000,
            categoryBreakdown = emptyList(),
            previousMonthComparison = null,
            recordedMonthCount = 2,
        )
}

private class FaultInjectingConversationRepository(
    private val delegate: ConversationRepository,
) : ConversationRepository by delegate {
    var failStartExchange: Boolean = false
    var failList: Boolean = false

    override suspend fun startExchange(command: StartConversationExchange): ConversationExchange {
        if (failStartExchange) error("start exchange database failure")
        return delegate.startExchange(command)
    }

    override suspend fun list(includeArchived: Boolean) =
        if (failList) {
            error("conversation list database failure")
        } else {
            delegate.list(includeArchived)
        }
}

private class ScriptedTextProvider(
    var response: AiProviderResponse = AiProviderResponse("A useful answer."),
    var failure: Throwable? = null,
    var beforeResponse: () -> Unit = {},
    var onRequest: (() -> AiProviderResponse)? = null,
) : AiProviderClient {
    override suspend fun generateText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): AiProviderResponse {
        beforeResponse()
        failure?.let { throw it }
        return onRequest?.invoke() ?: response
    }

    override fun streamText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): Flow<AiStreamEvent> = emptyFlow()

    override suspend fun generateStructured(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse = error("Not used")
}

private class InspectableAdvisorSettings : SettingsRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    fun overviewFingerprint(): String? = values.entries.singleOrNull { it.key.startsWith("advisor.overview.fingerprint.") }?.value
}

private class InMemoryAdvisorSecretStore : AdvisorSecretStore {
    private var apiKey: String? = null
    var failReads: Boolean = false

    override suspend fun readApiKey(): String? {
        if (failReads) error("secure storage unavailable")
        return apiKey
    }

    override suspend fun writeApiKey(apiKey: String) {
        this.apiKey = apiKey
    }

    override suspend fun clearApiKey() {
        apiKey = null
    }
}
