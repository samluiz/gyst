package com.samluiz.gyst.presentation

data class ExpenseDraft(
    val amountCents: Long,
    val categoryId: String,
    val note: String?,
    val recurringMonthly: Boolean,
)

data class SubscriptionDraft(
    val name: String,
    val amountCents: Long,
    val billingDay: Int,
    val categoryId: String,
)

data class InstallmentDraft(
    val name: String,
    val amountCents: Long,
    val totalInstallments: Int,
    val categoryId: String,
)
