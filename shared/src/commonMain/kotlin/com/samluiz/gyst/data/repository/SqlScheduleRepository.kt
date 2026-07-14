package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.domain.usecase.monthBounds
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

class SqlScheduleRepository(private val holder: DatabaseHolder) : ScheduleRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(item: PaymentScheduleItem) {
        q.upsertScheduleItem(
            kind = item.kind.name,
            ref_id = item.refId,
            subscription_id = item.refId.takeIf { item.kind == ScheduleKind.SUBSCRIPTION },
            installment_plan_id = item.refId.takeIf { item.kind == ScheduleKind.INSTALLMENT },
            due_date = item.dueDate.toString(),
            amount_cents = item.amountCents,
            category_id = item.categoryId,
            status = item.status.name,
            paid_at = item.paidAt?.toString(),
            id = item.id,
            id_ = item.id,
            kind_ = item.kind.name,
            ref_id_ = item.refId,
            subscription_id_ = item.refId.takeIf { item.kind == ScheduleKind.SUBSCRIPTION },
            installment_plan_id_ = item.refId.takeIf { item.kind == ScheduleKind.INSTALLMENT },
            due_date_ = item.dueDate.toString(),
            amount_cents_ = item.amountCents,
            category_id_ = item.categoryId,
            status_ = item.status.name,
            paid_at_ = item.paidAt?.toString(),
        )
    }

    override suspend fun byDateRange(
        from: LocalDate,
        to: LocalDate,
    ): List<PaymentScheduleItem> {
        return q.selectScheduleByDateRange(fromDate = from.toString(), toDate = to.toString()).executeAsList().map {
            PaymentScheduleItem(
                id = it.id,
                kind = ScheduleKind.valueOf(it.kind),
                refId = it.ref_id,
                categoryId = it.category_id,
                dueDate = LocalDate.parse(it.due_date),
                amountCents = it.amount_cents,
                status = ScheduleStatus.valueOf(it.status),
                paidAt = it.paid_at?.let(Instant::parse),
            )
        }
    }

    override suspend fun findByRefAndDate(
        refId: String,
        dueDate: LocalDate,
    ): PaymentScheduleItem? {
        return q.selectDueScheduleItemsByRefAndDate(refId = refId, dueDate = dueDate.toString()).executeAsOneOrNull()?.let {
            PaymentScheduleItem(
                id = it.id,
                kind = ScheduleKind.valueOf(it.kind),
                refId = it.ref_id,
                categoryId = it.category_id,
                dueDate = LocalDate.parse(it.due_date),
                amountCents = it.amount_cents,
                status = ScheduleStatus.valueOf(it.status),
                paidAt = it.paid_at?.let(Instant::parse),
            )
        }
    }

    override suspend fun findById(id: String): PaymentScheduleItem? {
        return q.selectScheduleItemById(id).executeAsOneOrNull()?.let {
            PaymentScheduleItem(
                id = it.id,
                kind = ScheduleKind.valueOf(it.kind),
                refId = it.ref_id,
                categoryId = it.category_id,
                dueDate = LocalDate.parse(it.due_date),
                amountCents = it.amount_cents,
                status = ScheduleStatus.valueOf(it.status),
                paidAt = it.paid_at?.let(Instant::parse),
            )
        }
    }

    override suspend fun deleteByRefAndKindFromDate(
        refId: String,
        kind: ScheduleKind,
        fromDateInclusive: LocalDate,
    ) {
        q.deleteScheduleItemsByRefAndKindFromDate(
            refId = refId,
            kind = kind.name,
            fromDate = fromDateInclusive.toString(),
        )
    }

    override suspend fun markStatus(
        id: String,
        status: ScheduleStatus,
        paidAtIso: String?,
    ) {
        q.updateScheduleStatus(status.name, paidAtIso, id)
    }

    override suspend fun commitmentsForMonth(yearMonth: YearMonth): Long {
        val (from, to) = monthBounds(yearMonth)
        return q.sumDueByMonthAndKinds(
            fromDate = from.toString(),
            toDate = to.toString(),
            firstKind = ScheduleKind.SUBSCRIPTION.name,
            secondKind = ScheduleKind.INSTALLMENT.name,
        )
            .executeAsOne()
    }
}
