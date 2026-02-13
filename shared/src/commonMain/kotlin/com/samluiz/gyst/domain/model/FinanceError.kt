package com.samluiz.gyst.domain.model

sealed class FinanceError(message: String) : IllegalStateException(message) {
    class NegativeAmount : FinanceError("Amounts cannot be negative")
    class GuardBlockedInstallments : FinanceError("No new installments mode is enabled")
}
