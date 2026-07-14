package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.ForecastMonth
import com.samluiz.gyst.domain.model.MonthComparison
import com.samluiz.gyst.domain.model.MonthlySummary
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.YearMonth
import kotlinx.coroutines.flow.StateFlow

data class AdvisorConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiFormat: AdvisorApiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
    val providerId: String = "custom",
    val profileId: String = DEFAULT_ADVISOR_PROFILE_ID,
    val capabilities: Set<AiCapability> = setOf(AiCapability.TEXT_GENERATION),
)

enum class AdvisorApiFormat { CHAT_COMPLETIONS, RESPONSES }

enum class AdvisorRole { USER, ADVISOR }

enum class AdvisorFailureCode {
    INVALID_BASE_URL,
    MODEL_REQUIRED,
    API_KEY_REQUIRED,
    NOT_CONFIGURED,
    SECURE_STORAGE,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    UNSUPPORTED_CAPABILITY,
    CANCELLED,
    DATABASE,
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
    val sequence: Long = 0,
    val status: ConversationMessageStatus = ConversationMessageStatus.COMPLETED,
    val providerId: String? = null,
    val modelId: String? = null,
    val errorCode: String? = null,
    val retryCount: Long = 0,
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
    val conversations: List<AdvisorConversation> = emptyList(),
    val selectedConversationId: String? = null,
    val providerProfiles: List<ProviderProfile> = emptyList(),
    val configuredProfileIds: Set<String> = emptySet(),
    val isConversationListLoading: Boolean = false,
    val lastError: AdvisorFailure? = null,
) {
    val isConfigured: Boolean
        get() = hasApiKey && config.baseUrl.isNotBlank() && config.model.isNotBlank()
}

interface AdvisorSecretStore {
    suspend fun readApiKey(): String?

    suspend fun writeApiKey(apiKey: String)

    suspend fun clearApiKey()

    suspend fun readApiKey(profileId: String): String? = if (profileId == DEFAULT_ADVISOR_PROFILE_ID) readApiKey() else null

    suspend fun writeApiKey(
        profileId: String,
        apiKey: String,
    ) {
        check(profileId == DEFAULT_ADVISOR_PROFILE_ID) { "This secure store does not support provider profiles." }
        writeApiKey(apiKey)
    }

    suspend fun clearApiKey(profileId: String) {
        if (profileId == DEFAULT_ADVISOR_PROFILE_ID) clearApiKey()
    }

    suspend fun clearAllApiKeys() = clearApiKey()
}

const val DEFAULT_ADVISOR_PROFILE_ID = "default"

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

    suspend fun createConversation(title: String? = null): String

    suspend fun selectConversation(conversationId: String)

    suspend fun renameConversation(
        conversationId: String,
        title: String,
    )

    suspend fun deleteConversation(conversationId: String)

    suspend fun retryMessage(
        messageId: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    )

    suspend fun cancelResponse()

    /** Cancels every provider request before the local database file is replaced. */
    suspend fun suspendForDatabaseReplacement() = cancelResponse()

    suspend fun disconnect()
}
