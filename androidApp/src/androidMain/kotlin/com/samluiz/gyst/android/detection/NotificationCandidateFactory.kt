package com.samluiz.gyst.android.detection

import com.samluiz.gyst.domain.model.CandidateNormalizationContext
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.RawTransactionExtraction
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.normalizeExtraction
import com.samluiz.gyst.domain.model.normalizeTransactionText
import com.samluiz.gyst.domain.model.sha256
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class NotificationCandidateInput(
    val sourcePackage: String,
    val notificationFingerprint: String,
    val postedAt: Instant,
    val extraction: RawTransactionExtraction,
    val providerId: String? = null,
    val modelId: String? = null,
)

/** Maps both rule and AI extraction through the shared candidate normalization pipeline. */
class NotificationCandidateFactory(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    fun create(
        input: NotificationCandidateInput,
        categories: List<Category>,
        now: Instant,
    ): TransactionCandidate {
        val extraction =
            input.extraction.copy(
                transactionDate =
                    input.extraction.transactionDate
                        ?: input.postedAt.toLocalDateTime(timeZone).date.toString(),
                accountOrPaymentMethod =
                    inferPaymentMethod(
                        input.extraction.accountOrPaymentMethod,
                        input.extraction.supportingText,
                    ),
            )
        val result =
            normalizeExtraction(
                extraction,
                CandidateNormalizationContext(
                    source = CandidateSource.ANDROID_NOTIFICATION,
                    sourceReference = input.notificationFingerprint,
                    localeTag = if (extraction.amount.orEmpty().contains("R$")) "pt-BR" else "en-US",
                    timeZoneId = timeZone.id,
                ),
            )
        val draft = result.candidate
        val fingerprintWindow = input.postedAt.epochSeconds / DEDUPLICATION_WINDOW_SECONDS
        val idempotencyMaterial =
            draft.fingerprint
                ?.let { "$it|$fingerprintWindow" }
                ?: input.notificationFingerprint
        val idempotencyKey = "android-notification:$idempotencyMaterial"
        val candidateId = "notification-${sha256(idempotencyKey.encodeToByteArray()).take(ID_HASH_LENGTH)}"
        val categoryId =
            draft.suggestedCategoryName?.let { suggested ->
                categories.firstOrNull { category ->
                    normalizeTransactionText(category.name) == normalizeTransactionText(suggested)
                }?.id
            }
        val issueWarnings = result.issues.map { issue -> issue.code.name.lowercase() }
        return TransactionCandidate(
            id = candidateId,
            importSessionId = null,
            source = CandidateSource.ANDROID_NOTIFICATION,
            sourceReference = input.notificationFingerprint,
            sourcePage = input.sourcePackage,
            rowOrder = input.postedAt.toEpochMilliseconds(),
            idempotencyKey = idempotencyKey,
            fingerprint = draft.fingerprint,
            description = draft.description,
            amountCents = draft.amountCents,
            currency = draft.currency,
            occurredDate = draft.occurredDate,
            occurredTime = draft.occurredTime,
            timeZoneId = draft.timeZoneId,
            transactionType = draft.transactionType,
            suggestedCategoryId = categoryId,
            suggestedCategoryLabel = draft.suggestedCategoryName.takeIf { categoryId == null },
            accountOrPaymentMethod = draft.accountOrPaymentMethod,
            installmentIndex = draft.installmentIndex,
            installmentTotal = draft.installmentTotal,
            note = draft.note,
            confidence = draft.confidence,
            sourceImageHash = null,
            // The source notification has served its purpose; do not retain its body in the suggestion.
            supportingText = null,
            warnings = (draft.warnings + issueWarnings).distinct(),
            lowConfidenceFields = draft.lowConfidenceFields,
            selected = true,
            status = CandidateStatus.NEEDS_REVIEW,
            duplicateCandidateId = null,
            duplicateExpenseId = null,
            linkedExpenseId = null,
            providerId = input.providerId,
            modelId = input.modelId,
            retryCount = 0,
            errorType = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun inferPaymentMethod(
        declared: String?,
        supportingText: String?,
    ): String? {
        val combined = listOfNotNull(declared, supportingText).joinToString(" ").lowercase()
        return when {
            Regex("\\bpix\\b").containsMatchIn(combined) -> PaymentMethod.PIX.name
            Regex("(?:d[eé]bito|debit)").containsMatchIn(combined) -> PaymentMethod.DEBIT.name
            Regex("(?:dinheiro|cash)").containsMatchIn(combined) -> PaymentMethod.CASH.name
            Regex("(?:transfer[eê]ncia|transfer)").containsMatchIn(combined) -> PaymentMethod.TRANSFER.name
            declared?.uppercase() in PaymentMethod.entries.map { it.name } -> declared?.uppercase()
            else -> null
        }
    }

    private companion object {
        const val DEDUPLICATION_WINDOW_SECONDS = 15 * 60L
        const val ID_HASH_LENGTH = 32
    }
}
