package com.samluiz.gyst.app

import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.TransactionCandidate
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class AutomaticTransactionDetectionUiTest {
    @Test
    fun confidenceBandHasStableReviewThresholds() {
        assertEquals(DetectionConfidenceBand.LOW, detectionConfidenceBand(null))
        assertEquals(DetectionConfidenceBand.LOW, detectionConfidenceBand(0.59))
        assertEquals(DetectionConfidenceBand.MEDIUM, detectionConfidenceBand(0.60))
        assertEquals(DetectionConfidenceBand.MEDIUM, detectionConfidenceBand(0.79))
        assertEquals(DetectionConfidenceBand.HIGH, detectionConfidenceBand(0.80))
    }

    @Test
    fun editedReviewPreservesDurableSourceIdentity() {
        val original = candidate()
        val updatedAt = Instant.parse("2026-07-14T13:00:00Z")

        val edited =
            reviewedDetectionCandidate(
                candidate = original,
                description = "  Padaria Central  ",
                amountDigits = "4290",
                currency = "brl",
                date = "2026-07-13",
                time = "08:45",
                type = CandidateTransactionType.EXPENSE,
                categoryId = "category-food",
                paymentMethod = "DEBIT",
                note = "  café da manhã ",
                updatedAt = updatedAt,
            )

        assertEquals(original.id, edited.id)
        assertEquals(original.idempotencyKey, edited.idempotencyKey)
        assertEquals(original.sourceReference, edited.sourceReference)
        assertEquals("Padaria Central", edited.description)
        assertEquals(4_290L, edited.amountCents)
        assertEquals("BRL", edited.currency)
        assertEquals(LocalDate(2026, 7, 13), edited.occurredDate)
        assertEquals("category-food", edited.suggestedCategoryId)
        assertEquals("DEBIT", edited.accountOrPaymentMethod)
        assertEquals("café da manhã", edited.note)
        assertEquals(updatedAt, edited.updatedAt)
    }

    @Test
    fun invalidReviewInputRemainsInvalidInsteadOfBeingInvented() {
        val edited =
            reviewedDetectionCandidate(
                candidate = candidate(),
                description = "   ",
                amountDigits = "not-a-number",
                currency = "   ",
                date = "14/07/26",
                time = "",
                type = CandidateTransactionType.UNKNOWN,
                categoryId = null,
                paymentMethod = null,
                note = "",
                updatedAt = Instant.parse("2026-07-14T14:00:00Z"),
            )

        assertNull(edited.description)
        assertNull(edited.amountCents)
        assertNull(edited.currency)
        assertNull(edited.occurredDate)
        assertNull(edited.occurredTime)
        assertNull(edited.note)
    }

    private fun candidate(): TransactionCandidate {
        val createdAt = Instant.parse("2026-07-14T12:00:00Z")
        return TransactionCandidate(
            id = "candidate-1",
            importSessionId = null,
            source = CandidateSource.ANDROID_NOTIFICATION,
            sourceReference = "com.example.bank",
            sourcePage = null,
            rowOrder = 1,
            idempotencyKey = "notification:event-1",
            fingerprint = "fingerprint-1",
            description = "Example Store",
            amountCents = 1_000,
            currency = "BRL",
            occurredDate = LocalDate(2026, 7, 14),
            occurredTime = "12:00",
            timeZoneId = "America/Sao_Paulo",
            transactionType = CandidateTransactionType.EXPENSE,
            suggestedCategoryId = "category-other",
            accountOrPaymentMethod = "PIX",
            installmentIndex = null,
            installmentTotal = null,
            note = null,
            confidence = 0.72,
            sourceImageHash = null,
            supportingText = null,
            warnings = emptyList(),
            lowConfidenceFields = emptySet(),
            selected = true,
            status = CandidateStatus.NEEDS_REVIEW,
            duplicateCandidateId = null,
            duplicateExpenseId = null,
            linkedExpenseId = null,
            providerId = null,
            modelId = null,
            retryCount = 0,
            errorType = null,
            errorMessage = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
