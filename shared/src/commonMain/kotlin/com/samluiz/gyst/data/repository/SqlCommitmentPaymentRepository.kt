package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.id
import kotlin.time.Clock

class SqlCommitmentPaymentRepository(private val holder: DatabaseHolder) : CommitmentPaymentRepository {
    override suspend fun markSchedulePaidAndCreateExpense(
        scheduleItemId: String,
        categoryId: String,
        paymentMethod: PaymentMethod,
        note: String?,
    ) {
        holder.withDatabase { db ->
            val q = db.financeQueries
            db.transaction {
                val item = q.selectScheduleItemById(scheduleItemId).executeAsOneOrNull() ?: return@transaction
                val paidAt = Clock.System.now().toString()
                val expenseId = id("exp-sch")
                q.updateScheduleStatus(ScheduleStatus.PAID.name, paidAt, scheduleItemId)
                q.upsertExpense(
                    occurred_at = item.due_date,
                    amount_cents = item.amount_cents,
                    category_id = categoryId,
                    note = note,
                    merchant = null,
                    payment_method = paymentMethod.name,
                    recurrence_type = RecurrenceType.ONE_TIME.name,
                    created_at = paidAt,
                    schedule_item_id = scheduleItemId,
                    recurrence_series_id = null,
                    id = expenseId,
                    id_ = expenseId,
                    occurred_at_ = item.due_date,
                    amount_cents_ = item.amount_cents,
                    category_id_ = categoryId,
                    note_ = note,
                    merchant_ = null,
                    payment_method_ = paymentMethod.name,
                    recurrence_type_ = RecurrenceType.ONE_TIME.name,
                    created_at_ = paidAt,
                    schedule_item_id_ = scheduleItemId,
                    recurrence_series_id_ = null,
                )
            }
        }
    }
}
