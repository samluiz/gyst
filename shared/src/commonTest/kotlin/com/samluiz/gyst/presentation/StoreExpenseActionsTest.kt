package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.ExpenseRepository
import com.samluiz.gyst.domain.repository.InstallmentRepository
import com.samluiz.gyst.domain.repository.ScheduleRepository
import com.samluiz.gyst.domain.repository.SubscriptionRepository
import com.samluiz.gyst.domain.usecase.AddOrUpdateExpenseUseCase
import com.samluiz.gyst.domain.usecase.CreateInstallmentPlanUseCase
import com.samluiz.gyst.domain.usecase.UpsertSubscriptionUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreExpenseActionsTest {
    @Test
    fun addInstallmentCreatesPlan() = runTest {
        val expenseRepo = FakeExpenseRepo()
        val subRepo = FakeSubscriptionRepo()
        val instRepo = FakeInstallmentRepo()
        val scheduleRepo = FakeScheduleRepo()
        var state = MainState()

        val actions =
            StoreExpenseActions(
                expenseRepository = expenseRepo,
                subscriptionRepository = subRepo,
                installmentRepository = instRepo,
                scheduleRepository = scheduleRepo,
                addOrUpdateExpenseUseCase = AddOrUpdateExpenseUseCase(expenseRepo),
                upsertSubscriptionUseCase = UpsertSubscriptionUseCase(subRepo, scheduleRepo),
                createInstallmentPlanUseCase = CreateInstallmentPlanUseCase(instRepo, scheduleRepo),
                getState = { state },
                setState = { state = it },
                refresh = { },
            )

        actions.addInstallment(
            draft =
                InstallmentDraft(
                    name = "Phone",
                    amountCents = 2_000_00L,
                    totalInstallments = 10,
                    categoryId = "cat-a",
                ),
            requireCategoryId = { },
            requireName = { it },
            toMonthlyInstallmentCents = { total, count -> total / count },
        )

        val created = instRepo.list().single()
        assertEquals("Phone", created.name)
        assertEquals(200_00L, created.monthlyAmountCents)
    }

    @Test
    fun addExpenseCreatesMonthlyExpenseWhenRequested() = runTest {
        val expenseRepo = FakeExpenseRepo()
        val subRepo = FakeSubscriptionRepo()
        val instRepo = FakeInstallmentRepo()
        val scheduleRepo = FakeScheduleRepo()
        var state = MainState(currentMonth = YearMonth(2026, 5))

        val actions =
            StoreExpenseActions(
                expenseRepository = expenseRepo,
                subscriptionRepository = subRepo,
                installmentRepository = instRepo,
                scheduleRepository = scheduleRepo,
                addOrUpdateExpenseUseCase = AddOrUpdateExpenseUseCase(expenseRepo),
                upsertSubscriptionUseCase = UpsertSubscriptionUseCase(subRepo, scheduleRepo),
                createInstallmentPlanUseCase = CreateInstallmentPlanUseCase(instRepo, scheduleRepo),
                getState = { state },
                setState = { state = it },
                refresh = { },
            )

        actions.addExpense(
            draft = ExpenseDraft(120_00, "cat-a", "  internet ", recurringMonthly = true),
            sanitizeNote = { it?.trim() },
            requireCategoryId = { },
        )

        val created = expenseRepo.byMonth(YearMonth(2026, 5)).single()
        assertEquals(RecurrenceType.MONTHLY, created.recurrenceType)
        assertEquals("internet", created.note)
    }
}

private class FakeExpenseRepo : ExpenseRepository {
    private val items = mutableListOf<Expense>()

    override suspend fun upsert(expense: Expense) {
        items.removeAll { it.id == expense.id }
        items += expense
    }

    override suspend fun delete(id: String) {
        items.removeAll { it.id == id }
    }

    override suspend fun getById(id: String): Expense? = items.firstOrNull { it.id == id }

    override suspend fun byMonth(yearMonth: YearMonth): List<Expense> = items.filter { YearMonth.fromDate(it.occurredAt) == yearMonth }

    override suspend fun byMonthPaged(yearMonth: YearMonth, limit: Long, offset: Long): List<Expense> = byMonth(yearMonth)

    override suspend fun search(yearMonth: YearMonth, categoryId: String?, query: String?): List<Expense> = byMonth(yearMonth)

    override suspend fun deleteFutureRecurringByTemplate(fromDateExclusive: LocalDate, template: Expense) = Unit

    override suspend fun updateFutureRecurringByTemplate(fromDateExclusive: LocalDate, oldTemplate: Expense, newTemplate: Expense) = Unit

    override suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long> = emptyMap()

    override suspend fun monthlyTotal(yearMonth: YearMonth): Long = 0L

    override suspend fun monthlyRecurringTotal(yearMonth: YearMonth): Long = 0L
}

private class FakeSubscriptionRepo : SubscriptionRepository {
    private val items = mutableListOf<Subscription>()

    override suspend fun upsert(subscription: Subscription) {
        items.removeAll { it.id == subscription.id }
        items += subscription
    }

    override suspend fun delete(id: String) {
        items.removeAll { it.id == id }
    }

    override suspend fun list(): List<Subscription> = items.toList()

    override suspend fun listActive(): List<Subscription> = items.filter { it.active }
}

private class FakeInstallmentRepo : InstallmentRepository {
    private val items = mutableListOf<InstallmentPlan>()

    override suspend fun upsert(plan: InstallmentPlan) {
        items.removeAll { it.id == plan.id }
        items += plan
    }

    override suspend fun delete(id: String) {
        items.removeAll { it.id == id }
    }

    override suspend fun list(): List<InstallmentPlan> = items.toList()

    override suspend fun listActive(): List<InstallmentPlan> = items.filter { it.active }
}

private class FakeScheduleRepo : ScheduleRepository {
    override suspend fun upsert(item: PaymentScheduleItem) = Unit

    override suspend fun byDateRange(from: LocalDate, to: LocalDate): List<PaymentScheduleItem> = emptyList()

    override suspend fun findByRefAndDate(refId: String, dueDate: LocalDate): PaymentScheduleItem? = null

    override suspend fun findById(id: String): PaymentScheduleItem? = null

    override suspend fun deleteByRefAndKind(refId: String, kind: ScheduleKind) = Unit

    override suspend fun deleteByRefAndKindFromDate(refId: String, kind: ScheduleKind, fromDateInclusive: LocalDate) = Unit

    override suspend fun markStatus(id: String, status: ScheduleStatus, paidAtIso: String?) = Unit

    override suspend fun commitmentsForMonth(yearMonth: YearMonth): Long = 0L
}
