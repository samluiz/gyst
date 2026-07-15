package com.samluiz.gyst.data.importer

import com.samluiz.gyst.data.advisor.toAdvisorConfig
import com.samluiz.gyst.domain.model.CandidateDraft
import com.samluiz.gyst.domain.model.CandidateIssueCode
import com.samluiz.gyst.domain.model.CandidateIssueSeverity
import com.samluiz.gyst.domain.model.CandidateNormalizationContext
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.CandidateValidationIssue
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.RawTransactionExtraction
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.TransactionImportSession
import com.samluiz.gyst.domain.model.TransactionImportSource
import com.samluiz.gyst.domain.model.candidateLowConfidenceFields
import com.samluiz.gyst.domain.model.inferExpenseCategory
import com.samluiz.gyst.domain.model.inferExpensePaymentMethod
import com.samluiz.gyst.domain.model.normalizeExtraction
import com.samluiz.gyst.domain.model.normalizeTransactionText
import com.samluiz.gyst.domain.model.sha256
import com.samluiz.gyst.domain.model.transactionFingerprint
import com.samluiz.gyst.domain.model.validateCandidateForExpenseApproval
import com.samluiz.gyst.domain.model.validateCandidatePreview
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.repository.CandidateApprovalRepository
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import com.samluiz.gyst.domain.repository.CategoryRepository
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import com.samluiz.gyst.domain.repository.TransactionCandidateRepository
import com.samluiz.gyst.domain.repository.TransactionImportRepository
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.ImageImportFailure
import com.samluiz.gyst.domain.service.ImageImportFailureCode
import com.samluiz.gyst.domain.service.ImageImportImage
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.domain.service.ImageImportStage
import com.samluiz.gyst.domain.service.ImageImportState
import com.samluiz.gyst.domain.service.ImageImportSummary
import com.samluiz.gyst.domain.service.ImageSourceFailure
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.ImageSourceService
import com.samluiz.gyst.domain.service.ReviewableTransactionCandidate
import com.samluiz.gyst.domain.service.TEMPORARY_IMAGE_TTL_MILLIS
import com.samluiz.gyst.domain.service.TemporaryImageHandle
import com.samluiz.gyst.domain.service.TransactionCandidateEdit
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

class DefaultImageImportService(
    private val imageSourceService: ImageSourceService,
    private val providerProfileRepository: ProviderProfileRepository,
    private val secretStore: AdvisorSecretStore,
    private val aiProviderClient: AiProviderClient,
    private val importRepository: TransactionImportRepository,
    private val candidateRepository: TransactionCandidateRepository,
    private val approvalRepository: CandidateApprovalRepository,
    private val categoryRepository: CategoryRepository,
    private val now: () -> Instant = { Clock.System.now() },
    private val idFactory: (String) -> String = ::id,
    private val extractionParser: ImageTransactionExtractionParser = ImageTransactionExtractionParser(),
) : ImageImportService {
    private val mutableState =
        MutableStateFlow(
            ImageImportState(
                canSelectImages = imageSourceService.capabilities.canSelectImages,
                canCaptureImage = imageSourceService.capabilities.canCaptureImage,
                maximumSelection = imageSourceService.capabilities.maximumSelection,
            ),
        )
    override val state: StateFlow<ImageImportState> = mutableState.asStateFlow()

    private val mutationMutex = Mutex()
    private val initializationMutex = Mutex()
    private var handles: List<TemporaryImageHandle> = emptyList()
    private var operationId: String? = null
    private var activeAnalysisJob: Job? = null
    private var lastAnalysisRequest: AnalysisRequest? = null
    private var databaseReplacementSuspended = false

    override suspend fun initialize() =
        initializationMutex.withLock {
            try {
                try {
                    imageSourceService.cleanupExpired()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Expired temporary files are retried on the next initialization.
                }
                recoverInterruptedSessions()
                cleanupExpiredDraftSessions()
                val profiles = compatibleProfiles()
                val sessions = importRepository.list()
                val ready = sessions.firstOrNull { it.status == ImportSessionStatus.READY }
                if (ready != null) {
                    restoreReadyPreview(ready, profiles)
                } else {
                    val retryable =
                        sessions.firstOrNull {
                            it.status in
                                setOf(
                                    ImportSessionStatus.CREATED,
                                    ImportSessionStatus.FAILED,
                                    ImportSessionStatus.CANCELLED,
                                )
                        }
                    val restored = retryable != null && restoreRetryableSession(retryable, profiles)
                    if (!restored) {
                        handles = emptyList()
                        operationId = null
                        lastAnalysisRequest = null
                        mutableState.value = emptyState(compatibleProfiles = profiles)
                    }
                }
                recoverPendingSourcesLocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(ImageImportFailureCode.DATABASE, retryable = true)
            }
        }

    private suspend fun restoreReadyPreview(
        session: TransactionImportSession,
        profiles: List<ProviderProfile>,
    ) {
        handles = emptyList()
        operationId = session.id
        lastAnalysisRequest = null
        val candidates = candidateRepository.byImportSession(session.id)
        val images = importRepository.sources(session.id).map { it.toStateImage(isLocallyAvailable = false) }
        mutableState.value =
            emptyState(compatibleProfiles = profiles).copy(
                stage = ImageImportStage.PREVIEW,
                images = images,
                sessionId = session.id,
                selectedProviderProfileId = session.providerProfileId,
                progress = 1f,
                candidates = candidates.toReviewable(),
            )
    }

    private suspend fun restoreRetryableSession(
        session: TransactionImportSession,
        profiles: List<ProviderProfile>,
    ): Boolean {
        val sources = importRepository.sources(session.id)
        if (sources.isEmpty()) {
            importRepository.delete(session.id)
            return false
        }
        val expected = sources.mapNotNull(TransactionImportSource::toTemporaryHandle)
        val restored = imageSourceService.restoreAvailable(expected)
        val availableReferences = restored.mapTo(mutableSetOf(), TemporaryImageHandle::temporaryReference)
        val allAvailable =
            expected.size == sources.size &&
                expected.all { it.temporaryReference in availableReferences }
        if (!allAvailable) {
            cleanupTemporary(restored)
            importRepository.delete(session.id)
            handles = emptyList()
            operationId = null
            lastAnalysisRequest = null
            mutableState.value =
                emptyState(compatibleProfiles = profiles).copy(
                    stage = ImageImportStage.SOURCES_SELECTED,
                    images = sources.map { it.toStateImage(isLocallyAvailable = false) },
                    failure = ImageImportFailure(ImageImportFailureCode.IMAGE_SOURCE, retryable = false),
                )
            return true
        }
        handles = expected
        operationId = session.id
        lastAnalysisRequest =
            session.providerProfileId?.let {
                AnalysisRequest(
                    providerProfileId = it,
                    localeTag = session.localeTag,
                    defaultCurrency = session.defaultCurrency,
                    fallbackCategoryName =
                        categoryRepository.list().firstOrNull { category ->
                            category.id == IMAGE_IMPORT_FALLBACK_CATEGORY_ID
                        }?.name ?: DEFAULT_FALLBACK_CATEGORY_NAME,
                )
            }
        mutableState.value =
            emptyState(compatibleProfiles = profiles).copy(
                stage = ImageImportStage.SOURCES_SELECTED,
                images = expected.map(TemporaryImageHandle::toStateImage),
                sessionId = session.id,
                selectedProviderProfileId = session.providerProfileId,
                failure =
                    if (session.providerProfileId == null && session.status == ImportSessionStatus.CREATED) {
                        null
                    } else {
                        ImageImportFailure(ImageImportFailureCode.INTERRUPTED, retryable = true)
                    },
            )
        return true
    }

    private fun emptyState(compatibleProfiles: List<ProviderProfile> = emptyList()): ImageImportState =
        ImageImportState(
            canSelectImages = imageSourceService.capabilities.canSelectImages,
            canCaptureImage = imageSourceService.capabilities.canCaptureImage,
            maximumSelection = imageSourceService.capabilities.maximumSelection,
            compatibleProfiles = compatibleProfiles,
        )

    override suspend fun selectImages() {
        val result =
            try {
                imageSourceService.selectImages()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(ImageImportFailureCode.IMAGE_SOURCE, retryable = true)
                return
            }
        when (result) {
            ImageSourceResult.Cancelled -> Unit
            is ImageSourceResult.Failed -> showImageSourceFailure(result.reason)
            is ImageSourceResult.Selected -> acceptAndAcknowledgeImages(result.images)
        }
    }

    override suspend fun captureImage() {
        val result =
            try {
                imageSourceService.captureImage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(ImageImportFailureCode.IMAGE_SOURCE, retryable = true)
                return
            }
        when (result) {
            ImageSourceResult.Cancelled -> Unit
            is ImageSourceResult.Failed -> showImageSourceFailure(result.reason)
            is ImageSourceResult.Selected -> acceptAndAcknowledgeImages(result.images)
        }
    }

    private suspend fun acceptAndAcknowledgeImages(images: List<TemporaryImageHandle>) {
        val persisted = acceptImages(images)
        if (!persisted) return
        try {
            imageSourceService.acknowledgeRecoveredImages(images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Durable queued results are replay-safe until platform acknowledgement succeeds.
        }
    }

    override suspend fun recoverPendingSources() =
        initializationMutex.withLock {
            recoverPendingSourcesLocked()
        }

    private suspend fun recoverPendingSourcesLocked() {
        val recovered =
            try {
                imageSourceService.pendingRecoveredImages()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(ImageImportFailureCode.IMAGE_SOURCE, retryable = true)
                return
            }
        if (recovered.isEmpty()) return
        acceptAndAcknowledgeImages(recovered)
    }

    override suspend fun removeImage(imageId: String) {
        mutationMutex.withLock {
            if (activeAnalysisJob != null) return
            val removed = handles.filter { it.id == imageId }
            if (removed.isEmpty()) {
                removeUnavailableImage(imageId)
                return
            }
            val remaining = handles.filterNot { it.id == imageId }
            val previousOperationId = operationId
            operationId = idFactory("image-import-operation")
            if (remaining.isEmpty()) {
                try {
                    abandonPersistedDraft()
                    handles = emptyList()
                    lastAnalysisRequest = null
                    cleanupTemporary(removed)
                    publishSources(session = null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    operationId = previousOperationId
                    showFailure(ImageImportFailureCode.DATABASE, retryable = true)
                }
                return
            }
            try {
                val session = persistSourceDraft(remaining, replacesSessionId = mutableState.value.sessionId)
                handles = remaining
                lastAnalysisRequest = null
                cleanupTemporary(removed)
                publishSources(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationId = previousOperationId
                showFailure(ImageImportFailureCode.DATABASE, retryable = true)
            }
        }
    }

    private fun removeUnavailableImage(imageId: String) {
        val current = mutableState.value
        if (current.images.none { it.id == imageId }) return
        val remaining = current.images.filterNot { it.id == imageId }
        operationId = null
        lastAnalysisRequest = null
        mutableState.value =
            current.copy(
                stage = if (remaining.isEmpty()) ImageImportStage.IDLE else ImageImportStage.SOURCES_SELECTED,
                images = remaining,
                sessionId = null,
                progress = 0f,
                failure = current.failure.takeIf { remaining.isNotEmpty() },
            )
    }

    override suspend fun analyze(
        providerProfileId: String,
        localeTag: String,
        defaultCurrency: String,
        fallbackCategoryName: String,
    ) {
        val job = currentCoroutineContext().job
        val request = AnalysisRequest(providerProfileId, localeTag, defaultCurrency.uppercase(), fallbackCategoryName)
        val selectedHandles =
            mutationMutex.withLock {
                if (activeAnalysisJob != null) {
                    showFailure(ImageImportFailureCode.DUPLICATE_OPERATION, retryable = true)
                    return@withLock null
                }
                if (databaseReplacementSuspended) return@withLock null
                if (handles.isEmpty()) {
                    showFailure(ImageImportFailureCode.NO_IMAGES, retryable = false)
                    return@withLock null
                }
                activeAnalysisJob = job
                lastAnalysisRequest = request
                handles
            } ?: return

        var session: TransactionImportSession? = null
        var imageInputs: List<AiImageInput> = emptyList()
        try {
            val profile = requireCompatibleProfile(providerProfileId)
            val apiKey =
                try {
                    secretStore.readApiKey(profile.id)?.trim().orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw ImageImportOperationException(
                        ImageImportFailureCode.PROVIDER_NOT_CONFIGURED,
                        retryable = true,
                    )
                }
            if (apiKey.isEmpty()) throw ImageImportOperationException(ImageImportFailureCode.PROVIDER_NOT_CONFIGURED)

            val categories = categoryRepository.list()
            val fallbackCategory = resolveFallbackCategory(categories, request.fallbackCategoryName)
            session = prepareSession(profile, request, selectedHandles)
            prepareSessionForAnalysis(session)
            val defaultDate = now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            mutableState.update {
                it.copy(
                    stage = ImageImportStage.ANALYZING,
                    sessionId = session.id,
                    selectedProviderProfileId = profile.id,
                    progress = 0.05f,
                    candidates = emptyList(),
                    summary = null,
                    failure = null,
                )
            }
            imageInputs = readImages(selectedHandles)
            val response =
                aiProviderClient.generateStructured(
                    config = profile.toAdvisorConfig(),
                    apiKey = apiKey,
                    instructions =
                        imageExtractionInstructions(
                            localeTag = request.localeTag,
                            defaultCurrency = request.defaultCurrency,
                            defaultDate = defaultDate.toString(),
                            categoryNames = categories.map { it.name },
                            sourceIds = selectedHandles.map { it.id },
                        ),
                    messages = emptyList(),
                    images = imageInputs,
                    schema = imageTransactionExtractionSchema,
                )
            mutableState.update { it.copy(progress = 0.7f) }
            val rawRows = extractionParser.parse(response.content)
            val candidates =
                buildCandidates(
                    session,
                    profile,
                    selectedHandles,
                    rawRows,
                    request,
                    categories,
                    fallbackCategory,
                    defaultDate,
                )
            val persistedCandidates = candidateRepository.insertAllAtomically(candidates)
            importRepository.updateStatus(
                id = session.id,
                status = ImportSessionStatus.READY,
                selectedCount = persistedCandidates.count(TransactionCandidate::selected).toLong(),
                importedCount = 0,
                errorType = null,
                safeErrorMessage = null,
                updatedAt = now(),
                completedAt = null,
            )
            mutableState.update {
                it.copy(
                    stage = ImageImportStage.PREVIEW,
                    progress = 1f,
                    candidates = persistedCandidates.toReviewable(),
                    failure = null,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val replacingDatabase = mutationMutex.withLock { databaseReplacementSuspended }
                if (!replacingDatabase) {
                    session?.markFailedOrCancelled(ImportSessionStatus.CANCELLED, "CANCELLED")
                }
                mutableState.update {
                    it.copy(
                        stage = ImageImportStage.CANCELLED,
                        failure = ImageImportFailure(ImageImportFailureCode.CANCELLED, retryable = true),
                    )
                }
            }
            throw cancelled
        } catch (failure: AiProviderException) {
            val diagnostic = failure.safeDiagnosticCode()
            AppLogger.w(
                LOG_TAG,
                buildString {
                    append("analysis_failed provider_code=${failure.code.name}")
                    failure.httpStatusCode?.let { append(" http_status=$it") }
                    failure.providerErrorCode?.let { append(" provider_error_code=$it") }
                },
            )
            session?.markFailedOrCancelled(
                status = ImportSessionStatus.FAILED,
                errorType = diagnostic,
                safeErrorMessage = failure.safeProviderMessage(),
            )
            showProviderFailure(failure)
        } catch (_: InvalidImageExtractionException) {
            AppLogger.w(LOG_TAG, "analysis_failed code=INVALID_STRUCTURED_RESPONSE")
            session?.markFailedOrCancelled(ImportSessionStatus.FAILED, "INVALID_STRUCTURED_RESPONSE")
            showFailure(ImageImportFailureCode.INVALID_STRUCTURED_RESPONSE, retryable = true)
        } catch (failure: ImageImportOperationException) {
            AppLogger.w(LOG_TAG, "analysis_failed code=${failure.code.name}")
            session?.markFailedOrCancelled(ImportSessionStatus.FAILED, failure.code.name)
            showFailure(failure.code, retryable = failure.retryable)
        } catch (_: Exception) {
            AppLogger.e(LOG_TAG, "analysis_failed code=DATABASE")
            session?.markFailedOrCancelled(ImportSessionStatus.FAILED, "DATABASE")
            showFailure(ImageImportFailureCode.DATABASE, retryable = true)
        } finally {
            imageInputs.forEach { it.bytes.fill(0) }
            mutationMutex.withLock {
                if (activeAnalysisJob == job) activeAnalysisJob = null
            }
        }
    }

    override suspend fun retryAnalysis() {
        val request = lastAnalysisRequest
        if (request == null) {
            showFailure(ImageImportFailureCode.NO_IMAGES, retryable = false)
            return
        }
        analyze(request.providerProfileId, request.localeTag, request.defaultCurrency, request.fallbackCategoryName)
    }

    override suspend fun cancelAnalysis() {
        val job = mutationMutex.withLock { activeAnalysisJob }
        job?.cancel(CancellationException("Image analysis cancelled by user"))
        job?.join()
    }

    override suspend fun updateCandidate(
        candidateId: String,
        edit: TransactionCandidateEdit,
    ) {
        mutationMutex.withLock {
            val current = candidateRepository.get(candidateId) ?: return
            if (current.status != CandidateStatus.NEEDS_REVIEW) return
            persistEdited(current.applyEdit(edit))
            current.importSessionId?.let { recomputeSessionDuplicates(it) }
            refreshCandidates(current.importSessionId)
        }
    }

    override suspend fun setCandidateSelected(
        candidateId: String,
        selected: Boolean,
    ) {
        mutationMutex.withLock {
            val current = candidateRepository.get(candidateId) ?: return
            if (current.status != CandidateStatus.NEEDS_REVIEW) return
            candidateRepository.update(current.copy(selected = selected, updatedAt = now()))
            refreshCandidates(current.importSessionId)
        }
    }

    override suspend fun addCandidate(edit: TransactionCandidateEdit) {
        mutationMutex.withLock {
            val sessionId = mutableState.value.sessionId ?: return
            val session = importRepository.get(sessionId) ?: return
            if (session.status != ImportSessionStatus.READY) return
            val current = candidateRepository.byImportSession(sessionId)
            val categories = categoryRepository.list()
            val defaultCategory = inferExpenseCategory(categories, null, edit.description, null)
            val defaultCurrency = session.defaultCurrency.takeUnless { it == UNDEFINED_CURRENCY } ?: "BRL"
            val defaultDate = now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val candidateId = idFactory("transaction-candidate")
            val candidate =
                TransactionCandidate(
                    id = candidateId,
                    importSessionId = sessionId,
                    source = CandidateSource.IMAGE,
                    sourceReference = "manual-review-row",
                    sourcePage = null,
                    rowOrder = (current.maxOfOrNull { it.rowOrder } ?: -1L) + 1L,
                    idempotencyKey = "image:$sessionId:manual:$candidateId",
                    fingerprint = null,
                    description = defaultCategory?.name,
                    amountCents = null,
                    currency = defaultCurrency,
                    occurredDate = defaultDate,
                    occurredTime = null,
                    timeZoneId = null,
                    transactionType = CandidateTransactionType.EXPENSE,
                    suggestedCategoryId = defaultCategory?.id,
                    accountOrPaymentMethod = PaymentMethod.DEBIT.name,
                    installmentIndex = null,
                    installmentTotal = null,
                    note = null,
                    confidence = null,
                    sourceImageHash = null,
                    supportingText = null,
                    warnings = emptyList(),
                    lowConfidenceFields = emptySet(),
                    selected = true,
                    status = CandidateStatus.NEEDS_REVIEW,
                    duplicateCandidateId = null,
                    duplicateExpenseId = null,
                    linkedExpenseId = null,
                    providerId = session.providerId,
                    modelId = session.modelId,
                    retryCount = 0,
                    errorType = null,
                    errorMessage = null,
                    createdAt = now(),
                    updatedAt = now(),
                ).applyEdit(
                    edit.copy(
                        description = edit.description?.takeIf(String::isNotBlank) ?: defaultCategory?.name,
                        currency = edit.currency?.takeIf(String::isNotBlank) ?: defaultCurrency,
                        occurredDate = edit.occurredDate ?: defaultDate,
                        transactionType = CandidateTransactionType.EXPENSE,
                        suggestedCategoryId = edit.suggestedCategoryId ?: defaultCategory?.id,
                        accountOrPaymentMethod = edit.accountOrPaymentMethod ?: PaymentMethod.DEBIT.name,
                    ),
                )
            candidateRepository.insert(candidate)
            persistEdited(candidate)
            recomputeSessionDuplicates(sessionId)
            refreshCandidates(sessionId)
        }
    }

    override suspend fun deleteCandidate(candidateId: String) {
        mutationMutex.withLock {
            val candidate = candidateRepository.get(candidateId) ?: return
            candidateRepository.deleteUnapproved(candidateId)
            candidate.importSessionId?.let { recomputeSessionDuplicates(it) }
            refreshCandidates(candidate.importSessionId)
        }
    }

    override suspend fun applyCategoryToSelected(categoryId: String) {
        editSelected {
            it.copy(
                suggestedCategoryId = categoryId,
                suggestedCategoryLabel = null,
                warnings = it.warnings - WARNING_DEFAULT_CATEGORY,
                lowConfidenceFields = it.lowConfidenceFields - "category",
            )
        }
    }

    override suspend fun applyPaymentMethodToSelected(paymentMethod: String) {
        val normalized = normalizePaymentMethod(paymentMethod) ?: paymentMethod.trim().uppercase()
        editSelected { it.copy(accountOrPaymentMethod = normalized) }
    }

    override suspend fun confirmImport() {
        mutationMutex.withLock {
            val sessionId = mutableState.value.sessionId ?: return
            val candidates = candidateRepository.byImportSession(sessionId).filter(TransactionCandidate::selected)
            if (candidates.isEmpty() || candidates.any { it.reviewIssues().hasErrors() }) {
                AppLogger.w(LOG_TAG, "import_validation_failed")
                mutableState.update {
                    it.copy(failure = ImageImportFailure(ImageImportFailureCode.VALIDATION, retryable = false))
                }
                return
            }
            mutableState.update { it.copy(stage = ImageImportStage.IMPORTING, failure = null) }
            val commands =
                candidates.map { candidate ->
                    val stableSuffix = sha256(candidate.id.encodeToByteArray()).take(24)
                    ApproveExpenseCandidateCommand(
                        candidateId = candidate.id,
                        expenseId = "expense-import-$stableSuffix",
                        originId = "expense-origin-$stableSuffix",
                        createdAt = now(),
                        categoryToCreate = fallbackCategoryFor(candidate, lastAnalysisRequest?.fallbackCategoryName),
                    )
                }
            val results =
                try {
                    approvalRepository.approveImportAtomically(sessionId, commands, now())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AppLogger.e(LOG_TAG, "import_persistence_failed")
                    mutableState.update {
                        it.copy(
                            stage = ImageImportStage.PREVIEW,
                            failure = ImageImportFailure(ImageImportFailureCode.DATABASE, retryable = true),
                        )
                    }
                    return
                }
            if (results.any { it is CandidateApprovalResult.Rejected }) {
                AppLogger.w(LOG_TAG, "import_atomic_batch_rejected")
                mutableState.update {
                    it.copy(
                        stage = ImageImportStage.PREVIEW,
                        failure = ImageImportFailure(ImageImportFailureCode.VALIDATION, retryable = false),
                    )
                }
                return
            }
            val imported = results.count { it is CandidateApprovalResult.Approved }
            val existing = results.count { it is CandidateApprovalResult.AlreadyApproved }
            AppLogger.i(LOG_TAG, "import_completed imported=$imported idempotent=$existing")
            cleanupTemporary(handles)
            handles = emptyList()
            val refreshed = candidateRepository.byImportSession(sessionId)
            mutableState.update {
                it.copy(
                    stage = ImageImportStage.COMPLETED,
                    images = emptyList(),
                    progress = 1f,
                    candidates = refreshed.toReviewable(),
                    summary = ImageImportSummary(candidates.size, imported, existing),
                    failure = null,
                )
            }
        }
    }

    override suspend fun cancelImport() {
        cancelAnalysis()
        mutationMutex.withLock {
            val sessionId = mutableState.value.sessionId
            if (sessionId != null) {
                val hasApprovedRows =
                    candidateRepository.byImportSession(sessionId).any { it.status == CandidateStatus.APPROVED }
                if (!hasApprovedRows) importRepository.delete(sessionId)
            }
            cleanupTemporary(handles)
            handles = emptyList()
            mutableState.update {
                it.copy(
                    stage = ImageImportStage.CANCELLED,
                    images = emptyList(),
                    candidates = emptyList(),
                    failure = ImageImportFailure(ImageImportFailureCode.CANCELLED, retryable = false),
                )
            }
        }
    }

    override suspend fun suspendForDatabaseReplacement() {
        val job =
            mutationMutex.withLock {
                databaseReplacementSuspended = true
                activeAnalysisJob
            }
        job?.cancel(CancellationException("Database replacement requested"))
        job?.join()
        mutationMutex.withLock {
            cleanupTemporary(handles)
            handles = emptyList()
            operationId = null
            lastAnalysisRequest = null
            activeAnalysisJob = null
            mutableState.value =
                ImageImportState(
                    canSelectImages = imageSourceService.capabilities.canSelectImages,
                    canCaptureImage = imageSourceService.capabilities.canCaptureImage,
                    maximumSelection = imageSourceService.capabilities.maximumSelection,
                )
            databaseReplacementSuspended = false
        }
    }

    override suspend fun clear() {
        if (mutableState.value.stage !in setOf(ImageImportStage.COMPLETED, ImageImportStage.IDLE)) {
            cancelImport()
        }
        mutationMutex.withLock {
            cleanupTemporary(handles)
            handles = emptyList()
            operationId = null
            lastAnalysisRequest = null
            mutableState.value =
                ImageImportState(
                    canSelectImages = imageSourceService.capabilities.canSelectImages,
                    canCaptureImage = imageSourceService.capabilities.canCaptureImage,
                    maximumSelection = imageSourceService.capabilities.maximumSelection,
                    compatibleProfiles = compatibleProfiles(),
                )
        }
    }

    private suspend fun acceptImages(newImages: List<TemporaryImageHandle>): Boolean {
        if (newImages.isEmpty()) return true
        return mutationMutex.withLock {
            if (activeAnalysisJob != null || databaseReplacementSuspended) return@withLock false
            val previousHandles = handles
            val combined = (previousHandles + newImages).distinctBy { it.sha256 }
            val accepted = combined.take(imageSourceService.capabilities.maximumSelection)
            val rejected = (previousHandles + newImages).filter { incoming -> accepted.none { it.id == incoming.id } }
            if (accepted.map { it.id } == previousHandles.map { it.id }) {
                cleanupRejectedImages(rejected, accepted)
                return@withLock true
            }
            val previousOperationId = operationId
            val previousAnalysisRequest = lastAnalysisRequest
            operationId = idFactory("image-import-operation")
            try {
                val session = persistSourceDraft(accepted, replacesSessionId = mutableState.value.sessionId)
                handles = accepted
                lastAnalysisRequest = null
                cleanupRejectedImages(rejected, accepted)
                publishSources(session)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationId = previousOperationId
                lastAnalysisRequest = previousAnalysisRequest
                showFailure(ImageImportFailureCode.DATABASE, retryable = true)
                false
            }
        }
    }

    private suspend fun cleanupRejectedImages(
        rejected: Collection<TemporaryImageHandle>,
        retained: Collection<TemporaryImageHandle>,
    ) {
        val retainedReferences = retained.mapTo(mutableSetOf(), TemporaryImageHandle::temporaryReference)
        cleanupTemporary(rejected.filterNot { it.temporaryReference in retainedReferences })
    }

    private suspend fun abandonPersistedDraft() {
        val sessionId = mutableState.value.sessionId ?: return
        val hasApprovedRows = candidateRepository.byImportSession(sessionId).any { it.status == CandidateStatus.APPROVED }
        if (!hasApprovedRows) importRepository.delete(sessionId)
    }

    private fun publishSources(session: TransactionImportSession?) {
        mutableState.update {
            it.copy(
                stage = if (handles.isEmpty()) ImageImportStage.IDLE else ImageImportStage.SOURCES_SELECTED,
                images = handles.map(TemporaryImageHandle::toStateImage),
                sessionId = session?.id,
                selectedProviderProfileId = session?.providerProfileId,
                progress = 0f,
                candidates = emptyList(),
                summary = null,
                failure = null,
            )
        }
    }

    private suspend fun compatibleProfiles(): List<ProviderProfile> =
        providerProfileRepository.listSupporting(REQUIRED_IMAGE_CAPABILITIES)
            .filter(ProviderProfile::active)
            .filter { profile -> hasConfiguredCredential(profile.id) }

    private suspend fun hasConfiguredCredential(profileId: String): Boolean =
        try {
            !secretStore.readApiKey(profileId).isNullOrBlank()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun requireCompatibleProfile(profileId: String): ProviderProfile {
        val profile =
            providerProfileRepository.get(profileId)
                ?: throw ImageImportOperationException(ImageImportFailureCode.PROVIDER_NOT_FOUND, retryable = false)
        if (!profile.active || !profile.capabilities.supports(REQUIRED_IMAGE_CAPABILITIES)) {
            throw ImageImportOperationException(
                ImageImportFailureCode.UNSUPPORTED_PROVIDER_CAPABILITY,
                retryable = false,
            )
        }
        return profile
    }

    private suspend fun prepareSession(
        profile: ProviderProfile,
        request: AnalysisRequest,
        selectedHandles: List<TemporaryImageHandle>,
    ): TransactionImportSession {
        val sessionId =
            mutableState.value.sessionId
                ?: throw ImageImportOperationException(ImageImportFailureCode.DATABASE, retryable = true)
        val existing =
            importRepository.get(sessionId)
                ?: throw ImageImportOperationException(ImageImportFailureCode.DATABASE, retryable = true)
        val persistedHashes = importRepository.sources(sessionId).map(TransactionImportSource::sourceHash)
        if (persistedHashes != selectedHandles.map(TemporaryImageHandle::sha256)) {
            throw ImageImportOperationException(ImageImportFailureCode.DATABASE, retryable = true)
        }
        return importRepository.configureAnalysis(
            id = existing.id,
            providerProfileId = profile.id,
            providerId = profile.providerId,
            modelId = profile.model,
            localeTag = request.localeTag,
            defaultCurrency = request.defaultCurrency,
            updatedAt = now(),
        )
    }

    private suspend fun persistSourceDraft(
        selectedHandles: List<TemporaryImageHandle>,
        replacesSessionId: String?,
    ): TransactionImportSession {
        require(selectedHandles.isNotEmpty())
        val createdAt = now()
        val currentOperationId = operationId ?: idFactory("image-import-operation").also { operationId = it }
        val idempotencyPayload =
            buildString {
                append(currentOperationId)
                selectedHandles.forEach { append('|').append(it.sha256) }
            }
        val session =
            TransactionImportSession(
                id = idFactory("transaction-import"),
                idempotencyKey = "image-import:${sha256(idempotencyPayload.encodeToByteArray())}",
                status = ImportSessionStatus.CREATED,
                providerProfileId = null,
                providerId = null,
                modelId = null,
                localeTag = UNDEFINED_LOCALE_TAG,
                defaultCurrency = UNDEFINED_CURRENCY,
                allowPartial = false,
                selectedCount = 0,
                importedCount = 0,
                errorType = null,
                errorMessage = null,
                createdAt = createdAt,
                updatedAt = createdAt,
                completedAt = null,
            )
        val sources =
            selectedHandles.mapIndexed { index, handle ->
                TransactionImportSource(
                    id = stableImportSourceId(session.id, handle.sha256),
                    importSessionId = session.id,
                    sourceHash = handle.sha256,
                    sourceOrder = index.toLong(),
                    mediaType = handle.mimeType,
                    displayName = handle.displayName,
                    byteSize = handle.byteSize,
                    temporaryReference = handle.temporaryReference,
                    createdAt = createdAt,
                )
            }
        return try {
            importRepository.replaceSourceDraft(replacesSessionId, session, sources)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            importRepository.getByIdempotencyKey(session.idempotencyKey)?.let { persisted ->
                val persistedHashes = importRepository.sources(persisted.id).map(TransactionImportSource::sourceHash)
                if (persistedHashes == selectedHandles.map(TemporaryImageHandle::sha256)) return persisted
            }
            throw failure
        }
    }

    private suspend fun prepareSessionForAnalysis(session: TransactionImportSession) {
        importRepository.beginAnalysis(session.id, now())
    }

    private suspend fun readImages(selectedHandles: List<TemporaryImageHandle>): List<AiImageInput> {
        val count = selectedHandles.size.coerceAtLeast(1)
        val inputs = mutableListOf<AiImageInput>()
        try {
            selectedHandles.forEachIndexed { index, handle ->
                val bytes =
                    imageSourceService.readBytes(handle)
                mutableState.update { it.copy(progress = 0.1f + (0.35f * (index + 1) / count)) }
                inputs += AiImageInput(sourceId = handle.id, mimeType = handle.mimeType, bytes = bytes)
            }
        } catch (cancelled: CancellationException) {
            inputs.forEach { it.bytes.fill(0) }
            throw cancelled
        } catch (_: Exception) {
            inputs.forEach { it.bytes.fill(0) }
            throw ImageImportOperationException(ImageImportFailureCode.IMAGE_SOURCE)
        }
        return inputs
    }

    private suspend fun buildCandidates(
        session: TransactionImportSession,
        profile: ProviderProfile,
        selectedHandles: List<TemporaryImageHandle>,
        rows: List<RawTransactionExtraction>,
        request: AnalysisRequest,
        categories: List<Category>,
        fallbackCategory: Category,
        defaultDate: LocalDate,
    ): List<TransactionCandidate> {
        val sourceByReference =
            selectedHandles.flatMap { handle ->
                listOf(handle.id, handle.displayName, handle.sha256).map { it to handle }
            }.toMap()
        val priorSourceHashes =
            selectedHandles.associate { handle ->
                handle.sha256 to
                    importRepository.priorSources(handle.sha256).any { it.importSessionId != session.id }
            }
        val firstCandidateByFingerprint = mutableMapOf<String, String>()
        return rows.mapIndexedNotNull { index, raw ->
            val sourceHandle = raw.sourcePage?.let(sourceByReference::get) ?: selectedHandles.singleOrNull()
            val aggregateSourceHash =
                selectedHandles.takeIf { sourceHandle == null }
                    ?.map(TemporaryImageHandle::sha256)
                    ?.sorted()
                    ?.joinToString("|")
                    ?.encodeToByteArray()
                    ?.let(::sha256)
            val sourceReference = sourceHandle?.id ?: "image-import-session:${session.id}"
            val sourceHash = sourceHandle?.sha256 ?: aggregateSourceHash
            val normalization =
                normalizeExtraction(
                    raw = raw,
                    context =
                        CandidateNormalizationContext(
                            source = CandidateSource.IMAGE,
                            sourceReference = sourceReference,
                            sourceImageHash = sourceHash,
                            defaultCurrency = request.defaultCurrency,
                            localeTag = request.localeTag,
                            defaultDate = defaultDate,
                        ),
                )
            val draft = normalization.candidate
            if (draft.transactionType in NON_EXPENSE_TYPES ||
                draft.transactionType == CandidateTransactionType.UNKNOWN && raw.transactionType != null
            ) {
                return@mapIndexedNotNull null
            }
            val stableRowKey = sha256("${session.id}|$sourceHash|$index".encodeToByteArray())
            val candidateId = "transaction-candidate-${stableRowKey.take(24)}"
            val inferredCategory =
                inferExpenseCategory(
                    categories = categories,
                    suggestedName = draft.suggestedCategoryName,
                    description = draft.description,
                    supportingText = draft.supportingText,
                ) ?: fallbackCategory
            val usedFallbackCategory = inferredCategory.id == fallbackCategory.id
            val categoryId = inferredCategory.id.takeIf { id -> categories.any { it.id == id } }
            val categoryLabel = inferredCategory.name.takeIf { categoryId == null }
            val paymentMethod =
                normalizePaymentMethod(draft.accountOrPaymentMethod)
                    ?: inferExpensePaymentMethod(
                        draft.accountOrPaymentMethod,
                        draft.description,
                        draft.supportingText,
                    ).name
            val description = draft.description ?: inferredCategory.name
            val fingerprint =
                transactionFingerprint(
                    date = draft.occurredDate,
                    amountCents = draft.amountCents,
                    currency = draft.currency,
                    description = description,
                    account = paymentMethod,
                    type = CandidateTransactionType.EXPENSE,
                )
            var candidate =
                TransactionCandidate(
                    id = candidateId,
                    importSessionId = session.id,
                    source = CandidateSource.IMAGE,
                    sourceReference = draft.sourceReference,
                    sourcePage = draft.sourcePage,
                    rowOrder = index.toLong(),
                    idempotencyKey = "image:${session.id}:$stableRowKey",
                    fingerprint = fingerprint,
                    description = description,
                    amountCents = draft.amountCents,
                    currency = draft.currency,
                    occurredDate = draft.occurredDate,
                    occurredTime = draft.occurredTime,
                    timeZoneId = draft.timeZoneId,
                    transactionType = CandidateTransactionType.EXPENSE,
                    suggestedCategoryId = categoryId,
                    suggestedCategoryLabel = categoryLabel,
                    accountOrPaymentMethod = paymentMethod,
                    installmentIndex = draft.installmentIndex,
                    installmentTotal = draft.installmentTotal,
                    note = draft.note,
                    confidence = draft.confidence,
                    sourceImageHash = draft.sourceImageHash,
                    supportingText = draft.supportingText,
                    warnings =
                        buildList {
                            addAll(draft.warnings)
                            if (usedFallbackCategory) add(WARNING_DEFAULT_CATEGORY)
                            if (sourceHandle == null) add(WARNING_AMBIGUOUS_SOURCE)
                            if (sourceHandle?.let { priorSourceHashes[it.sha256] } == true) {
                                add(WARNING_REPEATED_SOURCE)
                            }
                        }.distinct(),
                    lowConfidenceFields = draft.lowConfidenceFields,
                    selected = normalization.issues.none { it.code == CandidateIssueCode.POSSIBLE_SUMMARY_ROW },
                    status = CandidateStatus.NEEDS_REVIEW,
                    duplicateCandidateId = fingerprint?.let(firstCandidateByFingerprint::get),
                    duplicateExpenseId = null,
                    linkedExpenseId = null,
                    providerId = profile.providerId,
                    modelId = profile.model,
                    retryCount = 0,
                    errorType = null,
                    errorMessage = null,
                    createdAt = now(),
                    updatedAt = now(),
                )
            if (candidate.duplicateCandidateId == null && candidate.fingerprint != null) {
                candidate =
                    candidate.copy(
                        duplicateCandidateId =
                            candidateRepository.duplicatesByFingerprint(candidate.fingerprint, candidate.id)
                                .firstOrNull()
                                ?.id,
                    )
            }
            candidate =
                candidate.copy(
                    duplicateExpenseId = candidateRepository.potentialExistingExpenseIds(candidate).firstOrNull(),
                )
            if (candidate.duplicateCandidateId != null || candidate.duplicateExpenseId != null) {
                AppLogger.i(LOG_TAG, "duplicate_candidate_flagged source=IMAGE")
                candidate = candidate.copy(warnings = (candidate.warnings + WARNING_POSSIBLE_DUPLICATE).distinct())
            }
            candidate.fingerprint?.let { fingerprint ->
                if (fingerprint !in firstCandidateByFingerprint) {
                    firstCandidateByFingerprint[fingerprint] = candidate.id
                }
            }
            candidate
        }
    }

    private fun resolveFallbackCategory(
        categories: List<Category>,
        requestedName: String,
    ): Category {
        categories.firstOrNull { it.id == IMAGE_IMPORT_FALLBACK_CATEGORY_ID }?.let { return it }
        categories.firstOrNull {
            normalizeTransactionText(it.name) in GENERIC_CATEGORY_NAMES
        }?.let { return it }
        return Category(
            id = IMAGE_IMPORT_FALLBACK_CATEGORY_ID,
            name = requestedName.trim().take(MAX_FALLBACK_CATEGORY_NAME_LENGTH).ifBlank { DEFAULT_FALLBACK_CATEGORY_NAME },
            type = CategoryType.VARIABLE,
        )
    }

    private fun fallbackCategoryFor(
        candidate: TransactionCandidate,
        requestedName: String?,
    ): Category? =
        candidate.suggestedCategoryLabel
            ?.takeIf { candidate.suggestedCategoryId == null && WARNING_DEFAULT_CATEGORY in candidate.warnings }
            ?.let { label ->
                Category(
                    id = IMAGE_IMPORT_FALLBACK_CATEGORY_ID,
                    name = requestedName?.trim()?.take(MAX_FALLBACK_CATEGORY_NAME_LENGTH).orEmpty().ifBlank { label },
                    type = CategoryType.VARIABLE,
                )
            }

    private suspend fun persistEdited(candidate: TransactionCandidate) {
        val fingerprint =
            transactionFingerprint(
                date = candidate.occurredDate,
                amountCents = candidate.amountCents,
                currency = candidate.currency,
                description = candidate.description,
                account = candidate.accountOrPaymentMethod,
                type = candidate.transactionType,
            )
        var updated =
            candidate.copy(
                fingerprint = fingerprint,
                duplicateCandidateId = null,
                duplicateExpenseId = null,
                warnings = candidate.warnings.filterNot { it == WARNING_POSSIBLE_DUPLICATE },
                updatedAt = now(),
            )
        if (fingerprint != null) {
            updated =
                updated.copy(
                    duplicateCandidateId =
                        candidateRepository.duplicatesByFingerprint(fingerprint, candidate.id).firstOrNull()?.id,
                )
        }
        updated =
            updated.copy(duplicateExpenseId = candidateRepository.potentialExistingExpenseIds(updated).firstOrNull())
        if (updated.duplicateCandidateId != null || updated.duplicateExpenseId != null) {
            updated = updated.copy(warnings = updated.warnings + WARNING_POSSIBLE_DUPLICATE)
        }
        candidateRepository.update(updated)
    }

    private suspend fun editSelected(transform: (TransactionCandidate) -> TransactionCandidate) {
        mutationMutex.withLock {
            val sessionId = mutableState.value.sessionId ?: return
            candidateRepository.byImportSession(sessionId)
                .filter { it.selected && it.status == CandidateStatus.NEEDS_REVIEW }
                .forEach { persistEdited(transform(it)) }
            recomputeSessionDuplicates(sessionId)
            refreshCandidates(sessionId)
        }
    }

    private suspend fun recomputeSessionDuplicates(sessionId: String) {
        candidateRepository.byImportSession(sessionId)
            .filter { it.status == CandidateStatus.NEEDS_REVIEW }
            .forEach { candidate ->
                val duplicateCandidateId =
                    candidate.fingerprint?.let { fingerprint ->
                        candidateRepository.duplicatesByFingerprint(fingerprint, candidate.id).firstOrNull()?.id
                    }
                val duplicateExpenseId = candidateRepository.potentialExistingExpenseIds(candidate).firstOrNull()
                val hasDuplicate = duplicateCandidateId != null || duplicateExpenseId != null
                val warnings =
                    if (hasDuplicate) {
                        (candidate.warnings + WARNING_POSSIBLE_DUPLICATE).distinct()
                    } else {
                        candidate.warnings.filterNot { it == WARNING_POSSIBLE_DUPLICATE }
                    }
                if (
                    candidate.duplicateCandidateId != duplicateCandidateId ||
                    candidate.duplicateExpenseId != duplicateExpenseId ||
                    candidate.warnings != warnings
                ) {
                    candidateRepository.update(
                        candidate.copy(
                            duplicateCandidateId = duplicateCandidateId,
                            duplicateExpenseId = duplicateExpenseId,
                            warnings = warnings,
                            updatedAt = now(),
                        ),
                    )
                }
            }
    }

    private suspend fun refreshCandidates(sessionId: String?) {
        if (sessionId == null) return
        val candidates = candidateRepository.byImportSession(sessionId)
        mutableState.update { it.copy(candidates = candidates.toReviewable(), failure = null) }
    }

    private suspend fun recoverInterruptedSessions() {
        importRepository.list()
            .filter { it.status in setOf(ImportSessionStatus.ANALYZING, ImportSessionStatus.IMPORTING) }
            .forEach { session ->
                val recoveredCandidates = candidateRepository.byImportSession(session.id)
                val recoveredPreview =
                    session.status == ImportSessionStatus.ANALYZING &&
                        recoveredCandidates.any { it.status == CandidateStatus.NEEDS_REVIEW }
                importRepository.updateStatus(
                    id = session.id,
                    status = if (recoveredPreview) ImportSessionStatus.READY else ImportSessionStatus.FAILED,
                    selectedCount = recoveredCandidates.count(TransactionCandidate::selected).toLong(),
                    importedCount = session.importedCount,
                    errorType = if (recoveredPreview) null else "PROCESS_INTERRUPTED",
                    safeErrorMessage = null,
                    updatedAt = now(),
                    completedAt = null,
                )
            }
    }

    private suspend fun cleanupExpiredDraftSessions() {
        val cutoff = now().toEpochMilliseconds() - TEMPORARY_IMAGE_TTL_MILLIS
        importRepository.list()
            .filter {
                it.completedAt == null &&
                    it.status != ImportSessionStatus.IMPORTING &&
                    it.updatedAt.toEpochMilliseconds() <= cutoff
            }.forEach { session ->
                val containsApprovedRows =
                    candidateRepository.byImportSession(session.id).any { it.status == CandidateStatus.APPROVED }
                if (!containsApprovedRows) {
                    val temporarySources = importRepository.sources(session.id).mapNotNull(TransactionImportSource::toTemporaryHandle)
                    importRepository.delete(session.id)
                    cleanupTemporary(temporarySources)
                }
            }
    }

    private suspend fun cleanupTemporary(images: Collection<TemporaryImageHandle>) {
        if (images.isEmpty()) return
        withContext(NonCancellable) {
            try {
                imageSourceService.cleanup(images)
            } catch (_: Exception) {
                // Platform TTL cleanup remains the fallback; ledger state must not be rolled back.
            }
        }
    }

    private suspend fun TransactionImportSession.markFailedOrCancelled(
        status: ImportSessionStatus,
        errorType: String,
        safeErrorMessage: String? = null,
    ) {
        runCatching {
            importRepository.updateStatus(
                id = id,
                status = status,
                selectedCount = selectedCount,
                importedCount = importedCount,
                errorType = errorType,
                safeErrorMessage = safeErrorMessage,
                updatedAt = now(),
                completedAt = null,
            )
        }
    }

    private fun showProviderFailure(failure: AiProviderException) {
        val (code, retryable) =
            when (failure.code) {
                AiProviderFailureCode.AUTHENTICATION -> ImageImportFailureCode.AUTHENTICATION to false
                AiProviderFailureCode.RATE_LIMITED -> ImageImportFailureCode.RATE_LIMITED to true
                AiProviderFailureCode.NETWORK -> ImageImportFailureCode.NETWORK to true
                AiProviderFailureCode.TIMEOUT -> ImageImportFailureCode.TIMEOUT to true
                AiProviderFailureCode.INVALID_RESPONSE -> ImageImportFailureCode.INVALID_STRUCTURED_RESPONSE to true
                AiProviderFailureCode.UNSUPPORTED_CAPABILITY -> {
                    ImageImportFailureCode.UNSUPPORTED_PROVIDER_CAPABILITY to false
                }
                AiProviderFailureCode.CANCELLED -> ImageImportFailureCode.CANCELLED to true
                AiProviderFailureCode.REQUEST_FAILED ->
                    if (failure.httpStatusCode in 500..599) {
                        ImageImportFailureCode.PROVIDER_UNAVAILABLE to true
                    } else {
                        ImageImportFailureCode.NETWORK to failure.isRetryableRequestFailure()
                    }
            }
        mutableState.update {
            it.copy(
                stage = if (handles.isEmpty()) ImageImportStage.IDLE else ImageImportStage.SOURCES_SELECTED,
                failure =
                    ImageImportFailure(
                        code = code,
                        retryable = retryable,
                        retryAfterSeconds = failure.retryAfterSeconds,
                        httpStatusCode = failure.httpStatusCode,
                        providerErrorCode = failure.providerErrorCode,
                        providerMessage = failure.safeProviderMessage(),
                    ),
            )
        }
    }

    private fun showImageSourceFailure(failure: ImageSourceFailure) {
        showFailure(
            code = ImageImportFailureCode.IMAGE_SOURCE,
            retryable = failure == ImageSourceFailure.IO_FAILURE,
        )
    }

    private fun showFailure(
        code: ImageImportFailureCode,
        retryable: Boolean,
    ) {
        mutableState.update {
            it.copy(
                stage = if (handles.isEmpty()) ImageImportStage.IDLE else ImageImportStage.SOURCES_SELECTED,
                failure = ImageImportFailure(code, retryable),
            )
        }
    }

    private fun TransactionCandidate.applyEdit(edit: TransactionCandidateEdit): TransactionCandidate =
        copy(
            description = edit.description?.trim()?.takeIf(String::isNotEmpty),
            amountCents = edit.amountCents?.let { kotlin.math.abs(it) },
            currency = edit.currency?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            occurredDate = edit.occurredDate,
            occurredTime = edit.occurredTime?.trim()?.takeIf(String::isNotEmpty),
            timeZoneId = edit.timeZoneId?.trim()?.takeIf(String::isNotEmpty),
            transactionType = edit.transactionType,
            suggestedCategoryId = edit.suggestedCategoryId,
            suggestedCategoryLabel = suggestedCategoryLabel.takeIf { edit.suggestedCategoryId == null },
            warnings = warnings.filterNot { it == WARNING_DEFAULT_CATEGORY && edit.suggestedCategoryId != null },
            accountOrPaymentMethod =
                edit.accountOrPaymentMethod?.let(::normalizePaymentMethod)
                    ?: edit.accountOrPaymentMethod?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            installmentIndex = edit.installmentIndex,
            installmentTotal = edit.installmentTotal,
            note = edit.note?.trim()?.takeIf(String::isNotEmpty),
            lowConfidenceFields =
                candidateLowConfidenceFields(
                    description = edit.description,
                    amountCents = edit.amountCents,
                    occurredDate = edit.occurredDate,
                    currency = edit.currency,
                    transactionType = edit.transactionType,
                    confidence = confidence,
                ),
            updatedAt = now(),
        )

    private fun List<TransactionCandidate>.toReviewable(): List<ReviewableTransactionCandidate> =
        sortedBy(TransactionCandidate::rowOrder).map {
            ReviewableTransactionCandidate(candidate = it, issues = it.reviewIssues())
        }

    private fun TransactionCandidate.reviewIssues(): List<CandidateValidationIssue> {
        val previewDraft =
            CandidateDraft(
                source = source,
                sourceReference = sourceReference,
                sourcePage = sourcePage,
                description = description,
                amountCents = amountCents,
                currency = currency,
                occurredDate = occurredDate,
                occurredTime = occurredTime,
                timeZoneId = timeZoneId,
                transactionType = transactionType,
                suggestedCategoryName = suggestedCategoryLabel,
                accountOrPaymentMethod = accountOrPaymentMethod,
                installmentIndex = installmentIndex,
                installmentTotal = installmentTotal,
                note = note,
                confidence = confidence,
                sourceImageHash = sourceImageHash,
                supportingText = supportingText,
                warnings = warnings,
                lowConfidenceFields = lowConfidenceFields,
            )
        return buildList {
            addAll(validateCandidatePreview(previewDraft))
            addAll(
                validateCandidateForExpenseApproval(this@reviewIssues).filterNot { issue ->
                    issue.code == CandidateIssueCode.MISSING_CATEGORY &&
                        suggestedCategoryLabel != null &&
                        WARNING_DEFAULT_CATEGORY in warnings
                },
            )
            if (duplicateCandidateId != null || duplicateExpenseId != null) {
                add(
                    CandidateValidationIssue(
                        CandidateIssueCode.POSSIBLE_DUPLICATE,
                        CandidateIssueSeverity.WARNING,
                        null,
                    ),
                )
            }
        }.distinctBy { Triple(it.code, it.severity, it.field) }
    }

    private data class AnalysisRequest(
        val providerProfileId: String,
        val localeTag: String,
        val defaultCurrency: String,
        val fallbackCategoryName: String,
    )
}

private class ImageImportOperationException(
    val code: ImageImportFailureCode,
    val retryable: Boolean = true,
) : Exception()

private fun AiProviderException.safeDiagnosticCode(): String =
    buildString {
        append(code.name)
        httpStatusCode?.let { append("_HTTP_$it") }
        providerErrorCode?.let { append("_$it") }
    }.take(MAX_SAFE_DIAGNOSTIC_CODE_LENGTH)

private fun AiProviderException.safeProviderMessage(): String? = message?.takeIf { httpStatusCode != null }

private fun AiProviderException.isRetryableRequestFailure(): Boolean =
    when (httpStatusCode) {
        null -> true
        408, 409, 425 -> true
        in 500..599 -> true
        else -> false
    }

private const val MAX_SAFE_DIAGNOSTIC_CODE_LENGTH = 160

private fun TemporaryImageHandle.toStateImage(): ImageImportImage =
    ImageImportImage(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        byteSize = byteSize,
        sha256 = sha256,
        isLocallyAvailable = true,
    )

private fun TransactionImportSource.toTemporaryHandle(): TemporaryImageHandle? {
    val availableByteSize = byteSize ?: return null
    val availableReference = temporaryReference ?: return null
    return TemporaryImageHandle(
        id = id,
        displayName = displayName.orEmpty(),
        mimeType = mediaType,
        byteSize = availableByteSize,
        sha256 = sourceHash,
        temporaryReference = availableReference,
    )
}

private fun TransactionImportSource.toStateImage(isLocallyAvailable: Boolean): ImageImportImage =
    ImageImportImage(
        id = id,
        displayName = displayName.orEmpty(),
        mimeType = mediaType,
        byteSize = byteSize ?: 0,
        sha256 = sourceHash,
        isLocallyAvailable = isLocallyAvailable,
    )

private fun normalizePaymentMethod(value: String?): String? {
    val normalized = normalizeTransactionText(value.orEmpty())
    return when {
        normalized == "pix" || normalized.contains("instant payment") -> PaymentMethod.PIX.name
        normalized.contains("debit") || normalized.contains("debito") -> PaymentMethod.DEBIT.name
        normalized.contains("cash") || normalized.contains("dinheiro") -> PaymentMethod.CASH.name
        normalized.contains("transfer") || normalized.contains("transferencia") -> PaymentMethod.TRANSFER.name
        normalized in PaymentMethod.entries.map { it.name.lowercase() } -> normalized.uppercase()
        else -> null
    }
}

private fun List<CandidateValidationIssue>.hasErrors(): Boolean {
    return any { it.severity == CandidateIssueSeverity.ERROR }
}

private fun stableImportSourceId(
    sessionId: String,
    sourceHash: String,
): String = "transaction-import-source-${sha256("$sessionId|$sourceHash".encodeToByteArray()).take(24)}"

private val REQUIRED_IMAGE_CAPABILITIES =
    ProviderCapabilities(
        textGeneration = true,
        visionInput = true,
        structuredOutput = true,
    )

private const val WARNING_AMBIGUOUS_SOURCE = "ambiguous-source-image"
private const val WARNING_REPEATED_SOURCE = "source-image-seen-before"
private const val WARNING_POSSIBLE_DUPLICATE = "possible-duplicate"
private const val WARNING_DEFAULT_CATEGORY = "category-defaulted"
private const val UNDEFINED_LOCALE_TAG = "und"
private const val UNDEFINED_CURRENCY = "XXX"
private const val LOG_TAG = "ImageImport"
private const val IMAGE_IMPORT_FALLBACK_CATEGORY_ID = "category-image-import-other"
private const val DEFAULT_FALLBACK_CATEGORY_NAME = "Other"
private const val MAX_FALLBACK_CATEGORY_NAME_LENGTH = 40
private val GENERIC_CATEGORY_NAMES = setOf("other", "others", "outro", "outros", "uncategorized", "sem categoria")
private val NON_EXPENSE_TYPES =
    setOf(
        CandidateTransactionType.INCOME,
        CandidateTransactionType.TRANSFER,
        CandidateTransactionType.REFUND,
    )
