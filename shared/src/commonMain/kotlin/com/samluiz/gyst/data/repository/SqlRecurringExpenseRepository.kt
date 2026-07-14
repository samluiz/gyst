package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.RecurringExpenseSeries
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.repository.RecurringExpenseRepository

class SqlRecurringExpenseRepository(private val holder: DatabaseHolder) : RecurringExpenseRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(series: RecurringExpenseSeries) {
        q.upsertRecurringExpenseSeries(
            start_year_month = series.startYearMonth.toString(),
            end_year_month = series.endYearMonth?.toString(),
            day_of_month = series.dayOfMonth.toLong(),
            amount_cents = series.amountCents,
            category_id = series.categoryId,
            note = series.note,
            merchant = series.merchant,
            payment_method = series.paymentMethod.name,
            active = if (series.active) 1 else 0,
            id = series.id,
            id_ = series.id,
            start_year_month_ = series.startYearMonth.toString(),
            end_year_month_ = series.endYearMonth?.toString(),
            day_of_month_ = series.dayOfMonth.toLong(),
            amount_cents_ = series.amountCents,
            category_id_ = series.categoryId,
            note_ = series.note,
            merchant_ = series.merchant,
            payment_method_ = series.paymentMethod.name,
            active_ = if (series.active) 1 else 0,
        )
    }

    override suspend fun findById(id: String): RecurringExpenseSeries? =
        q.selectRecurringExpenseSeriesById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun activeForMonth(yearMonth: YearMonth): List<RecurringExpenseSeries> =
        q.selectActiveRecurringExpenseSeriesForMonth(yearMonth.toString()).executeAsList().map { it.toDomain() }

    override suspend fun endBefore(
        id: String,
        lastActiveMonth: YearMonth,
    ) {
        q.endRecurringExpenseSeries(lastActiveMonth.toString(), id)
    }
}

private fun com.samluiz.gyst.db.Recurring_expense_series.toDomain() =
    RecurringExpenseSeries(
        id = id,
        startYearMonth = YearMonth.parse(start_year_month),
        endYearMonth = end_year_month?.let(YearMonth::parse),
        dayOfMonth = day_of_month.toInt(),
        amountCents = amount_cents,
        categoryId = category_id,
        note = note,
        merchant = merchant,
        paymentMethod = PaymentMethod.valueOf(payment_method),
        active = active == 1L,
    )
