package com.samluiz.gyst.domain

import com.samluiz.gyst.domain.model.CandidateNormalizationContext
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.RawTransactionExtraction
import com.samluiz.gyst.domain.model.inferExpenseCategory
import com.samluiz.gyst.domain.model.normalizeExtraction
import com.samluiz.gyst.domain.model.sha256
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionCandidateTest {
    @Test
    fun normalizesBrazilianAmountAndDateWithoutFloatingPoint() {
        val result =
            normalizeExtraction(
                raw =
                    RawTransactionExtraction(
                        description = "Mercado Central",
                        amount = "R$ 1.234,56",
                        transactionDate = "14/07/2026",
                        transactionType = "compra no débito",
                        confidence = 0.92,
                    ),
                context =
                    CandidateNormalizationContext(
                        source = CandidateSource.IMAGE,
                        defaultCurrency = "BRL",
                        localeTag = "pt-BR",
                    ),
            )

        assertEquals(123_456L, result.candidate.amountCents)
        assertEquals("BRL", result.candidate.currency)
        assertEquals(LocalDate(2026, 7, 14), result.candidate.occurredDate)
        assertEquals(CandidateTransactionType.EXPENSE, result.candidate.transactionType)
    }

    @Test
    fun dateWithoutYearRemainsUnresolvedForReview() {
        val result =
            normalizeExtraction(
                raw = RawTransactionExtraction(description = "Padaria", amount = "12,50", transactionDate = "14/07"),
                context = CandidateNormalizationContext(CandidateSource.IMAGE, localeTag = "pt-BR"),
            )

        assertNull(result.candidate.occurredDate)
    }

    @Test
    fun importDefaultsMissingDateAndDescriptionFromSafeLocalContext() {
        val result =
            normalizeExtraction(
                raw =
                    RawTransactionExtraction(
                        amount = "42,90",
                        transactionType = "expense",
                        suggestedCategory = "Mercado",
                    ),
                context =
                    CandidateNormalizationContext(
                        CandidateSource.IMAGE,
                        localeTag = "pt-BR",
                        defaultDate = LocalDate(2026, 7, 14),
                    ),
            )

        assertEquals("Mercado", result.candidate.description)
        assertEquals(LocalDate(2026, 7, 14), result.candidate.occurredDate)
    }

    @Test
    fun categoryInferenceUsesMerchantEvidenceBeforeSafeFallback() {
        val categories =
            listOf(
                Category("home", "Moradia", CategoryType.ESSENTIAL),
                Category("food", "Mercado", CategoryType.ESSENTIAL),
            )

        assertEquals(
            "food",
            inferExpenseCategory(categories, suggestedName = null, description = "Supermercado Central", supportingText = null)?.id,
        )
    }

    @Test
    fun categoryInferenceDoesNotGuessWithoutEvidence() {
        val categories =
            listOf(
                Category("home", "Moradia", CategoryType.ESSENTIAL),
                Category("food", "Mercado", CategoryType.ESSENTIAL),
            )

        assertNull(
            inferExpenseCategory(categories, suggestedName = null, description = "Loja Exemplo", supportingText = null),
        )
    }

    @Test
    fun malformedAmountIsNotSilentlyReinterpreted() {
        val result =
            normalizeExtraction(
                raw =
                    RawTransactionExtraction(
                        description = "Padaria",
                        amount = "12,3456",
                        transactionDate = "2026-07-14",
                    ),
                context = CandidateNormalizationContext(CandidateSource.IMAGE, localeTag = "pt-BR"),
            )

        assertNull(result.candidate.amountCents)
    }

    @Test
    fun negativeAmountWithoutDebitOrRefundEvidenceRemainsUnknownForReview() {
        val result =
            normalizeExtraction(
                raw =
                    RawTransactionExtraction(
                        description = "Adjustment",
                        amount = "-R$ 42,90",
                        transactionDate = "2026-07-14",
                    ),
                context = CandidateNormalizationContext(CandidateSource.IMAGE, localeTag = "pt-BR"),
            )

        assertEquals(4_290L, result.candidate.amountCents)
        assertEquals(CandidateTransactionType.UNKNOWN, result.candidate.transactionType)
    }

    @Test
    fun accountCreditsAreIncomeButCreditCardPurchasesAreExpenses() {
        fun normalizedType(
            type: String?,
            description: String,
        ): CandidateTransactionType =
            normalizeExtraction(
                raw =
                    RawTransactionExtraction(
                        description = description,
                        amount = "42,90",
                        transactionDate = "2026-07-14",
                        transactionType = type,
                    ),
                context = CandidateNormalizationContext(CandidateSource.IMAGE, localeTag = "pt-BR"),
            ).candidate.transactionType

        assertEquals(CandidateTransactionType.INCOME, normalizedType("credit", "Account credit"))
        assertEquals(CandidateTransactionType.INCOME, normalizedType("crédito", "Crédito em conta"))
        assertEquals(CandidateTransactionType.INCOME, normalizedType(null, "Depósito recebido"))
        assertEquals(CandidateTransactionType.EXPENSE, normalizedType("credit", "Purchase approved at Amazon"))
        assertEquals(CandidateTransactionType.EXPENSE, normalizedType("crédito", "Pagamento aprovado"))
        assertEquals(CandidateTransactionType.EXPENSE, normalizedType("credit card purchase", "Compra no cartão de crédito"))
    }

    @Test
    fun stableHashMatchesSha256ReferenceVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()),
        )
    }
}
