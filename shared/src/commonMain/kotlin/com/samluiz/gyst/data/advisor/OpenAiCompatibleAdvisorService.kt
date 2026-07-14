package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.AppendConversationMessage
import com.samluiz.gyst.domain.model.ConversationMessage
import com.samluiz.gyst.domain.model.ConversationMessageRole
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.model.ConversationTitleSource
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.StartConversationExchange
import com.samluiz.gyst.domain.model.sha256
import com.samluiz.gyst.domain.repository.CompleteConversationMessage
import com.samluiz.gyst.domain.repository.ConversationRepository
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFailure
import com.samluiz.gyst.domain.service.AdvisorFailureCode
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorProviderPreset
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AdvisorState
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.DEFAULT_ADVISOR_PROFILE_ID
import com.samluiz.gyst.domain.usecase.id
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

private const val BASE_URL_KEY = "advisor.base_url"
private const val MODEL_KEY = "advisor.model"
private const val API_FORMAT_KEY = "advisor.api_format"
private const val PROVIDER_ID_KEY = "advisor.provider_id"
private const val PROFILE_ID_KEY = "advisor.profile_id"
private const val CAPABILITIES_KEY = "advisor.capabilities"
private const val SELECTED_CONVERSATION_KEY = "advisor.selected_conversation"
private const val OVERVIEW_FINGERPRINT_PREFIX = "advisor.overview.fingerprint."
private const val OPENING_OVERVIEW_METADATA = "advisor-opening-overview"
private const val OPENING_OVERVIEW_EXCHANGE = "opening-overview"

class OpenAiCompatibleAdvisorService(
    private val settingsRepository: SettingsRepository,
    private val secretStore: AdvisorSecretStore,
    providedClient: HttpClient? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val providerProfileRepository: ProviderProfileRepository? = null,
    private val providerClient: AiProviderClient = OpenAiCompatibleProviderClient(providedClient),
) : AdvisorService {
    private val mutableState = MutableStateFlow(AdvisorState())
    private val configurationMutex = Mutex()
    private val overviewMutex = Mutex()
    private val conversationOrchestrator =
        conversationRepository?.let { repository -> AdvisorConversationOrchestrator(repository, providerClient) }
    override val state: StateFlow<AdvisorState> = mutableState.asStateFlow()

    override suspend fun initialize() {
        mutableState.update { it.copy(isConversationListLoading = true, lastError = null) }
        try {
            val config = loadConfig()
            val hasKey = runCatching { !secretStore.readApiKey(config.profileId).isNullOrBlank() }.getOrDefault(false)
            if (hasKey && config.model.isNotBlank()) {
                val existing = providerProfileRepository?.get(config.profileId)
                providerProfileRepository?.upsert(config.toProfile(existing))
            }
            mutableState.update { it.copy(config = config, hasApiKey = hasKey) }
            conversationRepository?.recoverInterrupted(Clock.System.now())
            refreshPersistedState(preferredConversationId = settingsRepository.getString(SELECTED_CONVERSATION_KEY))
        } catch (failure: Throwable) {
            mutableState.update {
                it.copy(lastError = AdvisorFailure(AdvisorFailureCode.DATABASE, failure.safeDetail()))
            }
        } finally {
            mutableState.update { it.copy(isConversationListLoading = false) }
        }
    }

    override suspend fun configure(
        config: AdvisorConfig,
        apiKey: String?,
    ) = configurationMutex.withLock {
        val normalized = config.normalized()
        mutableState.update { it.copy(isConfiguring = true, lastError = null) }
        when {
            !normalized.baseUrl.startsWith("https://") && !normalized.baseUrl.startsWith("http://localhost") -> {
                mutableState.update { it.configurationFailure(AdvisorFailureCode.INVALID_BASE_URL) }
                return@withLock
            }
            normalized.model.isBlank() -> {
                mutableState.update { it.configurationFailure(AdvisorFailureCode.MODEL_REQUIRED) }
                return@withLock
            }
            AiCapability.TEXT_GENERATION !in normalized.capabilities -> {
                mutableState.update { it.configurationFailure(AdvisorFailureCode.UNSUPPORTED_CAPABILITY) }
                return@withLock
            }
        }

        val previousKey = runCatching { secretStore.readApiKey(normalized.profileId) }.getOrNull()
        if (apiKey.isNullOrBlank() && previousKey.isNullOrBlank()) {
            mutableState.update { it.configurationFailure(AdvisorFailureCode.API_KEY_REQUIRED) }
            return@withLock
        }
        val previousProfile = providerProfileRepository?.get(normalized.profileId)
        runCatching {
            if (!apiKey.isNullOrBlank()) secretStore.writeApiKey(normalized.profileId, apiKey.trim())
            providerProfileRepository?.upsert(normalized.toProfile(previousProfile))
            persistConfig(normalized)
            check(!secretStore.readApiKey(normalized.profileId).isNullOrBlank())
        }.onSuccess {
            mutableState.update {
                it.copy(
                    config = normalized,
                    hasApiKey = true,
                    isConfiguring = false,
                    lastError = null,
                )
            }
            refreshPersistedState()
        }.onFailure { error ->
            runCatching {
                if (previousKey == null) {
                    secretStore.clearApiKey(normalized.profileId)
                } else {
                    secretStore.writeApiKey(normalized.profileId, previousKey)
                }
            }
            mutableState.update {
                it.copy(
                    isConfiguring = false,
                    lastError = AdvisorFailure(AdvisorFailureCode.SECURE_STORAGE, error.safeDetail()),
                )
            }
        }
    }

    override suspend fun ask(
        prompt: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    ) {
        val question = prompt.trim()
        if (question.isBlank()) return
        conversationRepository ?: return setDatabaseFailure()
        val orchestrator = conversationOrchestrator ?: return setDatabaseFailure()
        val snapshot = mutableState.value
        if (!snapshot.isConfigured) {
            mutableState.update { it.copy(lastError = AdvisorFailure(AdvisorFailureCode.NOT_CONFIGURED)) }
            return
        }
        val conversationId = snapshot.selectedConversationId ?: createConversation()
        if (conversationId.isBlank()) return
        val config = mutableState.value.config
        val now = Clock.System.now()
        mutableState.update { it.copy(isLoading = true, lastError = null) }
        var operationFailure: AdvisorFailure? = null
        try {
            val apiKey =
                try {
                    secretStore.readApiKey(config.profileId).orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    operationFailure = AdvisorFailure(AdvisorFailureCode.SECURE_STORAGE, failure.safeDetail())
                    return
                }
            if (apiKey.isBlank()) {
                mutableState.update { it.copy(hasApiKey = false) }
                operationFailure = AdvisorFailure(AdvisorFailureCode.NOT_CONFIGURED)
                return
            }
            val outcome =
                orchestrator.generate(
                    command =
                        AdvisorGenerationCommand(
                            exchange =
                                StartConversationExchange(
                                    conversationId = conversationId,
                                    exchangeId = id("advisor-exchange"),
                                    userMessageId = id("advisor-user"),
                                    assistantMessageId = id("advisor-assistant"),
                                    userContent = question,
                                    providerProfileId = config.profileId,
                                    providerId = config.providerId,
                                    modelId = config.model,
                                    createdAt = now,
                                ),
                            config = config,
                            apiKey = apiKey,
                            instructions = AdvisorPromptBuilder.conversation(context, languageCode),
                        ),
                    onPersistedChange = { refreshPersistedState(preferredConversationId = conversationId) },
                )
            refreshPersistedState(preferredConversationId = conversationId)
            operationFailure = outcome.toAdvisorFailure()
        } catch (cancelled: CancellationException) {
            operationFailure = AdvisorFailure(AdvisorFailureCode.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            operationFailure = failure.toDatabaseFailure()
        } finally {
            mutableState.update { current ->
                current.copy(
                    isLoading = false,
                    lastError = operationFailure,
                )
            }
        }
    }

    override suspend fun ensureOverview(
        context: AdvisorFinancialContext,
        languageCode: String,
        force: Boolean,
    ) {
        val repository = conversationRepository ?: return setDatabaseFailure()
        val initial = mutableState.value
        if (!initial.isConfigured) return
        overviewMutex.withLock {
            var conversationId: String? = null
            var opening: ConversationMessage? = null
            var generationStarted = false
            var terminalStatePersisted = false
            try {
                val selectedConversationId = mutableState.value.selectedConversationId ?: createConversation()
                conversationId = selectedConversationId
                if (selectedConversationId.isBlank()) return@withLock
                val config = mutableState.value.config
                val fingerprint = overviewFingerprint(config, context, languageCode)
                val fingerprintKey = OVERVIEW_FINGERPRINT_PREFIX + selectedConversationId
                val persisted = repository.messages(selectedConversationId)
                opening = persisted.lastOrNull { it.attachmentMetadata == OPENING_OVERVIEW_METADATA }
                if (!force && opening?.status == ConversationMessageStatus.COMPLETED &&
                    settingsRepository.getString(fingerprintKey) == fingerprint
                ) {
                    return@withLock
                }
                val activeOpening =
                    opening?.let { existing ->
                        repository.beginRegeneration(
                            messageId = existing.id,
                            providerId = config.providerId,
                            modelId = config.model,
                            updatedAt = Clock.System.now(),
                        )
                    } ?: repository.appendMessage(
                        AppendConversationMessage(
                            id = id("advisor-overview"),
                            conversationId = selectedConversationId,
                            exchangeId = OPENING_OVERVIEW_EXCHANGE,
                            role = ConversationMessageRole.ASSISTANT,
                            content = "",
                            status = ConversationMessageStatus.PENDING,
                            providerProfileId = config.profileId,
                            providerId = config.providerId,
                            modelId = config.model,
                            attachmentMetadata = OPENING_OVERVIEW_METADATA,
                            createdAt = Clock.System.now(),
                        ),
                    )
                opening = activeOpening
                generationStarted = true
                mutableState.update { it.copy(isOverviewLoading = true, lastError = null) }
                refreshPersistedState(preferredConversationId = selectedConversationId)
                val response =
                    try {
                        providerClient.generateText(
                            config = config,
                            apiKey = secretStore.readApiKey(config.profileId).orEmpty(),
                            instructions = AdvisorPromptBuilder.overview(context, languageCode),
                            messages =
                                listOf(
                                    AiMessage(
                                        AiMessageRole.USER,
                                        AdvisorPromptBuilder.overviewRequest(languageCode),
                                    ),
                                ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        val providerFailureCode =
                            (failure as? AiProviderException)?.code
                                ?: AiProviderFailureCode.REQUEST_FAILED
                        repository.fail(
                            messageId = activeOpening.id,
                            partialContent = activeOpening.content,
                            errorType = providerFailureCode.name,
                            safeErrorMessage = providerFailureCode.safePersistedMessage(),
                            updatedAt = Clock.System.now(),
                        )
                        terminalStatePersisted = true
                        mutableState.update { it.copy(lastError = failure.toAdvisorFailure()) }
                        return@withLock
                    }
                repository.complete(
                    CompleteConversationMessage(
                        messageId = activeOpening.id,
                        content = response.content,
                        providerMessageId = response.providerMessageId,
                        inputTokens = response.tokenUsage?.promptTokens,
                        outputTokens = response.tokenUsage?.completionTokens,
                        updatedAt = Clock.System.now(),
                    ),
                )
                terminalStatePersisted = true
                settingsRepository.setString(fingerprintKey, fingerprint)
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(lastError = AdvisorFailure(AdvisorFailureCode.CANCELLED)) }
                throw cancelled
            } catch (failure: Throwable) {
                if (generationStarted && !terminalStatePersisted) {
                    opening?.let { message ->
                        runCatching {
                            repository.fail(
                                messageId = message.id,
                                partialContent = message.content,
                                errorType = AdvisorFailureCode.DATABASE.name,
                                safeErrorMessage = "Conversation persistence failed.",
                                updatedAt = Clock.System.now(),
                            )
                        }
                    }
                }
                mutableState.update { it.copy(lastError = failure.toDatabaseFailure()) }
            } finally {
                mutableState.update { it.copy(isOverviewLoading = false) }
                conversationId?.takeIf(String::isNotBlank)?.let { selectedId ->
                    try {
                        refreshPersistedState(preferredConversationId = selectedId)
                    } catch (_: CancellationException) {
                        // Preserve structured cancellation while still clearing the loading flag.
                    } catch (failure: Throwable) {
                        mutableState.update { it.copy(lastError = failure.toDatabaseFailure()) }
                    }
                }
            }
        }
    }

    override suspend fun clearConversation() {
        createConversation()
    }

    override suspend fun createConversation(title: String?): String {
        val repository = conversationRepository ?: return ""
        return runConversationOperation(default = "") {
            val now = Clock.System.now()
            val conversationId = id("advisor-conversation")
            val trimmedTitle = title?.trim()?.takeIf(String::isNotBlank)
            val config = mutableState.value.config
            repository.create(
                AdvisorConversation(
                    id = conversationId,
                    title = trimmedTitle,
                    titleSource = trimmedTitle?.let { ConversationTitleSource.MANUAL },
                    createdAt = now,
                    updatedAt = now,
                    providerProfileId = config.profileId.takeIf { mutableState.value.isConfigured },
                    lastProviderId = config.providerId.takeIf { mutableState.value.isConfigured },
                    lastModelId = config.model.takeIf { mutableState.value.isConfigured },
                    systemPromptSnapshot = ADVISOR_PROMPT_VERSION,
                ),
            )
            settingsRepository.setString(SELECTED_CONVERSATION_KEY, conversationId)
            refreshPersistedState(preferredConversationId = conversationId)
            conversationId
        }
    }

    override suspend fun selectConversation(conversationId: String) {
        val repository = conversationRepository ?: return
        runConversationOperation(Unit) {
            if (repository.get(conversationId) != null) {
                settingsRepository.setString(SELECTED_CONVERSATION_KEY, conversationId)
                refreshPersistedState(preferredConversationId = conversationId)
            }
        }
    }

    override suspend fun renameConversation(
        conversationId: String,
        title: String,
    ) {
        val normalized = title.trim()
        if (normalized.isBlank()) return
        runConversationOperation(Unit) {
            conversationRepository?.rename(conversationId, normalized, Clock.System.now())
            refreshPersistedState(preferredConversationId = conversationId)
        }
    }

    override suspend fun deleteConversation(conversationId: String) {
        val repository = conversationRepository ?: return
        runConversationOperation(Unit) {
            conversationOrchestrator?.cancel(conversationId)
            repository.delete(conversationId)
            val next = repository.list().firstOrNull()?.id
            settingsRepository.setString(SELECTED_CONVERSATION_KEY, next.orEmpty())
            refreshPersistedState(preferredConversationId = next)
        }
    }

    override suspend fun retryMessage(
        messageId: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    ) {
        val conversationId = mutableState.value.selectedConversationId ?: return
        val repository = conversationRepository ?: return setDatabaseFailure()
        val orchestrator = conversationOrchestrator ?: return setDatabaseFailure()
        val message =
            try {
                repository.messages(conversationId).firstOrNull { it.id == messageId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                setDatabaseFailure(failure)
                return
            } ?: return
        if (message.status !in setOf(ConversationMessageStatus.FAILED, ConversationMessageStatus.CANCELLED)) return
        val config = mutableState.value.config
        mutableState.update { it.copy(isLoading = true, lastError = null) }
        var operationFailure: AdvisorFailure? = null
        try {
            val apiKey =
                try {
                    secretStore.readApiKey(config.profileId).orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    operationFailure = AdvisorFailure(AdvisorFailureCode.SECURE_STORAGE, failure.safeDetail())
                    return
                }
            if (apiKey.isBlank()) {
                mutableState.update { it.copy(hasApiKey = false) }
                operationFailure = AdvisorFailure(AdvisorFailureCode.NOT_CONFIGURED)
                return
            }
            val outcome =
                orchestrator.retry(
                    conversationId = conversationId,
                    assistantMessageId = messageId,
                    config = config,
                    apiKey = apiKey,
                    instructions = AdvisorPromptBuilder.conversation(context, languageCode),
                    onPersistedChange = { refreshPersistedState(preferredConversationId = conversationId) },
                )
            refreshPersistedState(preferredConversationId = conversationId)
            operationFailure = outcome.toAdvisorFailure()
        } catch (cancelled: CancellationException) {
            operationFailure = AdvisorFailure(AdvisorFailureCode.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            operationFailure = failure.toDatabaseFailure()
        } finally {
            mutableState.update { it.copy(isLoading = false, lastError = operationFailure) }
        }
    }

    override suspend fun cancelResponse() {
        mutableState.value.selectedConversationId?.let { conversationOrchestrator?.cancel(it) }
    }

    override suspend fun suspendForDatabaseReplacement() {
        conversationOrchestrator?.cancelAll()
        // Overview and configuration requests run outside the conversation orchestrator. Waiting
        // for their mutexes guarantees their final persistence/secret-store writes have drained.
        overviewMutex.withLock { Unit }
        configurationMutex.withLock { Unit }
    }

    override suspend fun disconnect() {
        val profileId = mutableState.value.config.profileId
        runCatching { secretStore.clearApiKey(profileId) }
            .onFailure { error ->
                mutableState.update { it.copy(lastError = AdvisorFailure(AdvisorFailureCode.SECURE_STORAGE, error.safeDetail())) }
                return
            }
        mutableState.update { it.copy(hasApiKey = false, isLoading = false, isOverviewLoading = false, lastError = null) }
        refreshPersistedState()
    }

    private suspend fun refreshPersistedState(preferredConversationId: String? = mutableState.value.selectedConversationId) {
        val repository = conversationRepository ?: return
        val conversations = repository.list()
        val selectedId =
            preferredConversationId?.takeIf { id -> conversations.any { it.id == id } }
                ?: conversations.firstOrNull()?.id
        val persistedMessages = selectedId?.let { repository.messages(it) }.orEmpty()
        val opening = persistedMessages.lastOrNull { it.attachmentMetadata == OPENING_OVERVIEW_METADATA }
        val messages =
            persistedMessages
                .filterNot { it.attachmentMetadata == OPENING_OVERVIEW_METADATA }
                .map(ConversationMessage::toAdvisorMessage)
        val profiles = providerProfileRepository?.list().orEmpty()
        val candidateProfileIds = (profiles.map { it.id } + mutableState.value.config.profileId).toSet()
        val configuredIds =
            candidateProfileIds.filterTo(mutableSetOf()) { profileId ->
                runCatching { !secretStore.readApiKey(profileId).isNullOrBlank() }.getOrDefault(false)
            }
        mutableState.update { current ->
            current.copy(
                conversations = conversations,
                selectedConversationId = selectedId,
                overview = opening?.takeIf { it.content.isNotBlank() }?.toAdvisorMessage(),
                messages = messages,
                providerProfiles = profiles,
                configuredProfileIds = configuredIds,
                hasApiKey = current.config.profileId in configuredIds,
                isLoading = messages.any { it.status in setOf(ConversationMessageStatus.PENDING, ConversationMessageStatus.STREAMING) },
            )
        }
    }

    private suspend fun loadConfig(): AdvisorConfig {
        val baseUrl = settingsRepository.getString(BASE_URL_KEY) ?: AdvisorConfig().baseUrl
        val model = settingsRepository.getString(MODEL_KEY).orEmpty()
        val apiFormat =
            settingsRepository.getString(API_FORMAT_KEY)
                ?.let { runCatching { AdvisorApiFormat.valueOf(it) }.getOrNull() }
                ?: AdvisorApiFormat.CHAT_COMPLETIONS
        val legacyConfig = AdvisorConfig(baseUrl = baseUrl, model = model, apiFormat = apiFormat)
        val presetConfig = AdvisorProviderPreset.matchingLegacy(legacyConfig)?.config
        val loadedConfig =
            legacyConfig.copy(
                providerId =
                    settingsRepository.getString(PROVIDER_ID_KEY)?.takeIf(String::isNotBlank)
                        ?: presetConfig?.providerId
                        ?: legacyConfig.providerId,
                profileId =
                    settingsRepository.getString(PROFILE_ID_KEY)?.takeIf(String::isNotBlank)
                        ?: DEFAULT_ADVISOR_PROFILE_ID,
                capabilities =
                    settingsRepository.getString(CAPABILITIES_KEY)
                        ?.toCapabilities()
                        ?.takeIf(Set<AiCapability>::isNotEmpty)
                        ?: presetConfig?.capabilities
                        ?: legacyConfig.capabilities,
            )
        val upgradedConfig = AdvisorProviderPreset.upgradeRetiredPreset(loadedConfig)
        if (upgradedConfig != loadedConfig) persistConfig(upgradedConfig)
        return upgradedConfig
    }

    private suspend fun persistConfig(config: AdvisorConfig) {
        settingsRepository.setString(BASE_URL_KEY, config.baseUrl)
        settingsRepository.setString(MODEL_KEY, config.model)
        settingsRepository.setString(API_FORMAT_KEY, config.apiFormat.name)
        settingsRepository.setString(PROVIDER_ID_KEY, config.providerId)
        settingsRepository.setString(PROFILE_ID_KEY, config.profileId)
        settingsRepository.setString(CAPABILITIES_KEY, config.capabilities.toSettingsValue())
    }

    private fun setDatabaseFailure(failure: Throwable? = null) {
        mutableState.update {
            it.copy(
                isLoading = false,
                lastError = failure?.toDatabaseFailure() ?: AdvisorFailure(AdvisorFailureCode.DATABASE),
            )
        }
    }

    private suspend fun <T> runConversationOperation(
        default: T,
        operation: suspend () -> T,
    ): T {
        mutableState.update { it.copy(isConversationListLoading = true, lastError = null) }
        return try {
            operation()
        } catch (failure: Throwable) {
            mutableState.update {
                it.copy(lastError = AdvisorFailure(AdvisorFailureCode.DATABASE, failure.safeDetail()))
            }
            default
        } finally {
            mutableState.update { it.copy(isConversationListLoading = false) }
        }
    }
}

private fun AdvisorConfig.normalized(): AdvisorConfig =
    copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        providerId = providerId.trim().ifBlank { "custom" },
        profileId = profileId.trim().ifBlank { DEFAULT_ADVISOR_PROFILE_ID },
        capabilities = capabilities + AiCapability.TEXT_GENERATION,
    )

private fun AdvisorConfig.toProfile(existing: ProviderProfile?): ProviderProfile {
    val now = Clock.System.now()
    return ProviderProfile(
        id = profileId,
        providerId = providerId,
        displayName = existing?.displayName ?: providerId,
        baseUrl = baseUrl,
        model = model,
        apiFormat = apiFormat.name,
        capabilities = toProviderCapabilities(),
        active = true,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
        lastUsedAt = now,
    )
}

private fun ConversationMessage.toAdvisorMessage(): AdvisorMessage =
    AdvisorMessage(
        role = if (role == ConversationMessageRole.USER) AdvisorRole.USER else AdvisorRole.ADVISOR,
        content = content,
        id = id,
        sequence = sequence,
        status = status,
        providerId = providerId,
        modelId = modelId,
        errorCode = errorType,
        retryCount = retryCount,
    )

private fun AdvisorState.configurationFailure(code: AdvisorFailureCode): AdvisorState =
    copy(isConfiguring = false, lastError = AdvisorFailure(code))

private fun AdvisorGenerationOutcome?.toAdvisorFailure(): AdvisorFailure? =
    when (this) {
        null,
        is AdvisorGenerationOutcome.Completed,
        is AdvisorGenerationOutcome.Cancelled,
        is AdvisorGenerationOutcome.AlreadyRunning,
        -> null
        is AdvisorGenerationOutcome.Failed -> AdvisorFailure(code.toAdvisorFailureCode())
        is AdvisorGenerationOutcome.PersistenceFailed -> AdvisorFailure(AdvisorFailureCode.DATABASE)
    }

private fun Throwable.toAdvisorFailure(): AdvisorFailure {
    val providerFailure = this as? AiProviderException
    return AdvisorFailure(
        code = providerFailure?.code?.toAdvisorFailureCode() ?: AdvisorFailureCode.REQUEST_FAILED,
        detail = safeDetail(),
    )
}

private fun Throwable.toDatabaseFailure(): AdvisorFailure =
    AdvisorFailure(
        code = AdvisorFailureCode.DATABASE,
        detail = safeDetail(),
    )

private fun AiProviderFailureCode.toAdvisorFailureCode(): AdvisorFailureCode =
    when (this) {
        AiProviderFailureCode.AUTHENTICATION -> AdvisorFailureCode.AUTHENTICATION
        AiProviderFailureCode.RATE_LIMITED -> AdvisorFailureCode.RATE_LIMITED
        AiProviderFailureCode.NETWORK -> AdvisorFailureCode.NETWORK
        AiProviderFailureCode.TIMEOUT -> AdvisorFailureCode.TIMEOUT
        AiProviderFailureCode.INVALID_RESPONSE -> AdvisorFailureCode.INVALID_RESPONSE
        AiProviderFailureCode.UNSUPPORTED_CAPABILITY -> AdvisorFailureCode.UNSUPPORTED_CAPABILITY
        AiProviderFailureCode.CANCELLED -> AdvisorFailureCode.CANCELLED
        AiProviderFailureCode.REQUEST_FAILED -> AdvisorFailureCode.REQUEST_FAILED
    }

private fun AiProviderFailureCode.safePersistedMessage(): String =
    when (this) {
        AiProviderFailureCode.AUTHENTICATION -> "Provider authentication failed."
        AiProviderFailureCode.RATE_LIMITED -> "Provider rate limit reached."
        AiProviderFailureCode.NETWORK -> "Provider network request failed."
        AiProviderFailureCode.TIMEOUT -> "Provider request timed out."
        AiProviderFailureCode.INVALID_RESPONSE -> "Provider returned an invalid response."
        AiProviderFailureCode.UNSUPPORTED_CAPABILITY -> "The selected model does not support this operation."
        AiProviderFailureCode.CANCELLED -> "Provider request was cancelled."
        AiProviderFailureCode.REQUEST_FAILED -> "Provider request failed."
    }

private fun Throwable.safeDetail(): String? = message?.replace(Regex("(?i)(?:sk-|key-|bearer )[A-Za-z0-9._-]+"), "[redacted]")?.take(240)

private fun overviewFingerprint(
    config: AdvisorConfig,
    context: AdvisorFinancialContext,
    languageCode: String,
): String =
    sha256(
        buildString {
            append(ADVISOR_PROMPT_VERSION).append('|')
            append(config.profileId).append('|').append(config.model).append('|').append(languageCode).append('|')
            append(AdvisorPromptBuilder.overview(context, languageCode))
        }.encodeToByteArray(),
    )

private fun Set<AiCapability>.toSettingsValue(): String = sortedBy(AiCapability::name).joinToString(",") { it.name }

private fun String.toCapabilities(): Set<AiCapability> =
    split(',').mapNotNull { value -> runCatching { AiCapability.valueOf(value.trim()) }.getOrNull() }.toSet()
