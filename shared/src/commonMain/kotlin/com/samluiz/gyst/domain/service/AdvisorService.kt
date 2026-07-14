package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.ForecastMonth
import com.samluiz.gyst.domain.model.MonthComparison
import com.samluiz.gyst.domain.model.MonthlySummary
import com.samluiz.gyst.domain.model.YearMonth
import kotlinx.coroutines.flow.StateFlow

data class AdvisorConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiFormat: AdvisorApiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
)

enum class AdvisorApiFormat { CHAT_COMPLETIONS, RESPONSES }

enum class AdvisorRole { USER, ADVISOR }

enum class AdvisorFailureCode {
    INVALID_BASE_URL,
    MODEL_REQUIRED,
    API_KEY_REQUIRED,
    NOT_CONFIGURED,
    SECURE_STORAGE,
    REQUEST_FAILED,
}

data class AdvisorFailure(
    val code: AdvisorFailureCode,
    val detail: String? = null,
)

data class AdvisorMessage(
    val role: AdvisorRole,
    val content: String,
    val id: String = "",
)

data class AdvisorCategoryContext(
    val name: String,
    val type: String,
    val plannedCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
)

data class AdvisorExpenseContext(
    val description: String,
    val category: String,
    val occurredAt: String,
    val amountCents: Long,
    val recurring: Boolean,
)

data class AdvisorCommitmentContext(
    val name: String,
    val kind: String,
    val monthlyCents: Long,
    val endMonth: YearMonth?,
)

data class AdvisorFinancialContext(
    val month: YearMonth,
    val summary: MonthlySummary?,
    val forecast: List<ForecastMonth>,
    val activeSubscriptions: Int,
    val activeInstallments: Int,
    val nextFreedCashMonth: YearMonth?,
    val nextFreedCashCents: Long,
    val categoryBreakdown: List<AdvisorCategoryContext>,
    val largestExpenses: List<AdvisorExpenseContext> = emptyList(),
    val commitments: List<AdvisorCommitmentContext> = emptyList(),
    val previousMonthComparison: MonthComparison?,
    val recordedMonthCount: Int,
)

data class AdvisorState(
    val config: AdvisorConfig = AdvisorConfig(),
    val hasApiKey: Boolean = false,
    val isConfiguring: Boolean = false,
    val isOverviewLoading: Boolean = false,
    val isLoading: Boolean = false,
    val overview: AdvisorMessage? = null,
    val messages: List<AdvisorMessage> = emptyList(),
    val lastError: AdvisorFailure? = null,
) {
    val isConfigured: Boolean
        get() = hasApiKey && config.baseUrl.isNotBlank() && config.model.isNotBlank()
}

interface AdvisorSecretStore {
    suspend fun readApiKey(): String?

    suspend fun writeApiKey(apiKey: String)

    suspend fun clearApiKey()
}

interface AdvisorService {
    val state: StateFlow<AdvisorState>

    suspend fun initialize()

    suspend fun configure(
        config: AdvisorConfig,
        apiKey: String?,
    )

    suspend fun ask(
        prompt: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    )

    suspend fun ensureOverview(
        context: AdvisorFinancialContext,
        languageCode: String,
        force: Boolean = false,
    )

    suspend fun clearConversation()

    suspend fun disconnect()
}
