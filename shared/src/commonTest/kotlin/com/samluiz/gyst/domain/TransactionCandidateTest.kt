package com.samluiz.gyst.domain

import com.samluiz.gyst.domain.model.CandidateNormalizationContext
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.RawTransactionExtraction
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
    fun stableHashMatchesSha256ReferenceVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()),
        )
    }
}
