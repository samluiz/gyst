package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.ExpenseRepository
import com.samluiz.gyst.domain.repository.InstallmentRepository
import com.samluiz.gyst.domain.repository.RecurringExpenseRepository
import com.samluiz.gyst.domain.repository.ScheduleRepository
import com.samluiz.gyst.domain.repository.SubscriptionRepository
import com.samluiz.gyst.domain.usecase.AddOrUpdateExpenseUseCase
import com.samluiz.gyst.domain.usecase.CreateInstallmentPlanUseCase
import com.samluiz.gyst.domain.usecase.DeleteExpenseUseCase
import com.samluiz.gyst.domain.usecase.UpsertSubscriptionUseCase
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.domain.usecase.installmentAmountAt
import com.samluiz.gyst.domain.usecase.monthBounds
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

internal class StoreExpenseActions(
    private val expenseRepository: ExpenseRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val installmentRepository: InstallmentRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val scheduleRepository: ScheduleRepository,
    private val addOrUpdateExpenseUseCase: AddOrUpdateExpenseUseCase,
    private val deleteExpenseUseCase: DeleteExpenseUseCase = DeleteExpenseUseCase(expenseRepository),
    private val upsertSubscriptionUseCase: UpsertSubscriptionUseCase,
    private val createInstallmentPlanUseCase: CreateInstallmentPlanUseCase,
    private val getState: () -> MainState,
    private val refresh: suspend (Boolean) -> Unit,
) {
    suspend fun addExpense(
        draft: ExpenseDraft,
        sanitizeNote: (String?) -> String?,
        requireCategoryId: (String) -> Unit,
    ) {
        requireNonNegative(draft.amountCents)
        requireCategoryId(draft.categoryId)
        val nowDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val targetMonth = getState().currentMonth
        val day = nowDate.day.coerceAtMost(monthBounds(targetMonth).second.day)
        val occurredAt = LocalDate(targetMonth.year, targetMonth.month, day)
        val recurrenceSeriesId = id("rec").takeIf { draft.recurringMonthly }
        val expense =
            Expense(
                id = id("exp"),
                occurredAt = occurredAt,
                amountCents = draft.amountCents,
                categoryId = draft.categoryId,
                note = sanitizeNote(draft.note),
                paymentMethod = PaymentMethod.DEBIT,
                recurrenceType = if (draft.recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
                createdAt = Clock.System.now(),
                recurrenceSeriesId = recurrenceSeriesId,
            )
        if (recurrenceSeriesId != null) {
            recurringExpenseRepository.upsert(expense.toRecurringSeries(recurrenceSeriesId))
        }
        addOrUpdateExpenseUseCase(expense)
        refresh(false)
    }

    suspend fun addSubscription(
        draft: SubscriptionDraft,
        requireCategoryId: (String) -> Unit,
        requireName: (String) -> String,
    ) {
        requireNonNegative(draft.amountCents)
        requireCategoryId(draft.categoryId)
        val safeName = requireName(draft.name)
        val safeDay = draft.billingDay.coerceIn(1, 31)
        upsertSubscriptionUseCase(
            Subscription(
                id = id("sub"),
                name = safeName,
                amountCents = draft.amountCents,
                billingDay = safeDay,
                categoryId = draft.categoryId,
                active = true,
                startYearMonth = getState().currentMonth,
            ),
            scheduleStartYearMonth = getState().currentMonth,
        )
        refresh(false)
    }

    suspend fun addInstallment(
        draft: InstallmentDraft,
        requireCategoryId: (String) -> Unit,
        requireName: (String) -> String,
    ) {
        requireNonNegative(draft.amountCents)
        requireCategoryId(draft.categoryId)
        val safeName = requireName(draft.name)
        val safeInstallments = draft.totalInstallments.coerceIn(1, 360)
        val monthlyAmountCents = installmentAmountAt(draft.amountCents, safeInstallments, 0)
        val start = getState().currentMonth
        val end = start.plusMonths(safeInstallments - 1)
        createInstallmentPlanUseCase(
            InstallmentPlan(
                id = id("inst"),
                name = safeName,
                totalInstallments = safeInstallments,
                totalAmountCents = draft.amountCents,
                monthlyAmountCents = monthlyAmountCents,
                startYearMonth = start,
                endYearMonth = end,
                categoryId = draft.categoryId,
                active = true,
            ),
        )
        refresh(false)
    }

    suspend fun updateExpense(
        expenseId: String,
        amountCents: Long,
        categoryId: String,
        description: String?,
        recurringMonthly: Boolean,
    ) {
        requireNonNegative(amountCents)
        requireCategoryId(categoryId)
        val current = expenseRepository.getById(expenseId) ?: return
        var updated =
            current.copy(
                amountCents = amountCents,
                categoryId = categoryId,
                note = sanitizeOptionalNote(description),
                recurrenceType = if (recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
            )
        val currentSeriesId = current.recurrenceSeriesId
        when {
            recurringMonthly && currentSeriesId != null -> {
                val existingSeries = recurringExpenseRepository.findById(currentSeriesId)
                recurringExpenseRepository.upsert(
                    updated.toRecurringSeries(currentSeriesId).copy(
                        startYearMonth = existingSeries?.startYearMonth ?: YearMonth.fromDate(current.occurredAt),
                        endYearMonth = existingSeries?.endYearMonth,
                        active = existingSeries?.active ?: true,
                    ),
                )
                expenseRepository.updateFutureRecurringBySeries(
                    fromDateExclusive = current.occurredAt,
                    seriesId = currentSeriesId,
                    newTemplate = updated.copy(recurrenceType = RecurrenceType.MONTHLY),
                )
            }
            recurringMonthly -> {
                val newSeriesId = id("rec")
                updated = updated.copy(recurrenceSeriesId = newSeriesId)
                recurringExpenseRepository.upsert(updated.toRecurringSeries(newSeriesId))
            }
            currentSeriesId != null -> {
                recurringExpenseRepository.endBefore(currentSeriesId, YearMonth.fromDate(current.occurredAt).plusMonths(-1))
                expenseRepository.deleteFutureRecurringBySeries(
                    fromDateExclusive = current.occurredAt,
                    seriesId = currentSeriesId,
                )
                updated = updated.copy(recurrenceSeriesId = null)
            }
        }
        addOrUpdateExpenseUseCase(updated)
        refresh(false)
    }

    suspend fun deleteExpense(expenseId: String) {
        val current = expenseRepository.getById(expenseId)
        if (current?.recurrenceSeriesId != null) {
            expenseRepository.deleteRecurringFromOccurrence(
                expenseId = current.id,
                occurrenceDate = current.occurredAt,
                seriesId = current.recurrenceSeriesId,
                lastActiveMonth = YearMonth.fromDate(current.occurredAt).plusMonths(-1),
            )
        } else {
            deleteExpenseUseCase(expenseId)
        }
        refresh(false)
    }

    suspend fun updateSubscription(
        subscriptionId: String,
        name: String,
        amountCents: Long,
        billingDay: Int,
        categoryId: String,
    ) {
        requireNonNegative(amountCents)
        requireCategoryId(categoryId)
        val safeName = requireName(name)
        val safeDay = billingDay.coerceIn(1, 31)
        val currentMonth = getState().currentMonth
        val monthStart = LocalDate(currentMonth.year, currentMonth.month, 1)
        scheduleRepository.deleteByRefAndKindFromDate(subscriptionId, ScheduleKind.SUBSCRIPTION, monthStart)
        upsertSubscriptionUseCase(
            Subscription(
                id = subscriptionId,
                name = safeName,
                amountCents = amountCents,
                billingDay = safeDay,
                categoryId = categoryId,
                active = true,
                startYearMonth =
                    subscriptionRepository.list().firstOrNull { it.id == subscriptionId }?.startYearMonth ?: currentMonth,
            ),
            scheduleStartYearMonth = currentMonth,
        )
        refresh(false)
    }

    suspend fun deleteSubscription(subscriptionId: String) {
        val currentMonth = getState().currentMonth
        scheduleRepository.deleteByRefAndKindFromDate(
            subscriptionId,
            ScheduleKind.SUBSCRIPTION,
            LocalDate(currentMonth.year, currentMonth.month, 1),
        )
        subscriptionRepository.deactivate(subscriptionId)
        refresh(false)
    }

    suspend fun updateInstallment(
        installmentId: String,
        name: String,
        amountCents: Long,
        totalInstallments: Int,
        categoryId: String,
    ) {
        requireNonNegative(amountCents)
        requireCategoryId(categoryId)
        val safeName = requireName(name)
        val safeInstallments = totalInstallments.coerceIn(1, 360)
        val monthlyAmountCents = installmentAmountAt(amountCents, safeInstallments, 0)
        val current = installmentRepository.list().firstOrNull { it.id == installmentId } ?: return
        val currentMonth = getState().currentMonth
        val start = current.startYearMonth
        val monthStart = LocalDate(currentMonth.year, currentMonth.month, 1)
        scheduleRepository.deleteByRefAndKindFromDate(installmentId, ScheduleKind.INSTALLMENT, monthStart)
        createInstallmentPlanUseCase(
            InstallmentPlan(
                id = installmentId,
                name = safeName,
                totalInstallments = safeInstallments,
                totalAmountCents = amountCents,
                monthlyAmountCents = monthlyAmountCents,
                startYearMonth = start,
                endYearMonth = start.plusMonths(safeInstallments - 1),
                categoryId = categoryId,
                active = true,
            ),
        )
        refresh(false)
    }

    suspend fun deleteInstallment(installmentId: String) {
        val currentMonth = getState().currentMonth
        scheduleRepository.deleteByRefAndKindFromDate(
            installmentId,
            ScheduleKind.INSTALLMENT,
            LocalDate(currentMonth.year, currentMonth.month, 1),
        )
        installmentRepository.deactivate(installmentId)
        refresh(false)
    }

    suspend fun duplicateExpense(expenseId: String) {
        val current = expenseRepository.getById(expenseId) ?: return
        val targetMonth = getState().currentMonth
        val day = current.occurredAt.day.coerceAtMost(monthBounds(targetMonth).second.day)
        addOrUpdateExpenseUseCase(
            current.copy(
                id = id("exp"),
                occurredAt = LocalDate(targetMonth.year, targetMonth.month, day),
                createdAt = Clock.System.now(),
                scheduleItemId = null,
                recurrenceType = RecurrenceType.ONE_TIME,
                recurrenceSeriesId = null,
            ),
        )
        refresh(false)
    }

    suspend fun duplicateSubscription(subscriptionId: String) {
        val current = subscriptionRepository.list().firstOrNull { it.id == subscriptionId } ?: return
        upsertSubscriptionUseCase(
            current.copy(
                id = id("sub"),
                startYearMonth = getState().currentMonth,
            ),
            scheduleStartYearMonth = getState().currentMonth,
        )
        refresh(false)
    }

    suspend fun duplicateInstallment(installmentId: String) {
        val current = installmentRepository.list().firstOrNull { it.id == installmentId } ?: return
        val start = getState().currentMonth
        val safeInstallments = current.totalInstallments.coerceIn(1, 360)
        val monthlyAmountCents = installmentAmountAt(current.totalAmountCents, safeInstallments, 0)
        createInstallmentPlanUseCase(
            current.copy(
                id = id("inst"),
                monthlyAmountCents = monthlyAmountCents,
                startYearMonth = start,
                endYearMonth = start.plusMonths(safeInstallments - 1),
            ),
        )
        refresh(false)
    }

    private fun requireCategoryId(categoryId: String) {
        require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
    }

    private fun requireName(name: String): String {
        val safeName = name.trim().take(120)
        require(safeName.isNotBlank()) { "Descricao obrigatoria" }
        return safeName
    }

    private fun sanitizeOptionalNote(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(120)
}

private fun Expense.toRecurringSeries(seriesId: String) =
    RecurringExpenseSeries(
        id = seriesId,
        startYearMonth = YearMonth.fromDate(occurredAt),
        endYearMonth = null,
        dayOfMonth = occurredAt.day,
        amountCents = amountCents,
        categoryId = categoryId,
        note = note,
        merchant = merchant,
        paymentMethod = paymentMethod,
        active = true,
    )
