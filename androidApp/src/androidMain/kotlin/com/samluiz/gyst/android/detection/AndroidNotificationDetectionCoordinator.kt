package com.samluiz.gyst.android.detection

import com.samluiz.gyst.data.advisor.toAdvisorConfig
import com.samluiz.gyst.domain.model.CandidateIssueCode
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.MonitoredApplication
import com.samluiz.gyst.domain.model.MonitoredApplicationPolicy
import com.samluiz.gyst.domain.model.NotificationIngestion
import com.samluiz.gyst.domain.model.NotificationProcessingStatus
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.RawTransactionExtraction
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.transactionFingerprint
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.repository.CandidateApprovalFailure
import com.samluiz.gyst.domain.repository.CandidateApprovalRepository
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import com.samluiz.gyst.domain.repository.CategoryRepository
import com.samluiz.gyst.domain.repository.MonitoredApplicationRepository
import com.samluiz.gyst.domain.repository.NotificationIngestionRepository
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.repository.TransactionCandidateRepository
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import com.samluiz.gyst.domain.service.AutomaticDetectionSettingKeys
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionState
import com.samluiz.gyst.domain.service.DetectionApplication
import com.samluiz.gyst.domain.service.DetectionPermissionState
import com.samluiz.gyst.domain.service.DetectionServiceError
import com.samluiz.gyst.domain.service.FinancialNotificationRuleEngine
import com.samluiz.gyst.domain.service.FinancialNotificationRuleResult
import com.samluiz.gyst.domain.service.FinancialNotificationText
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Owns Android framework ingestion while delegating every financial rule to shared domain code.
 * Notification bodies never enter logs or WorkManager Data and are encrypted while waiting for AI.
 */
class AndroidNotificationDetectionCoordinator(
    private val settingsRepository: SettingsRepository,
    private val monitoredApplicationRepository: MonitoredApplicationRepository,
    private val ingestionRepository: NotificationIngestionRepository,
    private val candidateRepository: TransactionCandidateRepository,
    private val candidateApprovalRepository: CandidateApprovalRepository,
    private val categoryRepository: CategoryRepository,
    private val providerProfileRepository: ProviderProfileRepository,
    private val secretStore: AdvisorSecretStore,
    private val providerClient: AiProviderClient,
    private val permissionGateway: DetectionPermissionController,
    private val applicationCatalog: InstalledApplicationSource,
    private val scheduler: NotificationAnalysisScheduling,
    private val notifier: DetectedTransactionNotificationSink,
    private val contentProtector: NotificationContentProtector,
    private val ruleEngine: FinancialNotificationRuleEngine = FinancialNotificationRuleEngine(),
    private val candidateFactory: NotificationCandidateFactory = NotificationCandidateFactory(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutomaticTransactionDetectionService,
    NotificationIngress,
    NotificationAnalysisRunner,
    NotificationListenerLifecycleObserver {
    private val mutableState = MutableStateFlow(AutomaticTransactionDetectionState())
    override val state: StateFlow<AutomaticTransactionDetectionState> = mutableState.asStateFlow()

    private val initializationMutex = Mutex()
    private val ingestionMutex = Mutex()
    private val aiAnalysisMutex = Mutex()
    private val notificationDeliveryMutex = Mutex()
    private val initializationReplayRequested = AtomicBoolean(false)

    @Volatile
    private var runtimePolicy = RuntimePolicy()

    @Volatile
    private var listenerConnected = false

    @Volatile
    private var databaseReplacementSuspended = false

    /** Re-run only for a new process/runtime (or after an atomic database replacement). */
    @Volatile
    private var processRecoveryPending = true

    @Volatile
    private var applicationNotificationPermissionRequester: ((String) -> Unit)? = null

    @Volatile
    private var applicationNotificationShouldShowRationale: (() -> Boolean)? = null

    fun install() {
        AndroidDetectionRuntime.install(
            AndroidDetectionBindings(
                ingress = this,
                lifecycleObserver = this,
                analysisRunner = this,
            ),
        )
        scope.launch { initialize() }
    }

    override suspend fun initialize() {
        initializationMutex.withLock {
            databaseReplacementSuspended = false
            initializationReplayRequested.set(false)
            refreshState(cancelUnavailableWork = false)
            val recoveryRequired = processRecoveryPending
            if (recoveryRequired) {
                ingestionRepository.recoverInterruptedProcessing(Clock.System.now())
            }
            when {
                !runtimePolicy.enabled || !runtimePolicy.listenerAccessGranted -> stopOutstandingAnalysis()
                runtimePolicy.canAnalyzeWithAi -> rescheduleDurableAnalysis(retryPermanentFailures = false)
                else -> replayInterruptedRuleOnlyProcessing()
            }
            if (recoveryRequired) processRecoveryPending = false
            refreshState(cancelUnavailableWork = false)
            requestPostInitializationReplay()
            deliverPendingNotifications()
        }
    }

    override suspend fun suspendProcessingForDatabaseReplacement() {
        databaseReplacementSuspended = true
        processRecoveryPending = true
        runtimePolicy = runtimePolicy.copy(enabled = false)
        scheduler.cancelAll()
        aiAnalysisMutex.withLock { Unit }
        ingestionMutex.withLock { Unit }
    }

    override suspend fun refresh() {
        refreshState()
    }

    override suspend fun installedApplications(): List<DetectionApplication> =
        withContext(Dispatchers.IO) {
            val policies = monitoredApplicationRepository.list().associateBy(MonitoredApplication::packageName)
            applicationCatalog.listLaunchableApplications().map { application ->
                val stored = policies[application.packageName]
                DetectionApplication(
                    packageName = application.packageName,
                    displayName = application.displayName,
                    policy = stored?.policy ?: MonitoredApplicationPolicy.BLOCK,
                    enabled = stored?.enabled == true,
                )
            }
        }

    override suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) runtimePolicy = runtimePolicy.copy(enabled = false)
        settingsRepository.setString(AutomaticDetectionSettingKeys.ENABLED, enabled.toString())
        if (enabled) {
            permissionGateway.requestListenerRebind()
        } else {
            stopOutstandingAnalysis()
            suppressPendingNotifications()
        }
        refreshState()
        if (enabled) deliverPendingNotifications()
    }

    override suspend fun setAiAnalysisEnabled(enabled: Boolean) {
        if (enabled) {
            val error = validateSelectedProvider()
            if (error != null) {
                mutableState.value = mutableState.value.copy(lastError = error)
                return
            }
        } else {
            runtimePolicy = runtimePolicy.copy(aiAnalysisEnabled = false)
            stopOutstandingAnalysis()
        }
        settingsRepository.setString(AutomaticDetectionSettingKeys.AI_ANALYSIS_ENABLED, enabled.toString())
        refreshState()
    }

    override suspend fun setUserNotificationsEnabled(enabled: Boolean) {
        settingsRepository.setString(AutomaticDetectionSettingKeys.USER_NOTIFICATIONS_ENABLED, enabled.toString())
        if (!enabled) suppressPendingNotifications()
        refreshState()
        if (enabled) deliverPendingNotifications()
    }

    override suspend fun selectProviderProfile(profileId: String?) {
        val normalized = profileId?.trim().orEmpty()
        settingsRepository.setString(AutomaticDetectionSettingKeys.PROVIDER_PROFILE_ID, normalized)
        refreshState()
    }

    override suspend fun setApplicationPolicy(
        packageName: String,
        displayName: String,
        policy: MonitoredApplicationPolicy,
        enabled: Boolean,
    ) {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Invalid Android package name" }
        val now = Clock.System.now()
        val existing = monitoredApplicationRepository.get(packageName)
        monitoredApplicationRepository.upsert(
            MonitoredApplication(
                packageName = packageName,
                displayName = displayName.trim().take(MAX_APPLICATION_NAME_LENGTH).ifBlank { packageName },
                policy = policy,
                enabled = enabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        refreshState()
        if (runtimePolicy.enabled) permissionGateway.requestListenerRebind()
    }

    override suspend fun updateSuggestion(candidate: TransactionCandidate) {
        require(candidate.source == CandidateSource.ANDROID_NOTIFICATION)
        val fingerprint =
            transactionFingerprint(
                date = candidate.occurredDate,
                amountCents = candidate.amountCents,
                currency = candidate.currency,
                description = candidate.description,
                account = candidate.accountOrPaymentMethod,
                type = candidate.transactionType,
            )
        val updated = decorateDuplicates(candidate.copy(fingerprint = fingerprint, updatedAt = Clock.System.now()))
        candidateRepository.update(updated)
        updateDeliveredNotification(updated)
        refreshState()
    }

    override suspend fun suggestion(id: String): TransactionCandidate? =
        candidateRepository.get(id)?.takeIf { it.source == CandidateSource.ANDROID_NOTIFICATION }

    override suspend fun approveSuggestion(candidateId: String): CandidateApprovalResult {
        val candidate =
            candidateRepository.get(candidateId)
                ?: return CandidateApprovalResult.Rejected(CandidateApprovalFailure.CANDIDATE_NOT_FOUND)
        if (candidate.source != CandidateSource.ANDROID_NOTIFICATION) {
            return CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_CANDIDATE)
        }
        val now = Clock.System.now()
        val result =
            candidateApprovalRepository.approve(
                ApproveExpenseCandidateCommand(
                    candidateId = candidateId,
                    expenseId = "expense-${UUID.randomUUID()}",
                    originId = "origin-${UUID.randomUUID()}",
                    createdAt = now,
                ),
            )
        if (result is CandidateApprovalResult.Approved || result is CandidateApprovalResult.AlreadyApproved) {
            closeDetectionNotification(candidateId)
            AppLogger.i(TAG, "A notification-derived suggestion was approved")
        }
        refreshState()
        return result
    }

    override suspend fun rejectSuggestion(candidateId: String): Boolean {
        val candidate = candidateRepository.get(candidateId) ?: return false
        if (candidate.source != CandidateSource.ANDROID_NOTIFICATION) return false
        val changed =
            candidateRepository.transitionStatus(
                id = candidateId,
                expectedStatus = CandidateStatus.NEEDS_REVIEW,
                status = CandidateStatus.REJECTED,
                retryCount = candidate.retryCount,
                errorType = null,
                safeErrorMessage = null,
                updatedAt = Clock.System.now(),
            )
        if (changed) {
            closeDetectionNotification(candidateId)
            AppLogger.i(TAG, "A notification-derived suggestion was rejected")
        }
        refreshState()
        return changed
    }

    override suspend fun deleteSuggestion(candidateId: String): Boolean {
        val candidate = candidateRepository.get(candidateId) ?: return true
        if (candidate.source != CandidateSource.ANDROID_NOTIFICATION || candidate.status == CandidateStatus.APPROVED) {
            return false
        }
        candidateRepository.deleteUnapproved(candidateId)
        closeDetectionNotification(candidateId)
        refreshState()
        return candidateRepository.get(candidateId) == null
    }

    override suspend fun retryFailedAnalyses() {
        val error = validateSelectedProvider()
        if (error != null) {
            mutableState.value = mutableState.value.copy(lastError = error)
            return
        }
        rescheduleDurableAnalysis(retryPermanentFailures = true)
        refreshState()
    }

    override suspend fun deleteNotificationDerivedData() {
        scheduler.cancelAll()
        candidateRepository.pendingReview()
            .filter { it.source == CandidateSource.ANDROID_NOTIFICATION }
            .forEach { notifier.cancel(it.id) }
        ingestionRepository.deleteAll()
        refreshState()
    }

    override fun bindApplicationNotificationPermissionRequester(
        requester: ((String) -> Unit)?,
        shouldShowRationale: (() -> Boolean)?,
    ) {
        applicationNotificationPermissionRequester = requester
        applicationNotificationShouldShowRationale = shouldShowRationale
        scope.launch { refreshState() }
    }

    override fun requestApplicationNotificationPermission(): Boolean {
        if (
            mutableState.value.applicationNotificationPermission !=
            DetectionPermissionState.DENIED_CAN_REQUEST
        ) {
            return false
        }
        val requester = applicationNotificationPermissionRequester ?: return false
        scope.launch {
            settingsRepository.setString(
                AutomaticDetectionSettingKeys.POST_NOTIFICATIONS_REQUESTED,
                true.toString(),
            )
        }
        requester(AndroidDetectionPermissionGateway.POST_NOTIFICATIONS_PERMISSION)
        return true
    }

    override fun onApplicationNotificationPermissionResult(granted: Boolean) {
        scope.launch {
            settingsRepository.setString(
                AutomaticDetectionSettingKeys.POST_NOTIFICATIONS_REQUESTED,
                true.toString(),
            )
            refreshState()
            if (granted) deliverPendingNotifications()
        }
    }

    override fun openNotificationAccessSettings(): Boolean = permissionGateway.openNotificationListenerSettings()

    override fun openApplicationNotificationSettings(): Boolean = permissionGateway.openApplicationNotificationSettings()

    override fun shouldCollect(sourcePackage: String): Boolean = runtimePolicy.canCollect(sourcePackage)

    override fun onPosted(envelope: AndroidNotificationEnvelope) {
        scope.launch {
            try {
                processPostedNotification(envelope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                AppLogger.e(TAG, "Notification ingestion failed")
            }
        }
    }

    override fun onRemoved(identity: AndroidNotificationIdentity) {
        // A dismissed source notification must not delete a durable suggestion awaiting review.
    }

    override fun onListenerConnected() {
        listenerConnected = true
        scope.launch {
            refreshState()
            requestPostInitializationReplay()
        }
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        if (runtimePolicy.enabled && runtimePolicy.listenerAccessGranted) {
            permissionGateway.requestListenerRebind()
        }
        scope.launch { refreshState() }
    }

    override suspend fun analyze(
        suggestionId: String,
        runAttemptCount: Int,
    ): NotificationAnalysisOutcome =
        aiAnalysisMutex.withLock {
            analyzeDurableIngestion(suggestionId, runAttemptCount)
        }

    private suspend fun processPostedNotification(envelope: AndroidNotificationEnvelope) {
        ingestionMutex.withLock {
            if (!shouldCollect(envelope.identity.sourcePackage)) return
            val ruleResult =
                ruleEngine.evaluate(
                    FinancialNotificationText(
                        sourcePackage = envelope.identity.sourcePackage,
                        title = envelope.title,
                        text = envelope.text,
                        expandedText = envelope.expandedText,
                        category = envelope.category,
                        channelId = envelope.channelId,
                    ),
                )
            if (ruleResult !is FinancialNotificationRuleResult.Candidate) return

            val fingerprint = AndroidNotificationFingerprint.create(envelope)
            val existing = ingestionRepository.getByFingerprint(fingerprint)
            if (existing != null) {
                if (existing.processingStatus == NotificationProcessingStatus.FAILED && runtimePolicy.canAnalyzeWithAi) {
                    scheduler.schedule(existing.id)
                } else if (
                    !runtimePolicy.canAnalyzeWithAi &&
                    existing.candidateId == null &&
                    existing.processingStatus in RULE_REPLAYABLE_STATUSES
                ) {
                    completeRuleOnlySuggestion(existing, ruleResult)
                }
                return
            }

            val now = Clock.System.now()
            // Persist only encrypted, normalized text. It is needed briefly even in rule-only mode
            // so a process death between ingestion and candidate creation can be replayed safely.
            val protectedProviderText =
                runCatching { contentProtector.protect(ruleResult.normalizedTextForOptionalAi) }
                    .getOrElse {
                        AppLogger.e(TAG, "Notification analysis content could not be protected")
                        return
                    }
            val ingestion =
                NotificationIngestion(
                    id = "ingestion-${fingerprint.take(ID_HASH_LENGTH)}",
                    sourcePackage = envelope.identity.sourcePackage.take(MAX_PACKAGE_LENGTH),
                    notificationId = envelope.identity.notificationId.toLong(),
                    notificationKey = envelope.identity.notificationKey.take(MAX_NOTIFICATION_KEY_LENGTH),
                    notificationFingerprint = fingerprint,
                    postedAt = Instant.fromEpochMilliseconds(envelope.postedAtEpochMillis),
                    title = null,
                    mainText = null,
                    expandedText = null,
                    channelId = envelope.channelId,
                    category = envelope.category,
                    normalizedText = protectedProviderText,
                    processingStatus =
                        if (runtimePolicy.canAnalyzeWithAi) {
                            NotificationProcessingStatus.QUEUED
                        } else {
                            NotificationProcessingStatus.PROCESSING
                        },
                    candidateId = null,
                    retryCount = 0,
                    errorType = null,
                    errorMessage = null,
                    contentRedactedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            val stored = ingestionRepository.insertIdempotently(ingestion)
            if (stored.candidateId != null) return
            if (runtimePolicy.canAnalyzeWithAi) {
                if (!scheduler.schedule(stored.id)) {
                    failAndRedact(stored, "INVALID_WORK_IDENTIFIER", "Analysis could not be scheduled")
                }
            } else {
                completeRuleOnlySuggestion(stored, ruleResult, now)
            }
            refreshState()
        }
    }

    private suspend fun replayInterruptedRuleOnlyProcessing() {
        ingestionMutex.withLock {
            ingestionRepository.queuedForProcessing()
                .filter { ingestion ->
                    ingestion.processingStatus == NotificationProcessingStatus.QUEUED &&
                        ingestion.errorType == PROCESS_INTERRUPTED_ERROR &&
                        ingestion.candidateId == null
                }.forEach { ingestion ->
                    val normalizedText = revealProtectedText(ingestion) ?: return@forEach
                    val result =
                        ruleEngine.evaluate(
                            FinancialNotificationText(
                                sourcePackage = ingestion.sourcePackage,
                                title = "",
                                text = normalizedText,
                            ),
                        )
                    if (result is FinancialNotificationRuleResult.Candidate) {
                        ingestionRepository.updateStatus(
                            id = ingestion.id,
                            status = NotificationProcessingStatus.PROCESSING,
                            candidateId = null,
                            retryCount = ingestion.retryCount,
                            errorType = null,
                            safeErrorMessage = null,
                            updatedAt = Clock.System.now(),
                        )
                        completeRuleOnlySuggestion(ingestion, result)
                    } else {
                        failAndRedact(
                            ingestion,
                            errorType = "LOCAL_REPLAY_REJECTED",
                            safeMessage = "Interrupted local processing could not be reconstructed",
                        )
                    }
                }
        }
    }

    private suspend fun revealProtectedText(ingestion: NotificationIngestion): String? {
        val protectedText = ingestion.normalizedText
        if (protectedText.isNullOrBlank()) {
            failAndRedact(ingestion, "MISSING_CONTENT", "Analysis content is unavailable")
            return null
        }
        return runCatching { contentProtector.reveal(protectedText) }
            .getOrElse {
                failAndRedact(ingestion, "CONTENT_UNAVAILABLE", "Analysis content is unavailable")
                null
            }
    }

    private suspend fun completeRuleOnlySuggestion(
        ingestion: NotificationIngestion,
        ruleResult: FinancialNotificationRuleResult.Candidate,
        createdAt: Instant = Clock.System.now(),
    ) {
        val candidate =
            candidateFactory.create(
                input =
                    NotificationCandidateInput(
                        sourcePackage = ingestion.sourcePackage,
                        notificationFingerprint = ingestion.notificationFingerprint,
                        postedAt = ingestion.postedAt,
                        extraction = ruleResult.extraction,
                    ),
                categories = categoryRepository.list(),
                now = createdAt,
            )
        completeWithSuggestion(ingestion, candidate)
    }

    private suspend fun analyzeDurableIngestion(
        ingestionId: String,
        runAttemptCount: Int,
    ): NotificationAnalysisOutcome {
        refreshState(cancelUnavailableWork = false)
        val ingestion = ingestionRepository.get(ingestionId) ?: return NotificationAnalysisOutcome.PermanentFailure
        if (ingestion.candidateId != null || ingestion.processingStatus == NotificationProcessingStatus.COMPLETED) {
            return NotificationAnalysisOutcome.Completed
        }
        if (!runtimePolicy.canAnalyzeWithAi) {
            cancelAndRedact(ingestion)
            return NotificationAnalysisOutcome.Cancelled
        }
        if (ingestion.retryCount >= NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS) {
            failAndRedact(ingestion, "RETRY_EXHAUSTED", "Analysis retry budget exhausted")
            return NotificationAnalysisOutcome.PermanentFailure
        }
        val protectedText = ingestion.normalizedText
        if (protectedText.isNullOrBlank()) {
            failAndRedact(ingestion, "MISSING_CONTENT", "Analysis content is unavailable")
            return NotificationAnalysisOutcome.PermanentFailure
        }
        val normalizedText =
            runCatching { contentProtector.reveal(protectedText) }
                .getOrElse {
                    failAndRedact(ingestion, "CONTENT_UNAVAILABLE", "Analysis content is unavailable")
                    return NotificationAnalysisOutcome.PermanentFailure
                }
        val profileId = runtimePolicy.providerProfileId
        val profile = profileId?.let { providerProfileRepository.get(it) }
        if (profile == null || !profile.active) {
            failAndRedact(ingestion, "MISSING_PROVIDER", "Select a configured provider")
            return NotificationAnalysisOutcome.PermanentFailure
        }
        if (!profile.capabilities.supports(AI_REQUIRED_CAPABILITIES)) {
            failAndRedact(ingestion, "UNSUPPORTED_PROVIDER", "The selected model lacks structured output")
            return NotificationAnalysisOutcome.PermanentFailure
        }
        val apiKey = secretStore.readApiKey(profile.id)?.takeIf(String::isNotBlank)
        if (apiKey == null) {
            failAndRedact(ingestion, "MISSING_CREDENTIAL", "Add an API key for the selected provider")
            return NotificationAnalysisOutcome.PermanentFailure
        }
        ingestionRepository.updateStatus(
            id = ingestion.id,
            status = NotificationProcessingStatus.PROCESSING,
            candidateId = null,
            retryCount = ingestion.retryCount,
            errorType = null,
            safeErrorMessage = null,
            updatedAt = Clock.System.now(),
        )
        return try {
            val response =
                providerClient.generateStructured(
                    config = profile.toAdvisorConfig(),
                    apiKey = apiKey,
                    instructions = NOTIFICATION_EXTRACTION_INSTRUCTIONS,
                    messages = listOf(AiMessage(AiMessageRole.USER, normalizedText)),
                    images = emptyList(),
                    schema = NOTIFICATION_EXTRACTION_SCHEMA,
                )
            val extracted = decodeExtraction(response.content)
            val deterministic =
                ruleEngine.evaluate(
                    FinancialNotificationText(
                        sourcePackage = ingestion.sourcePackage,
                        title = "",
                        text = normalizedText,
                    ),
                )
            val merged = mergeWithDeterministicExtraction(extracted, deterministic)
            val candidate =
                candidateFactory.create(
                    input =
                        NotificationCandidateInput(
                            sourcePackage = ingestion.sourcePackage,
                            notificationFingerprint = ingestion.notificationFingerprint,
                            postedAt = ingestion.postedAt,
                            extraction = merged,
                            providerId = profile.providerId,
                            modelId = profile.model,
                        ),
                    categories = categoryRepository.list(),
                    now = Clock.System.now(),
                )
            completeWithSuggestion(ingestion, candidate)
            refreshState()
            NotificationAnalysisOutcome.Completed
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelAndRedact(ingestion) }
            NotificationAnalysisOutcome.Cancelled
        } catch (error: AiProviderException) {
            handleProviderFailure(ingestion, error, runAttemptCount)
        } catch (_: Throwable) {
            failAndRedact(ingestion, "INVALID_RESPONSE", "Provider response could not be processed")
            NotificationAnalysisOutcome.PermanentFailure
        }
    }

    private suspend fun completeWithSuggestion(
        ingestion: NotificationIngestion,
        candidate: TransactionCandidate,
    ) {
        val existing = candidateRepository.getByIdempotencyKey(candidate.idempotencyKey)
        val reviewable = existing ?: decorateDuplicates(candidate)
        val stored = ingestionRepository.storeSuggestionAndRedact(ingestion.id, reviewable, Clock.System.now())
        if (existing == null) {
            deliverNotificationIfNeeded(stored)
        } else {
            AppLogger.i(TAG, "duplicate_suggestion_suppressed source=ANDROID_NOTIFICATION")
        }
    }

    private suspend fun decorateDuplicates(candidate: TransactionCandidate): TransactionCandidate {
        val duplicateCandidate =
            candidate.fingerprint?.let { fingerprint ->
                candidateRepository.duplicatesByFingerprint(fingerprint, candidate.id).firstOrNull()
            }
        val duplicateExpenseId = candidateRepository.potentialExistingExpenseIds(candidate).firstOrNull()
        val hasDuplicate = duplicateCandidate != null || duplicateExpenseId != null
        if (hasDuplicate) AppLogger.i(TAG, "duplicate_candidate_flagged source=ANDROID_NOTIFICATION")
        return candidate.copy(
            duplicateCandidateId = duplicateCandidate?.id,
            duplicateExpenseId = duplicateExpenseId,
            warnings =
                if (hasDuplicate) {
                    (candidate.warnings + CandidateIssueCode.POSSIBLE_DUPLICATE.name.lowercase()).distinct()
                } else {
                    candidate.warnings.filterNot { it == CandidateIssueCode.POSSIBLE_DUPLICATE.name.lowercase() }
                },
        )
    }

    private suspend fun handleProviderFailure(
        ingestion: NotificationIngestion,
        failure: AiProviderException,
        runAttemptCount: Int,
    ): NotificationAnalysisOutcome {
        val retryable = failure.code in RETRYABLE_PROVIDER_FAILURES
        val retryCount = maxOf(ingestion.retryCount + 1L, runAttemptCount.toLong() + 1L)
        val exhausted = retryCount >= NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS
        ingestionRepository.updateStatus(
            id = ingestion.id,
            status = NotificationProcessingStatus.FAILED,
            candidateId = null,
            retryCount = retryCount,
            errorType = failure.code.name,
            safeErrorMessage = safeProviderFailureMessage(failure.code),
            updatedAt = Clock.System.now(),
        )
        AppLogger.w(TAG, "BYOK notification extraction failed (${failure.code.name})")
        if (!retryable || exhausted) {
            ingestionRepository.redactContent(ingestion.id, Clock.System.now())
            return NotificationAnalysisOutcome.PermanentFailure
        }
        return NotificationAnalysisOutcome.RetryableFailure
    }

    private suspend fun validateSelectedProvider(): DetectionServiceError? {
        val profileId = mutableState.value.providerProfileId ?: readString(AutomaticDetectionSettingKeys.PROVIDER_PROFILE_ID)
        val profile =
            profileId?.takeIf(String::isNotBlank)?.let { providerProfileRepository.get(it) }
                ?: return DetectionServiceError.MISSING_PROVIDER_CONFIGURATION
        if (!profile.active || !profile.capabilities.supports(AI_REQUIRED_CAPABILITIES)) {
            return DetectionServiceError.UNSUPPORTED_PROVIDER_CAPABILITY
        }
        if (secretStore.readApiKey(profile.id).isNullOrBlank()) {
            return DetectionServiceError.MISSING_PROVIDER_CREDENTIAL
        }
        return null
    }

    private suspend fun refreshState(cancelUnavailableWork: Boolean = true) {
        try {
            val previousState = mutableState.value
            val enabled = readBoolean(AutomaticDetectionSettingKeys.ENABLED, default = false)
            val aiEnabled = readBoolean(AutomaticDetectionSettingKeys.AI_ANALYSIS_ENABLED, default = false)
            val userNotifications =
                readBoolean(AutomaticDetectionSettingKeys.USER_NOTIFICATIONS_ENABLED, default = true)
            val providerProfileId = readString(AutomaticDetectionSettingKeys.PROVIDER_PROFILE_ID)?.takeIf(String::isNotBlank)
            val permissionWasRequested =
                readBoolean(AutomaticDetectionSettingKeys.POST_NOTIFICATIONS_REQUESTED, default = false)
            val permissions =
                permissionGateway.snapshot(
                    permissionWasRequestedBefore = permissionWasRequested,
                    shouldShowRationale =
                        runCatching { applicationNotificationShouldShowRationale?.invoke() }
                            .getOrNull(),
                )
            val monitored = monitoredApplicationRepository.list()
            val compatibleProfiles =
                providerProfileRepository
                    .listSupporting(AI_REQUIRED_CAPABILITIES)
                    .filter { profile ->
                        profile.active &&
                            runCatching { secretStore.readApiKey(profile.id) }
                                .getOrNull()
                                ?.isNotBlank() == true
                    }
            val allowedPackages =
                monitored
                    .filter { it.enabled && it.policy == MonitoredApplicationPolicy.ALLOW }
                    .mapTo(mutableSetOf(), MonitoredApplication::packageName)
            val persistedPolicy =
                RuntimePolicy(
                    enabled = enabled,
                    aiAnalysisEnabled = aiEnabled,
                    userNotificationsEnabled = userNotifications,
                    providerProfileId = providerProfileId,
                    listenerAccessGranted = permissions.notificationListenerAccessGranted,
                    allowedPackages = allowedPackages,
                )
            runtimePolicy =
                if (databaseReplacementSuspended) {
                    persistedPolicy.copy(enabled = false)
                } else {
                    persistedPolicy
                }
            val queued = ingestionRepository.queuedForProcessing()
            val pending =
                candidateRepository.pendingReview().filter { it.source == CandidateSource.ANDROID_NOTIFICATION }
            if (
                previousState.initialized &&
                previousState.notificationListenerAccessGranted != permissions.notificationListenerAccessGranted
            ) {
                AppLogger.i(
                    TAG,
                    "notification_listener_access_changed granted=${permissions.notificationListenerAccessGranted}",
                )
            }
            if (
                previousState.initialized &&
                previousState.applicationNotificationPermission != permissions.applicationNotificationPermission.toDomain()
            ) {
                AppLogger.i(
                    TAG,
                    "application_notification_permission_changed state=${permissions.applicationNotificationPermission.name}",
                )
            }
            mutableState.value =
                AutomaticTransactionDetectionState(
                    isSupported = true,
                    initialized = true,
                    enabled = enabled,
                    aiAnalysisEnabled = aiEnabled,
                    userNotificationsEnabled = userNotifications,
                    providerProfileId = providerProfileId,
                    notificationListenerAccessGranted = permissions.notificationListenerAccessGranted,
                    applicationNotificationsEnabled = permissions.applicationNotificationsEnabled,
                    applicationNotificationPermission = permissions.applicationNotificationPermission.toDomain(),
                    listenerConnected = listenerConnected,
                    monitoredApplications = monitored.map(MonitoredApplication::toDetectionApplication),
                    compatibleProviderProfiles = compatibleProfiles,
                    pendingSuggestions = pending,
                    failedAnalysisCount = queued.count { it.processingStatus == NotificationProcessingStatus.FAILED },
                    lastError = null,
                )
            if (cancelUnavailableWork && (!enabled || !permissions.notificationListenerAccessGranted || !aiEnabled)) {
                stopOutstandingAnalysis()
            }
        } catch (_: Throwable) {
            mutableState.value = mutableState.value.copy(initialized = true, lastError = DetectionServiceError.DATABASE)
            AppLogger.e(TAG, "Automatic transaction detection state refresh failed")
        }
    }

    private suspend fun stopOutstandingAnalysis() {
        scheduler.cancelAll()
        ingestionRepository.queuedForProcessing().forEach { ingestion ->
            ingestionRepository.updateStatus(
                id = ingestion.id,
                status = NotificationProcessingStatus.CANCELLED,
                candidateId = ingestion.candidateId,
                retryCount = ingestion.retryCount,
                errorType = "FEATURE_DISABLED",
                safeErrorMessage = "Automatic analysis was disabled",
                updatedAt = Clock.System.now(),
            )
            ingestionRepository.redactContent(ingestion.id, Clock.System.now())
        }
    }

    private suspend fun rescheduleDurableAnalysis(retryPermanentFailures: Boolean) {
        if (!runtimePolicy.canAnalyzeWithAi) return
        ingestionRepository.queuedForProcessing().forEach { ingestion ->
            val retryableFailure = ingestion.errorType in RETRYABLE_PROVIDER_FAILURES.map(AiProviderFailureCode::name)
            val eligible =
                ingestion.processingStatus != NotificationProcessingStatus.FAILED ||
                    retryPermanentFailures ||
                    retryableFailure
            if (eligible && ingestion.normalizedText != null &&
                ingestion.retryCount < NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS
            ) {
                ingestionRepository.updateStatus(
                    id = ingestion.id,
                    status = NotificationProcessingStatus.QUEUED,
                    candidateId = null,
                    retryCount = ingestion.retryCount,
                    errorType = null,
                    safeErrorMessage = null,
                    updatedAt = Clock.System.now(),
                )
                scheduler.schedule(ingestion.id)
            }
        }
    }

    private suspend fun deliverPendingNotifications() {
        if (!runtimePolicy.enabled || !runtimePolicy.userNotificationsEnabled) return
        candidateRepository.pendingReview()
            .filter { it.source == CandidateSource.ANDROID_NOTIFICATION }
            .forEach { deliverNotificationIfNeeded(it) }
    }

    private suspend fun suppressPendingNotifications() {
        notificationDeliveryMutex.withLock {
            candidateRepository.pendingReview()
                .filter { it.source == CandidateSource.ANDROID_NOTIFICATION }
                .forEach { candidate ->
                    notifier.cancel(candidate.id)
                    settingsRepository.setString(
                        AutomaticDetectionSettingKeys.notificationDelivery(candidate.id),
                        DELIVERY_CLOSED,
                    )
                }
        }
    }

    private suspend fun deliverNotificationIfNeeded(candidate: TransactionCandidate) {
        notificationDeliveryMutex.withLock {
            if (!runtimePolicy.enabled || !runtimePolicy.userNotificationsEnabled) return@withLock
            val markerKey = AutomaticDetectionSettingKeys.notificationDelivery(candidate.id)
            if (settingsRepository.getString(markerKey) == DELIVERY_SENT) return@withLock
            val delivery =
                notifier.notify(
                    DetectedTransactionNotification(
                        suggestionId = candidate.id,
                        formattedAmount = candidate.formattedAmount(),
                        merchantOrDescription = candidate.description,
                    ),
                )
            when (delivery) {
                DetectionNotificationDelivery.SENT -> settingsRepository.setString(markerKey, DELIVERY_SENT)
                DetectionNotificationDelivery.PERMISSION_DENIED,
                DetectionNotificationDelivery.APP_NOTIFICATIONS_DISABLED,
                -> {
                    mutableState.value =
                        mutableState.value.copy(lastError = DetectionServiceError.APPLICATION_NOTIFICATION_PERMISSION_DENIED)
                }
                DetectionNotificationDelivery.DELIVERY_FAILED -> {
                    AppLogger.w(TAG, "Detected-transaction notification delivery failed")
                }
                DetectionNotificationDelivery.INVALID_SUGGESTION_ID -> {
                    AppLogger.e(TAG, "Detected-transaction notification had an invalid local identifier")
                }
            }
        }
    }

    private suspend fun updateDeliveredNotification(candidate: TransactionCandidate) {
        notificationDeliveryMutex.withLock {
            if (!runtimePolicy.enabled || !runtimePolicy.userNotificationsEnabled) return@withLock
            val markerKey = AutomaticDetectionSettingKeys.notificationDelivery(candidate.id)
            if (settingsRepository.getString(markerKey) != DELIVERY_SENT) return@withLock
            notifier.notify(
                DetectedTransactionNotification(
                    suggestionId = candidate.id,
                    formattedAmount = candidate.formattedAmount(),
                    merchantOrDescription = candidate.description,
                ),
            )
        }
    }

    private suspend fun closeDetectionNotification(candidateId: String) {
        notificationDeliveryMutex.withLock {
            notifier.cancel(candidateId)
            settingsRepository.setString(AutomaticDetectionSettingKeys.notificationDelivery(candidateId), DELIVERY_CLOSED)
        }
    }

    private suspend fun cancelAndRedact(ingestion: NotificationIngestion) {
        ingestionRepository.updateStatus(
            id = ingestion.id,
            status = NotificationProcessingStatus.CANCELLED,
            candidateId = ingestion.candidateId,
            retryCount = ingestion.retryCount,
            errorType = "CANCELLED",
            safeErrorMessage = "Analysis was cancelled",
            updatedAt = Clock.System.now(),
        )
        ingestionRepository.redactContent(ingestion.id, Clock.System.now())
    }

    private suspend fun failAndRedact(
        ingestion: NotificationIngestion,
        errorType: String,
        safeMessage: String,
    ) {
        ingestionRepository.updateStatus(
            id = ingestion.id,
            status = NotificationProcessingStatus.FAILED,
            candidateId = ingestion.candidateId,
            retryCount = ingestion.retryCount,
            errorType = errorType,
            safeErrorMessage = safeMessage,
            updatedAt = Clock.System.now(),
        )
        ingestionRepository.redactContent(ingestion.id, Clock.System.now())
        AppLogger.w(TAG, "Notification processing failed ($errorType)")
    }

    private fun mergeWithDeterministicExtraction(
        ai: RawTransactionExtraction,
        deterministic: FinancialNotificationRuleResult,
    ): RawTransactionExtraction {
        val local = (deterministic as? FinancialNotificationRuleResult.Candidate)?.extraction
        return ai.copy(
            description = ai.description ?: local?.description,
            amount = ai.amount ?: local?.amount,
            currency = ai.currency ?: local?.currency,
            transactionType = ai.transactionType ?: local?.transactionType,
            confidence = ai.confidence ?: local?.confidence,
            supportingText = local?.supportingText,
            warnings = (ai.warnings + local?.warnings.orEmpty()).distinct(),
        )
    }

    private fun decodeExtraction(content: String): RawTransactionExtraction {
        val trimmed = content.trim()
        val jsonContent =
            if (trimmed.startsWith("```")) {
                trimmed.lineSequence().drop(1).toList().dropLast(1).joinToString("\n")
            } else {
                trimmed
            }
        return json.decodeFromString(jsonContent)
    }

    private fun TransactionCandidate.formattedAmount(): String? {
        val amount = amountCents ?: return null
        val currencyCode = currency ?: return null
        val currencyInstance = runCatching { Currency.getInstance(currencyCode) }.getOrNull() ?: return null
        return runCatching {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { currency = currencyInstance }
                .format(BigDecimal.valueOf(amount, 2))
        }.getOrNull()
    }

    private suspend fun readBoolean(
        key: String,
        default: Boolean,
    ): Boolean = settingsRepository.getString(key)?.toBooleanStrictOrNull() ?: default

    private suspend fun readString(key: String): String? = settingsRepository.getString(key)

    private fun requestPostInitializationReplay() {
        if (
            runtimePolicy.enabled &&
            runtimePolicy.listenerAccessGranted &&
            initializationReplayRequested.compareAndSet(false, true)
        ) {
            permissionGateway.requestListenerRebind()
        }
    }

    private data class RuntimePolicy(
        val enabled: Boolean = false,
        val aiAnalysisEnabled: Boolean = false,
        val userNotificationsEnabled: Boolean = true,
        val providerProfileId: String? = null,
        val listenerAccessGranted: Boolean = false,
        val allowedPackages: Set<String> = emptySet(),
    ) {
        val canAnalyzeWithAi: Boolean
            get() = enabled && listenerAccessGranted && aiAnalysisEnabled && providerProfileId != null

        fun canCollect(sourcePackage: String): Boolean = enabled && listenerAccessGranted && sourcePackage in allowedPackages
    }

    private companion object {
        const val TAG = "TransactionDetection"
        const val MAX_APPLICATION_NAME_LENGTH = 120
        const val MAX_NOTIFICATION_KEY_LENGTH = 256
        const val MAX_PACKAGE_LENGTH = 180
        const val ID_HASH_LENGTH = 32
        const val DELIVERY_SENT = "sent"
        const val DELIVERY_CLOSED = "closed"
        const val PROCESS_INTERRUPTED_ERROR = "PROCESS_INTERRUPTED"
        val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val RULE_REPLAYABLE_STATUSES =
            setOf(
                NotificationProcessingStatus.PROCESSING,
                NotificationProcessingStatus.QUEUED,
                NotificationProcessingStatus.FAILED,
            )
        val AI_REQUIRED_CAPABILITIES =
            ProviderCapabilities(
                textGeneration = true,
                structuredOutput = true,
            )
        val RETRYABLE_PROVIDER_FAILURES =
            setOf(
                AiProviderFailureCode.RATE_LIMITED,
                AiProviderFailureCode.NETWORK,
                AiProviderFailureCode.TIMEOUT,
            )
        const val NOTIFICATION_EXTRACTION_INSTRUCTIONS =
            "Extract exactly one completed financial transaction from the supplied notification text. " +
                "Do not invent missing values. Keep the amount in its source notation, use ISO YYYY-MM-DD " +
                "only when a date is explicit, and classify the type as expense, income, transfer, refund, or unknown. " +
                "Never return authentication data, card numbers, account numbers, or unrelated notification content."
        val NOTIFICATION_EXTRACTION_SCHEMA =
            AiStructuredOutputSchema(
                name = "notification_transaction",
                jsonSchema = transactionExtractionSchema(),
            )
    }
}

private fun transactionExtractionSchema(): JsonObject {
    val nullableString = JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))))
    val nullableInteger = JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null")))))
    val nullableNumber = JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null")))))
    val properties =
        linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "description" to nullableString,
            "amount" to nullableString,
            "currency" to nullableString,
            "transactionDate" to nullableString,
            "transactionTime" to nullableString,
            "transactionType" to nullableString,
            "suggestedCategory" to nullableString,
            "accountOrPaymentMethod" to nullableString,
            "installmentIndex" to nullableInteger,
            "installmentTotal" to nullableInteger,
            "notes" to nullableString,
            "confidence" to nullableNumber,
            "sourcePage" to nullableString,
            "supportingText" to nullableString,
            "warnings" to
                buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                },
        )
    return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", JsonObject(properties))
        put("required", buildJsonArray { properties.keys.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun ApplicationNotificationPermissionState.toDomain(): DetectionPermissionState =
    when (this) {
        ApplicationNotificationPermissionState.NOT_REQUIRED -> DetectionPermissionState.NOT_REQUIRED
        ApplicationNotificationPermissionState.GRANTED -> DetectionPermissionState.GRANTED
        ApplicationNotificationPermissionState.DENIED_CAN_REQUEST -> DetectionPermissionState.DENIED_CAN_REQUEST
        ApplicationNotificationPermissionState.DENIED_PERMANENTLY -> DetectionPermissionState.DENIED_PERMANENTLY
    }

private fun MonitoredApplication.toDetectionApplication(): DetectionApplication =
    DetectionApplication(
        packageName = packageName,
        displayName = displayName,
        policy = policy,
        enabled = enabled,
    )

private fun safeProviderFailureMessage(code: AiProviderFailureCode): String =
    when (code) {
        AiProviderFailureCode.AUTHENTICATION -> "Provider authentication failed"
        AiProviderFailureCode.RATE_LIMITED -> "Provider rate limit reached"
        AiProviderFailureCode.NETWORK -> "Provider network request failed"
        AiProviderFailureCode.TIMEOUT -> "Provider request timed out"
        AiProviderFailureCode.INVALID_RESPONSE -> "Provider returned an invalid response"
        AiProviderFailureCode.UNSUPPORTED_CAPABILITY -> "Provider capability is unsupported"
        AiProviderFailureCode.CANCELLED -> "Provider request was cancelled"
        AiProviderFailureCode.REQUEST_FAILED -> "Provider request failed"
    }
