package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.MonthlySummary
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorOverviewTest {
    @Test
    fun overviewIsCachedUntilFinancialDataChanges() =
        runTest {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            content = """{"choices":[{"message":{"content":"Stable but commitment-heavy."}}]}""",
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json) }
                }
            val service =
                OpenAiCompatibleAdvisorService(
                    OverviewMemorySettings(),
                    OverviewMemorySecretStore(),
                    client,
                    conversationRepository = FakeConversationRepository(),
                )
            service.configure(
                AdvisorConfig("https://example.com/v1", "advisor-model", AdvisorApiFormat.CHAT_COMPLETIONS),
                "secret",
            )

            service.ensureOverview(context(), "en")
            service.ensureOverview(context(), "en")

            assertEquals(1, requests)
            assertEquals("Stable but commitment-heavy.", service.state.value.overview?.content)

            service.ensureOverview(context().copy(activeSubscriptions = 2), "en")

            assertEquals(2, requests)
        }

    @Test
    fun forcedRefreshBypassesTheOverviewCache() =
        runTest {
            var requests = 0
            val conversations = FakeConversationRepository()
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            content =
                                if (requests == 1) {
                                    """{"choices":[{"message":{"content":"Original overview."}}]}"""
                                } else {
                                    """{"choices":[{"message":{"content":"Updated overview."}}]}"""
                                },
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json) }
                }
            val service =
                OpenAiCompatibleAdvisorService(
                    OverviewMemorySettings(),
                    OverviewMemorySecretStore(),
                    client,
                    conversationRepository = conversations,
                )
            service.configure(
                AdvisorConfig("https://example.com/v1", "advisor-model", AdvisorApiFormat.CHAT_COMPLETIONS),
                "secret",
            )

            service.ensureOverview(context(), "en")
            service.ensureOverview(context(), "en", force = true)

            assertEquals(2, requests)
            assertEquals("Updated overview.", service.state.value.overview?.content)
            assertEquals(1, conversations.messages(checkNotNull(service.state.value.selectedConversationId)).size)
        }

    private fun context() =
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

private class OverviewMemorySettings : SettingsRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

private class OverviewMemorySecretStore : AdvisorSecretStore {
    private var key: String? = null

    override suspend fun readApiKey(): String? = key

    override suspend fun writeApiKey(apiKey: String) {
        key = apiKey
    }

    override suspend fun clearApiKey() {
        key = null
    }
}
