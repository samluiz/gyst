package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.monthBounds
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

class SqlExpenseRepository(private val holder: DatabaseHolder) : ExpenseRepository {
    override suspend fun upsert(expense: Expense) {
        holder.withDatabase { db ->
            db.financeQueries.upsertExpense(
                occurred_at = expense.occurredAt.toString(),
                amount_cents = expense.amountCents,
                category_id = expense.categoryId,
                note = expense.note,
                merchant = expense.merchant,
                payment_method = expense.paymentMethod.name,
                recurrence_type = expense.recurrenceType.name,
                created_at = expense.createdAt.toString(),
                schedule_item_id = expense.scheduleItemId,
                recurrence_series_id = expense.recurrenceSeriesId,
                id = expense.id,
                id_ = expense.id,
                occurred_at_ = expense.occurredAt.toString(),
                amount_cents_ = expense.amountCents,
                category_id_ = expense.categoryId,
                note_ = expense.note,
                merchant_ = expense.merchant,
                payment_method_ = expense.paymentMethod.name,
                recurrence_type_ = expense.recurrenceType.name,
                created_at_ = expense.createdAt.toString(),
                schedule_item_id_ = expense.scheduleItemId,
                recurrence_series_id_ = expense.recurrenceSeriesId,
            )
        }
    }

    override suspend fun delete(id: String) {
        holder.withDatabase { db ->
            db.financeQueries.deleteExpense(id)
        }
    }

    override suspend fun getById(id: String): Expense? =
        holder.withDatabase { db ->
            db.financeQueries.selectExpenseById(id).executeAsOneOrNull()?.let {
                Expense(
                    id = it.id,
                    occurredAt = LocalDate.parse(it.occurred_at),
                    amountCents = it.amount_cents,
                    categoryId = it.category_id,
                    note = it.note,
                    merchant = it.merchant,
                    paymentMethod = PaymentMethod.valueOf(it.payment_method),
                    recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                    createdAt = Instant.parse(it.created_at),
                    scheduleItemId = it.schedule_item_id,
                    recurrenceSeriesId = it.recurrence_series_id,
                )
            }
        }

    override suspend fun byMonth(yearMonth: YearMonth): List<Expense> =
        holder.withDatabase { db ->
            val (from, to) = monthBounds(yearMonth)
            db.financeQueries.selectExpensesByMonth(fromDate = from.toString(), toDate = to.toString()).executeAsList()
                .map {
                    Expense(
                        id = it.id,
                        occurredAt = LocalDate.parse(it.occurred_at),
                        amountCents = it.amount_cents,
                        categoryId = it.category_id,
                        note = it.note,
                        merchant = it.merchant,
                        paymentMethod = PaymentMethod.valueOf(it.payment_method),
                        recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                        createdAt = Instant.parse(it.created_at),
                        scheduleItemId = it.schedule_item_id,
                        recurrenceSeriesId = it.recurrence_series_id,
                    )
                }
        }

    override suspend fun byMonthPaged(
        yearMonth: YearMonth,
        limit: Long,
        offset: Long,
    ): List<Expense> =
        holder.withDatabase { db ->
            val (from, to) = monthBounds(yearMonth)
            db.financeQueries.selectExpensesByMonthPaged(
                fromDate = from.toString(),
                toDate = to.toString(),
                limit = limit,
                offset = offset,
            ).executeAsList().map {
                Expense(
                    id = it.id,
                    occurredAt = LocalDate.parse(it.occurred_at),
                    amountCents = it.amount_cents,
                    categoryId = it.category_id,
                    note = it.note,
                    merchant = it.merchant,
                    paymentMethod = PaymentMethod.valueOf(it.payment_method),
                    recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                    createdAt = Instant.parse(it.created_at),
                    scheduleItemId = it.schedule_item_id,
                    recurrenceSeriesId = it.recurrence_series_id,
                )
            }
        }

    override suspend fun search(
        yearMonth: YearMonth,
        categoryId: String?,
        query: String?,
    ): List<Expense> =
        holder.withDatabase { db ->
            val (from, to) = monthBounds(yearMonth)
            db.financeQueries.searchExpenses(
                fromDate = from.toString(),
                toDate = to.toString(),
                categoryId = categoryId,
                query = query,
            ).executeAsList().map {
                Expense(
                    id = it.id,
                    occurredAt = LocalDate.parse(it.occurred_at),
                    amountCents = it.amount_cents,
                    categoryId = it.category_id,
                    note = it.note,
                    merchant = it.merchant,
                    paymentMethod = PaymentMethod.valueOf(it.payment_method),
                    recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                    createdAt = Instant.parse(it.created_at),
                    scheduleItemId = it.schedule_item_id,
                    recurrenceSeriesId = it.recurrence_series_id,
                )
            }
        }

    override suspend fun deleteFutureRecurringBySeries(
        fromDateExclusive: LocalDate,
        seriesId: String,
    ) {
        holder.withDatabase { db ->
            db.financeQueries.deleteFutureRecurringBySeries(
                fromDate = fromDateExclusive.toString(),
                seriesId = seriesId,
            )
        }
    }

    override suspend fun deleteRecurringFromOccurrence(
        expenseId: String,
        occurrenceDate: LocalDate,
        seriesId: String,
        lastActiveMonth: YearMonth,
    ) {
        holder.withDatabase { db ->
            db.financeQueries.deleteRecurringFromOccurrence(
                seriesId = seriesId,
                expenseId = expenseId,
                occurrenceDate = occurrenceDate.toString(),
                lastActiveMonth = lastActiveMonth.toString(),
            )
        }
    }

    override suspend fun updateFutureRecurringBySeries(
        fromDateExclusive: LocalDate,
        seriesId: String,
        newTemplate: Expense,
    ) {
        holder.withDatabase { db ->
            db.financeQueries.updateFutureRecurringBySeries(
                fromDate = fromDateExclusive.toString(),
                seriesId = seriesId,
                newCategoryId = newTemplate.categoryId,
                newAmountCents = newTemplate.amountCents,
                newPaymentMethod = newTemplate.paymentMethod.name,
                newNote = newTemplate.note,
                newMerchant = newTemplate.merchant,
            )
        }
    }

    override suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long> =
        holder.withDatabase { db ->
            val (from, to) = monthBounds(yearMonth)
            db.financeQueries.sumExpensesByMonthAndCategory(fromDate = from.toString(), toDate = to.toString())
                .executeAsList()
                .associate { it.category_id to it.total }
        }

    override suspend fun monthlyTotal(yearMonth: YearMonth): Long =
        holder.withDatabase { db ->
            val (from, to) = monthBounds(yearMonth)
            db.financeQueries.sumExpensesByMonth(fromDate = from.toString(), toDate = to.toString()).executeAsOne()
        }
}
