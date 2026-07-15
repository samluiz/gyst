package com.samluiz.gyst.domain

import com.samluiz.gyst.domain.model.Expense
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.displayDescription
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ExpenseDescriptionTest {
    @Test
    fun importedMerchantIsUsedWhenNoUserDescriptionExists() {
        assertEquals("Mercado Central", expense(note = null, merchant = " Mercado Central ").displayDescription())
    }

    @Test
    fun userDescriptionTakesPriorityOverImportedMerchant() {
        assertEquals("Compra semanal", expense(note = "Compra semanal", merchant = "Mercado Central").displayDescription())
    }

    @Test
    fun blankValuesDoNotBecomeVisibleDescriptions() {
        assertNull(expense(note = " ", merchant = "").displayDescription())
    }

    private fun expense(
        note: String?,
        merchant: String?,
    ) = Expense(
        id = "expense",
        occurredAt = LocalDate(2026, 7, 14),
        amountCents = 1_000,
        categoryId = "category",
        note = note,
        merchant = merchant,
        paymentMethod = PaymentMethod.DEBIT,
        createdAt = Instant.parse("2026-07-14T12:00:00Z"),
    )
}
