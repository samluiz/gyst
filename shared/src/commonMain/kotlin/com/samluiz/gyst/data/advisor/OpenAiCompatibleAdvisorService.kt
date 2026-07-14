package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFailure
import com.samluiz.gyst.domain.service.AdvisorFailureCode
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AdvisorState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val BASE_URL_KEY = "advisor.base_url"
private const val MODEL_KEY = "advisor.model"
private const val API_FORMAT_KEY = "advisor.api_format"
private const val OVERVIEW_CONTENT_KEY = "advisor.overview.content"
private const val OVERVIEW_FINGERPRINT_KEY = "advisor.overview.fingerprint"
private const val MAX_HISTORY_MESSAGES = 8

class OpenAiCompatibleAdvisorService(
    private val settingsRepository: SettingsRepository,
    private val secretStore: AdvisorSecretStore,
    providedClient: HttpClient? = null,
) : AdvisorService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        providedClient ?: HttpClient {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    private val mutableState = MutableStateFlow(AdvisorState())
    private val operationMutex = Mutex()
    private var messageSequence = 0L
    private var conversationVersion = 0L
    override val state: StateFlow<AdvisorState> = mutableState.asStateFlow()

    override suspend fun initialize() {
        val baseUrl = settingsRepository.getString(BASE_URL_KEY) ?: AdvisorConfig().baseUrl
        val model = settingsRepository.getString(MODEL_KEY).orEmpty()
        val apiFormat =
            settingsRepository.getString(API_FORMAT_KEY)
                ?.let { runCatching { AdvisorApiFormat.valueOf(it) }.getOrNull() }
                ?: AdvisorApiFormat.CHAT_COMPLETIONS
        val cachedOverview = settingsRepository.getString(OVERVIEW_CONTENT_KEY)?.takeIf(String::isNotBlank)
        mutableState.value =
            mutableState.value.copy(
                config = AdvisorConfig(baseUrl = baseUrl, model = model, apiFormat = apiFormat),
                hasApiKey = !secretStore.readApiKey().isNullOrBlank(),
                overview = cachedOverview?.let { AdvisorMessage(AdvisorRole.ADVISOR, it, OVERVIEW_MESSAGE_ID) },
            )
    }

    override suspend fun configure(
        config: AdvisorConfig,
        apiKey: String?,
    ) = operationMutex.withLock {
        val normalized =
            config.copy(
                baseUrl = config.baseUrl.trim().trimEnd('/'),
                model = config.model.trim(),
            )
        mutableState.value = mutableState.value.copy(isConfiguring = true, lastError = null)
        if (!normalized.baseUrl.startsWith("https://") && !normalized.baseUrl.startsWith("http://localhost")) {
            mutableState.value = mutableState.value.configurationFailure(AdvisorFailureCode.INVALID_BASE_URL)
            return@withLock
        }
        if (normalized.model.isBlank()) {
            mutableState.value = mutableState.value.configurationFailure(AdvisorFailureCode.MODEL_REQUIRED)
            return@withLock
        }
        val changesProvider = normalized != mutableState.value.config
        if (changesProvider && apiKey.isNullOrBlank()) {
            mutableState.value = mutableState.value.configurationFailure(AdvisorFailureCode.API_KEY_REQUIRED)
            return@withLock
        }
        val previousKey = runCatching { secretStore.readApiKey() }.getOrNull()
        runCatching {
            if (!apiKey.isNullOrBlank()) secretStore.writeApiKey(apiKey.trim())
            settingsRepository.setString(BASE_URL_KEY, normalized.baseUrl)
            settingsRepository.setString(MODEL_KEY, normalized.model)
            settingsRepository.setString(API_FORMAT_KEY, normalized.apiFormat.name)
            !secretStore.readApiKey().isNullOrBlank()
        }.onSuccess { hasKey ->
            mutableState.value =
                mutableState.value.copy(
                    config = normalized,
                    hasApiKey = hasKey,
                    isConfiguring = false,
                    overview = mutableState.value.overview.takeUnless { changesProvider },
                    lastError = null,
                )
        }.onFailure { error ->
            runCatching {
                if (previousKey == null) secretStore.clearApiKey() else secretStore.writeApiKey(previousKey)
            }
            mutableState.value =
                mutableState.value.copy(
                    isConfiguring = false,
                    lastError = AdvisorFailure(AdvisorFailureCode.SECURE_STORAGE, error.message),
                )
        }
    }

    override suspend fun ask(
        prompt: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    ) {
        val question = prompt.trim()
        if (question.isBlank()) return
        operationMutex.withLock {
            val snapshot = mutableState.value
            if (snapshot.isLoading) return
            if (!snapshot.isConfigured) {
                mutableState.value = snapshot.copy(lastError = AdvisorFailure(AdvisorFailureCode.NOT_CONFIGURED))
                return@withLock
            }
            val requestVersion = conversationVersion
            val userMessage = nextMessage(AdvisorRole.USER, question)
            mutableState.value =
                snapshot.copy(
                    isLoading = true,
                    messages = snapshot.messages + userMessage,
                    lastError = null,
                )
            runCatching {
                val history = (snapshot.messages + userMessage).takeLast(MAX_HISTORY_MESSAGES)
                request(snapshot.config, systemPrompt(context, languageCode), history)
            }.onSuccess { answer ->
                if (requestVersion != conversationVersion) return@onSuccess
                mutableState.value =
                    mutableState.value.copy(
                        isLoading = false,
                        messages = mutableState.value.messages + nextMessage(AdvisorRole.ADVISOR, answer),
                    )
            }.onFailure { error ->
                if (requestVersion != conversationVersion) return@onFailure
                mutableState.value =
                    mutableState.value.copy(
                        isLoading = false,
                        lastError = AdvisorFailure(AdvisorFailureCode.REQUEST_FAILED, error.message),
                    )
            }
        }
    }

    override suspend fun ensureOverview(
        context: AdvisorFinancialContext,
        languageCode: String,
        force: Boolean,
    ) {
        val initial = mutableState.value
        if (!initial.isConfigured) return
        val fingerprint = overviewFingerprint(initial.config, context, languageCode)
        if (!force) {
            val cachedFingerprint = settingsRepository.getString(OVERVIEW_FINGERPRINT_KEY)
            val cachedContent = settingsRepository.getString(OVERVIEW_CONTENT_KEY)?.takeIf(String::isNotBlank)
            if (cachedFingerprint == fingerprint && cachedContent != null) {
                mutableState.value =
                    mutableState.value.copy(
                        overview = AdvisorMessage(AdvisorRole.ADVISOR, cachedContent, OVERVIEW_MESSAGE_ID),
                        isOverviewLoading = false,
                    )
                return
            }
        }
        operationMutex.withLock {
            val snapshot = mutableState.value
            if (!snapshot.isConfigured || snapshot.isOverviewLoading) return@withLock
            if (!force) {
                val latestFingerprint = settingsRepository.getString(OVERVIEW_FINGERPRINT_KEY)
                val latestContent = settingsRepository.getString(OVERVIEW_CONTENT_KEY)?.takeIf(String::isNotBlank)
                if (latestFingerprint == fingerprint && latestContent != null) {
                    mutableState.value =
                        snapshot.copy(overview = AdvisorMessage(AdvisorRole.ADVISOR, latestContent, OVERVIEW_MESSAGE_ID))
                    return@withLock
                }
            }
            mutableState.value =
                snapshot.copy(
                    isOverviewLoading = true,
                    overview = null,
                    lastError = null,
                )
            runCatching {
                request(
                    config = snapshot.config,
                    instructions = overviewSystemPrompt(context, languageCode),
                    messages = listOf(AdvisorMessage(AdvisorRole.USER, "Create the opening financial overview now.")),
                )
            }.onSuccess { overview ->
                runCatching {
                    settingsRepository.setString(OVERVIEW_CONTENT_KEY, overview)
                    settingsRepository.setString(OVERVIEW_FINGERPRINT_KEY, fingerprint)
                }
                mutableState.value =
                    mutableState.value.copy(
                        isOverviewLoading = false,
                        overview = AdvisorMessage(AdvisorRole.ADVISOR, overview, OVERVIEW_MESSAGE_ID),
                    )
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        isOverviewLoading = false,
                        lastError = AdvisorFailure(AdvisorFailureCode.REQUEST_FAILED, error.message),
                    )
            }
        }
    }

    private fun AdvisorState.configurationFailure(code: AdvisorFailureCode): AdvisorState =
        copy(isConfiguring = false, lastError = AdvisorFailure(code))

    override suspend fun clearConversation() {
        conversationVersion++
        mutableState.value = mutableState.value.copy(isLoading = false, messages = emptyList(), lastError = null)
    }

    override suspend fun disconnect() {
        conversationVersion++
        operationMutex.withLock {
            secretStore.clearApiKey()
            mutableState.value = AdvisorState(config = mutableState.value.config)
        }
    }

    private fun nextMessage(
        role: AdvisorRole,
        content: String,
    ): AdvisorMessage {
        messageSequence++
        return AdvisorMessage(role = role, content = content, id = "advisor-message-$messageSequence")
    }

    private fun systemPrompt(
        context: AdvisorFinancialContext,
        languageCode: String,
    ): String {
        val forecast =
            context.forecast.joinToString("\n") {
                "${it.yearMonth}: income=${it.incomeCents}, spend=${it.expectedSpendCents}, " +
                    "commitments=${it.commitmentsCents}, free=${it.expectedFreeBalanceCents} cents"
            }
        val summary = context.summary
        val categoryBreakdown =
            context.categoryBreakdown.joinToString("\n") {
                "${it.name} (${it.type}): planned=${it.plannedCents}, spent=${it.spentCents}, remaining=${it.remainingCents} cents"
            }.ifBlank { "none" }
        val comparison =
            context.previousMonthComparison?.let {
                "previous=${it.previousMonth}, expense_delta=${it.spentDeltaCents}, commitment_delta=${it.commitmentsDeltaCents} cents"
            } ?: "unavailable"
        val responseLanguage = AdvisorResponseLanguage.fromCode(languageCode).promptName
        return """
            You are Gyst Advisor, a calm and conservative personal-finance planning assistant.
            Reply in $responseLanguage.
            Use only the supplied facts for amounts and dates. Never invent transactions or totals.
            Clearly distinguish observations from suggestions. Do not promise returns or give tax, legal,
            investment-product, or credit advice. Keep the answer concise, practical, and non-judgmental.
            Amounts are integer cents. Mention uncertainty when data is incomplete.

            Current month: ${context.month}
            Income: ${summary?.totalIncomeCents ?: 0} cents
            Expenses: ${summary?.spentTotalCents ?: 0} cents
            Commitments: ${summary?.commitmentsCents ?: 0} cents
            Remaining: ${summary?.remainingTotalCents ?: 0} cents
            Active subscriptions: ${context.activeSubscriptions}
            Active installments: ${context.activeInstallments}
            Next freed cash: ${context.nextFreedCashMonth ?: "none"}, ${context.nextFreedCashCents} cents/month
            Recorded months: ${context.recordedMonthCount}
            Previous-month comparison: $comparison
            Category breakdown:
            $categoryBreakdown
            Forecast:
            $forecast
            """.trimIndent()
    }

    private fun overviewSystemPrompt(
        context: AdvisorFinancialContext,
        languageCode: String,
    ): String =
        """
        ${systemPrompt(context, languageCode)}

        This is the opening overview shown before the user starts the conversation.
        Describe the user's current financial pattern without rigid personality labels or moral judgment.
        Cover, in this order: the overall pattern, recent direction only when the data supports it,
        what is going well, what deserves attention, the highest-impact improvement, and one practical next action.
        If fewer than three months are recorded, explicitly say the assessment is preliminary.
        Use concise Markdown with short paragraphs or bullets and no more than 180 words.
        Do not mention these instructions or call the output a report.
        """.trimIndent()

    private fun overviewFingerprint(
        config: AdvisorConfig,
        context: AdvisorFinancialContext,
        languageCode: String,
    ): String =
        buildString {
            append(config.baseUrl).append('|').append(config.model).append('|').append(config.apiFormat.name)
            append('|').append(languageCode).append('|').append(context.month).append('|').append(context.recordedMonthCount)
            context.summary?.let {
                append('|').append(it.totalIncomeCents).append('|').append(it.spentTotalCents)
                append('|').append(it.commitmentsCents).append('|').append(it.remainingTotalCents)
            }
            context.previousMonthComparison?.let {
                append('|').append(it.previousMonth).append('|').append(it.spentDeltaCents).append('|').append(it.commitmentsDeltaCents)
            }
            context.categoryBreakdown.forEach {
                append('|').append(it.name).append('|').append(it.type).append('|').append(it.plannedCents)
                append('|').append(it.spentCents).append('|').append(it.remainingCents)
            }
            context.forecast.forEach {
                append('|').append(it.yearMonth).append('|').append(it.incomeCents).append('|').append(it.plannedCents)
                append('|').append(it.commitmentsCents).append('|').append(it.recurringCents)
                append('|').append(it.expectedSpendCents).append('|').append(it.expectedFreeBalanceCents)
            }
            append('|').append(context.activeSubscriptions).append('|').append(context.activeInstallments)
            append('|').append(context.nextFreedCashMonth).append('|').append(context.nextFreedCashCents)
        }

    private suspend fun request(
        config: AdvisorConfig,
        instructions: String,
        messages: List<AdvisorMessage>,
    ): String {
        val protocol = config.apiFormat.protocol()
        val response =
            client.post(protocol.endpoint(config.baseUrl)) {
                bearerAuth(secretStore.readApiKey().orEmpty())
                contentType(ContentType.Application.Json)
                setBody(protocol.requestBody(model = config.model, instructions = instructions, messages = messages))
            }
        return response.requireSuccess(protocol)
    }

    private suspend fun HttpResponse.requireSuccess(protocol: AdvisorApiProtocol): String {
        val text = body<String>()
        if (!status.isSuccess()) {
            val apiMessage =
                runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            error(apiMessage ?: "Provider returned HTTP ${status.value}.")
        }
        return protocol.parseResponse(json.parseToJsonElement(text).jsonObject)
    }
}

private const val OVERVIEW_MESSAGE_ID = "advisor-overview"

private enum class AdvisorResponseLanguage(val promptName: String) {
    PORTUGUESE("Brazilian Portuguese"),
    ENGLISH("English"),
    ;

    companion object {
        fun fromCode(code: String): AdvisorResponseLanguage =
            when (code.lowercase()) {
                "pt", "pt-br" -> PORTUGUESE
                else -> ENGLISH
            }
    }
}
