package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.ExpenseRepository
import com.samluiz.gyst.domain.repository.InstallmentRepository
import com.samluiz.gyst.domain.repository.ScheduleRepository
import com.samluiz.gyst.domain.repository.SubscriptionRepository
import com.samluiz.gyst.domain.usecase.AddOrUpdateExpenseUseCase
import com.samluiz.gyst.domain.usecase.CreateInstallmentPlanUseCase
import com.samluiz.gyst.domain.usecase.UpsertSubscriptionUseCase
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.domain.usecase.monthBounds
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

internal class StoreExpenseActions(
    private val expenseRepository: ExpenseRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val installmentRepository: InstallmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val addOrUpdateExpenseUseCase: AddOrUpdateExpenseUseCase,
    private val upsertSubscriptionUseCase: UpsertSubscriptionUseCase,
    private val createInstallmentPlanUseCase: CreateInstallmentPlanUseCase,
    private val getState: () -> MainState,
    private val setState: (MainState) -> Unit,
    private val refresh: suspend (Boolean) -> Unit,
) {
    suspend fun addExpense(draft: ExpenseDraft, sanitizeNote: (String?) -> String?, requireCategoryId: (String) -> Unit) {
        requireNonNegative(draft.amountCents)
        requireCategoryId(draft.categoryId)
        val nowDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val targetMonth = getState().currentMonth
        val day = nowDate.day.coerceAtMost(monthBounds(targetMonth).second.day)
        val occurredAt = LocalDate(targetMonth.year, targetMonth.month, day)
        addOrUpdateExpenseUseCase(
            Expense(
                id = id("exp"),
                occurredAt = occurredAt,
                amountCents = draft.amountCents,
                categoryId = draft.categoryId,
                note = sanitizeNote(draft.note),
                paymentMethod = PaymentMethod.DEBIT,
                recurrenceType = if (draft.recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
                createdAt = Clock.System.now(),
            ),
        )
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
            ),
            scheduleStartYearMonth = getState().currentMonth,
        )
        refresh(false)
    }

    suspend fun addInstallment(
        draft: InstallmentDraft,
        requireCategoryId: (String) -> Unit,
        requireName: (String) -> String,
        toMonthlyInstallmentCents: (Long, Int) -> Long,
    ) {
        requireNonNegative(draft.amountCents)
        requireCategoryId(draft.categoryId)
        val safeName = requireName(draft.name)
        val safeInstallments = draft.totalInstallments.coerceIn(1, 360)
        val monthlyAmountCents = toMonthlyInstallmentCents(draft.amountCents, safeInstallments)
        val start = getState().currentMonth
        val end = start.plusMonths(safeInstallments - 1)
        createInstallmentPlanUseCase(
            InstallmentPlan(
                id = id("inst"),
                name = safeName,
                totalInstallments = safeInstallments,
                monthlyAmountCents = monthlyAmountCents,
                startYearMonth = start,
                endYearMonth = end,
                categoryId = draft.categoryId,
                active = true,
            ),
        )
        refresh(false)
    }
}
