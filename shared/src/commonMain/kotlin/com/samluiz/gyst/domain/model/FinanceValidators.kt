package com.samluiz.gyst.domain.model

fun requireNonNegative(value: Long) {
    if (value < 0) throw FinanceError.NegativeAmount()
}
