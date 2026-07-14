package com.samluiz.gyst.app

import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.service.ImageImportStage
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ImageImportUiTest {
    @Test
    fun dateEditorAcceptsOnlyUnambiguousIsoDates() {
        assertEquals(LocalDate(2026, 7, 14), parseImportDate(" 2026-07-14 "))
        assertNull(parseImportDate("14/07/2026"))
        assertNull(parseImportDate("2026-02-30"))
    }

    @Test
    fun confirmationRequiresSelectedValidRowsInPreview() {
        assertTrue(canConfirmImageImport(2, selectedHasErrors = false, ImageImportStage.PREVIEW))
        assertFalse(canConfirmImageImport(0, selectedHasErrors = false, ImageImportStage.PREVIEW))
        assertFalse(canConfirmImageImport(2, selectedHasErrors = true, ImageImportStage.PREVIEW))
        assertFalse(canConfirmImageImport(2, selectedHasErrors = false, ImageImportStage.IMPORTING))
    }

    @Test
    fun emptyExtractionGuidanceIsLocalized() {
        val pt = appStringsForLanguage("pt")
        val en = appStringsForLanguage("en")

        assertEquals("Nenhuma transação encontrada", pt.imageImportEmptyPreviewTitle)
        assertEquals("No transactions found", en.imageImportEmptyPreviewTitle)
        assertTrue(pt.imageImportEmptyPreviewBody.contains("linha manualmente"))
        assertTrue(en.imageImportEmptyPreviewBody.contains("row manually"))
    }

    @Test
    fun internalWarningCodesAreLocalizedInsteadOfRenderedRaw() {
        val pt = appStringsForLanguage("pt")
        val en = appStringsForLanguage("en")

        assertEquals(pt.imageImportWarningRepeatedSource, imageImportWarningLabel("source-image-seen-before", pt))
        assertEquals(en.imageImportWarningAmbiguousSource, imageImportWarningLabel("ambiguous-source-image", en))
        assertEquals(pt.imageImportDuplicateWarning, imageImportWarningLabel("possible-duplicate", pt))
        assertEquals(en.imageImportWarningExtractedValue, imageImportWarningLabel("provider supplied detail", en))
    }

    @Test
    fun lowConfidenceFieldsExposeLocalizedFieldNames() {
        val strings = appStringsForLanguage("en")

        assertEquals(
            listOf(strings.imageImportFieldDescription, strings.imageImportFieldAmount, strings.imageImportFieldDate),
            imageImportLowConfidenceFieldLabels(linkedSetOf("description", "amount", "date"), strings),
        )
        assertEquals(
            listOf(strings.imageImportNeedsReview),
            imageImportLowConfidenceFieldLabels(setOf("future-field"), strings),
        )
    }

    @Test
    fun paymentLabelPreservesDetectedAccountAndExplainsMissingValue() {
        val strings = appStringsForLanguage("en")

        assertEquals(strings.imageImportPaymentPix, paymentMethodLabel("PIX", strings))
        assertEquals("Visa ending 4242", paymentMethodLabel("Visa ending 4242", strings))
        assertEquals(strings.imageImportPaymentUnknown, paymentMethodLabel(null, strings))
    }

    @Test
    fun rowSelectionDescriptionIdentifiesTheExactCandidate() {
        val strings = appStringsForLanguage("en")
        val description = candidateSelectionDescription(candidate(), strings)

        assertTrue(description.contains("Corner Market"))
        assertTrue(description.contains("42"))
        assertTrue(description.contains("2026-07-14"))
    }

    private fun candidate(): TransactionCandidate =
        TransactionCandidate(
            id = "candidate-1",
            importSessionId = "session-1",
            source = CandidateSource.IMAGE,
            sourceReference = "image-1",
            sourcePage = "1",
            rowOrder = 0,
            idempotencyKey = "image:session-1:0",
            fingerprint = null,
            description = "Corner Market",
            amountCents = 4_290,
            currency = "BRL",
            occurredDate = LocalDate(2026, 7, 14),
            occurredTime = "12:30",
            timeZoneId = null,
            transactionType = CandidateTransactionType.EXPENSE,
            suggestedCategoryId = null,
            accountOrPaymentMethod = "PIX",
            installmentIndex = null,
            installmentTotal = null,
            note = null,
            confidence = 0.91,
            sourceImageHash = "hash",
            supportingText = null,
            warnings = emptyList(),
            lowConfidenceFields = emptySet(),
            selected = true,
            status = CandidateStatus.NEEDS_REVIEW,
            duplicateCandidateId = null,
            duplicateExpenseId = null,
            linkedExpenseId = null,
            providerId = "provider",
            modelId = "model",
            retryCount = 0,
            errorType = null,
            errorMessage = null,
            createdAt = Instant.parse("2026-07-14T12:30:00Z"),
            updatedAt = Instant.parse("2026-07-14T12:30:00Z"),
        )
}
