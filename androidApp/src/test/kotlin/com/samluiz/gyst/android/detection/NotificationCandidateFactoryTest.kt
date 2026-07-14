package com.samluiz.gyst.android.detection

import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.RawTransactionExtraction
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationCandidateFactoryTest {
    private val factory = NotificationCandidateFactory(TimeZone.UTC)
    private val postedAt = Instant.parse("2026-07-14T15:30:00Z")
    private val categories =
        listOf(
            Category(
                id = "category-food",
                name = "Alimentação",
                type = CategoryType.ESSENTIAL,
            ),
        )

    @Test
    fun `rule extraction uses shared normalization and known source metadata`() {
        val candidate = createCandidate(postedAt)

        assertEquals(4_290L, candidate.amountCents)
        assertEquals("BRL", candidate.currency)
        assertEquals("2026-07-14", candidate.occurredDate.toString())
        assertEquals(CandidateTransactionType.EXPENSE, candidate.transactionType)
        assertEquals("PIX", candidate.accountOrPaymentMethod)
        assertEquals("category-food", candidate.suggestedCategoryId)
        assertNull(candidate.supportingText)
    }

    @Test
    fun `same normalized transaction in one window has stable idempotency key`() {
        val first = createCandidate(postedAt)
        val duplicateCallback = createCandidate(postedAt + 10.minutes)
        val laterPurchase = createCandidate(postedAt + 16.minutes)

        assertEquals(first.idempotencyKey, duplicateCallback.idempotencyKey)
        assertEquals(first.id, duplicateCallback.id)
        assertNotEquals(first.idempotencyKey, laterPurchase.idempotencyKey)
    }

    private fun createCandidate(posted: Instant) =
        factory.create(
            input =
                NotificationCandidateInput(
                    sourcePackage = "com.example.bank",
                    notificationFingerprint = "fingerprint-${posted.epochSeconds}",
                    postedAt = posted,
                    extraction =
                        RawTransactionExtraction(
                            description = "Padaria Exemplo",
                            amount = "R$ 42,90",
                            transactionType = "expense",
                            suggestedCategory = "alimentacao",
                            supportingText = "Compra aprovada no PIX",
                            confidence = 0.92,
                        ),
                ),
            categories = categories,
            now = Instant.parse("2026-07-14T16:00:00Z"),
        )
}
