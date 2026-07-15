package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.CandidateValidationIssue
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.TransactionCandidate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

enum class ImageImportStage {
    IDLE,
    SOURCES_SELECTED,
    ANALYZING,
    PREVIEW,
    IMPORTING,
    COMPLETED,
    CANCELLED,
}

enum class ImageImportFailureCode {
    NO_IMAGES,
    IMAGE_SOURCE,
    IMAGE_SOURCE_READ_FAILURE,
    IMAGE_SOURCE_TOO_LARGE,
    IMAGE_SOURCE_UNSUPPORTED_FORMAT,
    IMAGE_SOURCE_PERMISSION_DENIED,
    PROVIDER_NOT_FOUND,
    PROVIDER_NOT_CONFIGURED,
    UNSUPPORTED_PROVIDER_CAPABILITY,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    INVALID_STRUCTURED_RESPONSE,
    INTERRUPTED,
    VALIDATION,
    DATABASE,
    DUPLICATE_OPERATION,
    CANCELLED,
}

data class ImageImportFailure(
    val code: ImageImportFailureCode,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
    val httpStatusCode: Int? = null,
    val providerErrorCode: String? = null,
    val providerMessage: String? = null,
)

data class ImageImportImage(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val isLocallyAvailable: Boolean,
)

data class ReviewableTransactionCandidate(
    val candidate: TransactionCandidate,
    val issues: List<CandidateValidationIssue>,
)

data class ImageImportSummary(
    val selectedCount: Int,
    val importedCount: Int,
    val alreadyImportedCount: Int,
)

data class ImageImportState(
    val stage: ImageImportStage = ImageImportStage.IDLE,
    val canSelectImages: Boolean = false,
    val canCaptureImage: Boolean = false,
    val maximumSelection: Int = 0,
    val compatibleProfiles: List<ProviderProfile> = emptyList(),
    val images: List<ImageImportImage> = emptyList(),
    val sessionId: String? = null,
    val selectedProviderProfileId: String? = null,
    val progress: Float = 0f,
    val candidates: List<ReviewableTransactionCandidate> = emptyList(),
    val summary: ImageImportSummary? = null,
    val failure: ImageImportFailure? = null,
) {
    val canAnalyze: Boolean
        get() =
            images.isNotEmpty() &&
                images.all(ImageImportImage::isLocallyAvailable) &&
                stage != ImageImportStage.ANALYZING

    val selectedCandidates: List<ReviewableTransactionCandidate>
        get() = candidates.filter { it.candidate.selected }
}

/**
 * Complete editable candidate values. Callers start from the persisted candidate and change only
 * fields explicitly edited by the user; immutable provenance is retained by the implementation.
 */
data class TransactionCandidateEdit(
    val description: String?,
    val amountCents: Long?,
    val currency: String?,
    val occurredDate: LocalDate?,
    val occurredTime: String?,
    val timeZoneId: String?,
    val transactionType: CandidateTransactionType,
    val suggestedCategoryId: String?,
    val accountOrPaymentMethod: String?,
    val installmentIndex: Int?,
    val installmentTotal: Int?,
    val note: String?,
)

fun TransactionCandidate.toEdit(): TransactionCandidateEdit =
    TransactionCandidateEdit(
        description = description,
        amountCents = amountCents,
        currency = currency,
        occurredDate = occurredDate,
        occurredTime = occurredTime,
        timeZoneId = timeZoneId,
        transactionType = transactionType,
        suggestedCategoryId = suggestedCategoryId,
        accountOrPaymentMethod = accountOrPaymentMethod,
        installmentIndex = installmentIndex,
        installmentTotal = installmentTotal,
        note = note,
    )

interface ImageImportService {
    val state: StateFlow<ImageImportState>

    suspend fun initialize()

    /** Consumes platform image results delivered after Activity/process recreation. */
    suspend fun recoverPendingSources()

    suspend fun selectImages()

    suspend fun captureImage()

    suspend fun removeImage(imageId: String)

    /** The only operation that may transmit locally selected images to a configured provider. */
    suspend fun analyze(
        providerProfileId: String,
        localeTag: String,
        defaultCurrency: String,
        fallbackCategoryName: String = "Other",
    )

    suspend fun retryAnalysis()

    suspend fun cancelAnalysis()

    suspend fun updateCandidate(
        candidateId: String,
        edit: TransactionCandidateEdit,
    )

    suspend fun setCandidateSelected(
        candidateId: String,
        selected: Boolean,
    )

    suspend fun setAllCandidatesSelected(selected: Boolean)

    suspend fun addCandidate(edit: TransactionCandidateEdit)

    suspend fun deleteCandidate(candidateId: String)

    suspend fun applyCategory(
        candidateIds: Set<String>,
        categoryId: String,
    )

    suspend fun applyPaymentMethod(
        candidateIds: Set<String>,
        paymentMethod: String,
    )

    suspend fun confirmImport()

    suspend fun cancelImport()

    /** Quiesces provider and file work before the database file is atomically replaced. */
    suspend fun suspendForDatabaseReplacement()

    suspend fun clear()
}
