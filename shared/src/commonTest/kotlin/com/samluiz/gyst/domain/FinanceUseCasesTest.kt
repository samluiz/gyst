package com.samluiz.gyst.domain

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class FinanceUseCasesTest {
    @Test
    fun createBudgetMonthRejectsNegative() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val useCase = CreateBudgetMonthUseCase(budgetRepo)

            assertFailsWith<FinanceError.NegativeAmount> {
                useCase(YearMonth(2026, 2), -1)
            }
        }

    @Test
    fun monthlySummaryComputesRemainingAndCommitments() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val expenseRepo = FakeExpenseRepository()
            val scheduleRepo = FakeScheduleRepository()

            val month = budgetRepo.createOrUpdateMonth(YearMonth(2026, 2), 5_000_00)
            budgetRepo.setAllocations(
                month.id,
                listOf(
                    BudgetAllocation("a1", month.id, "cat1", 2_000_00),
                    BudgetAllocation("a2", month.id, "cat2", 1_000_00),
                ),
            )
            expenseRepo.upsert(
                Expense(
                    id = "e1",
                    occurredAt = LocalDate(2026, 2, 5),
                    amountCents = 500_00,
                    categoryId = "cat1",
                    paymentMethod = PaymentMethod.PIX,
                    createdAt = Instant.parse("2026-02-05T00:00:00Z"),
                ),
            )
            scheduleRepo.commitments[YearMonth(2026, 2).toString()] = 1_700_00

            val result =
                ComputeMonthlySummaryUseCase(
                    budgetRepo,
                    expenseRepo,
                    scheduleRepo,
                )(YearMonth(2026, 2))

            assertEquals(5_000_00, result.totalIncomeCents)
            assertEquals(3_000_00, result.plannedTotalCents)
            assertEquals(500_00, result.spentTotalCents)
            assertEquals(2_800_00, result.remainingTotalCents)
            assertEquals(1_700_00, result.commitmentsCents)
        }

    @Test
    fun monthlySummaryCategoryIncludesExpensesSubscriptionsAndInstallments() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val expenseRepo = FakeExpenseRepository()
            val scheduleRepo = FakeScheduleRepository()
            val subscriptionRepo = FakeSubscriptionRepository()
            val installmentRepo = FakeInstallmentRepository()

            val month = YearMonth(2026, 3)
            val created = budgetRepo.createOrUpdateMonth(month, 2_500_00)
            budgetRepo.setAllocations(
                created.id,
                listOf(BudgetAllocation("a1", created.id, "cat-home", 2_000_00)),
            )

            expenseRepo.upsert(
                Expense(
                    id = "e1",
                    occurredAt = LocalDate(2026, 3, 6),
                    amountCents = 300_00,
                    categoryId = "cat-home",
                    paymentMethod = PaymentMethod.PIX,
                    createdAt = Instant.parse("2026-03-06T00:00:00Z"),
                ),
            )
            subscriptionRepo.upsert(
                Subscription(
                    id = "s1",
                    name = "Internet",
                    amountCents = 120_00,
                    billingDay = 10,
                    categoryId = "cat-home",
                    active = true,
                    startYearMonth = month,
                ),
            )
            installmentRepo.upsert(
                InstallmentPlan(
                    id = "i1",
                    name = "Notebook",
                    totalInstallments = 10,
                    totalAmountCents = 2_000_00,
                    monthlyAmountCents = 200_00,
                    startYearMonth = YearMonth(2026, 2),
                    endYearMonth = YearMonth(2026, 11),
                    categoryId = "cat-home",
                    active = true,
                ),
            )
            scheduleRepo.upsert(
                PaymentScheduleItem(
                    id = "ps-sub-1",
                    kind = ScheduleKind.SUBSCRIPTION,
                    refId = "s1",
                    categoryId = "cat-home",
                    dueDate = LocalDate(2026, 3, 10),
                    amountCents = 120_00,
                    status = ScheduleStatus.DUE,
                ),
            )
            scheduleRepo.upsert(
                PaymentScheduleItem(
                    id = "ps-ins-1",
                    kind = ScheduleKind.INSTALLMENT,
                    refId = "i1",
                    categoryId = "cat-home",
                    dueDate = LocalDate(2026, 3, 1),
                    amountCents = 200_00,
                    status = ScheduleStatus.DUE,
                ),
            )
            scheduleRepo.commitments[month.toString()] = 320_00

            val result =
                ComputeMonthlySummaryUseCase(
                    budgetRepo,
                    expenseRepo,
                    scheduleRepo,
                )(month)

            val home = result.perCategory.first { it.categoryId == "cat-home" }
            assertEquals(620_00, home.spentCents)
        }

    @Test
    fun billingDayClampsToMonthEnd() {
        val due = dueDateForMonth(YearMonth(2026, 2), 31)
        assertEquals(LocalDate(2026, 2, 28), due)
    }

    @Test
    fun installmentSchedulePreservesEveryCent() =
        runTest {
            val installmentRepo = FakeInstallmentRepository()
            val scheduleRepo = FakeScheduleRepository()
            val plan =
                InstallmentPlan(
                    id = "i-exact",
                    name = "Exact split",
                    totalInstallments = 3,
                    totalAmountCents = 100,
                    monthlyAmountCents = 34,
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = YearMonth(2026, 9),
                    categoryId = "cat",
                    active = true,
                )

            CreateInstallmentPlanUseCase(installmentRepo, scheduleRepo)(plan)

            assertEquals(listOf(34L, 33L, 33L), scheduleRepo.items.sortedBy { it.dueDate }.map { it.amountCents })
            assertEquals(100L, scheduleRepo.items.sumOf { it.amountCents })
        }

    @Test
    fun forecastShowsFreedCashAfterCommitmentEnds() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val scheduleRepo = FakeScheduleRepository()

            val monthA = budgetRepo.createOrUpdateMonth(YearMonth(2026, 7), 4_000_00)
            budgetRepo.setAllocations(monthA.id, listOf(BudgetAllocation("a1", monthA.id, "cat1", 2_000_00)))
            scheduleRepo.commitments[YearMonth(2026, 7).toString()] = 1_700_00

            val monthB = budgetRepo.createOrUpdateMonth(YearMonth(2026, 8), 4_000_00)
            budgetRepo.setAllocations(monthB.id, listOf(BudgetAllocation("a2", monthB.id, "cat1", 2_000_00)))
            scheduleRepo.commitments[YearMonth(2026, 8).toString()] = 0

            val out =
                ComputeCashFlowForecastUseCase(budgetRepo, scheduleRepo, FakeRecurringExpenseRepository())(
                    LocalDate(2026, 7, 1),
                    2,
                )

            assertEquals(2_300_00, out[0].expectedFreeBalanceCents)
            assertEquals(4_000_00, out[1].expectedFreeBalanceCents)
        }

    @Test
    fun forecastCarriesRecurringBaselineIntoFutureMonths() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val scheduleRepo = FakeScheduleRepository()
            val recurringRepo = FakeRecurringExpenseRepository()

            budgetRepo.createOrUpdateMonth(YearMonth(2026, 7), 4_000_00)
            recurringRepo.upsert(
                RecurringExpenseSeries(
                    id = "er1",
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = null,
                    dayOfMonth = 10,
                    amountCents = 700_00,
                    categoryId = "cat1",
                    note = null,
                    merchant = null,
                    paymentMethod = PaymentMethod.DEBIT,
                    active = true,
                ),
            )

            val out = ComputeCashFlowForecastUseCase(budgetRepo, scheduleRepo, recurringRepo)(LocalDate(2026, 7, 1), 3)

            assertEquals(700_00, out[0].recurringCents)
            assertEquals(700_00, out[1].recurringCents)
            assertEquals(700_00, out[2].recurringCents)
        }

    @Test
    fun forecastUsesMonthSpecificRecurringWhenPresent() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val scheduleRepo = FakeScheduleRepository()
            val recurringRepo = FakeRecurringExpenseRepository()

            budgetRepo.createOrUpdateMonth(YearMonth(2026, 7), 4_000_00)
            recurringRepo.upsert(
                RecurringExpenseSeries(
                    id = "er-base",
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = YearMonth(2026, 7),
                    dayOfMonth = 10,
                    amountCents = 700_00,
                    categoryId = "cat1",
                    note = null,
                    merchant = null,
                    paymentMethod = PaymentMethod.DEBIT,
                    active = true,
                ),
            )
            recurringRepo.upsert(
                RecurringExpenseSeries(
                    id = "er-aug",
                    startYearMonth = YearMonth(2026, 8),
                    endYearMonth = null,
                    dayOfMonth = 10,
                    amountCents = 900_00,
                    categoryId = "cat1",
                    note = null,
                    merchant = null,
                    paymentMethod = PaymentMethod.DEBIT,
                    active = true,
                ),
            )

            val out = ComputeCashFlowForecastUseCase(budgetRepo, scheduleRepo, recurringRepo)(LocalDate(2026, 7, 1), 2)

            assertEquals(700_00, out[0].recurringCents)
            assertEquals(900_00, out[1].recurringCents)
        }

    @Test
    fun rolloverCopiesMonthlyRecurringExpenseToTargetMonth() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val expenseRepo = FakeExpenseRepository()
            val recurringRepo = FakeRecurringExpenseRepository()
            val useCase = HandleMonthRolloverUseCase(budgetRepo, expenseRepo, recurringRepo)

            recurringRepo.upsert(
                RecurringExpenseSeries(
                    id = "prev-rec",
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = null,
                    dayOfMonth = 31,
                    amountCents = 550_00,
                    categoryId = "cat-rent",
                    note = "Rent",
                    merchant = null,
                    paymentMethod = PaymentMethod.TRANSFER,
                    active = true,
                ),
            )

            useCase(YearMonth(2026, 8))

            val august = expenseRepo.byMonth(YearMonth(2026, 8))
            assertEquals(1, august.size)
            assertEquals(550_00, august.first().amountCents)
            assertEquals(LocalDate(2026, 8, 31), august.first().occurredAt)
            assertEquals(RecurrenceType.MONTHLY, august.first().recurrenceType)
        }

    @Test
    fun rolloverDoesNotCopyOneTimeExpenses() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val expenseRepo = FakeExpenseRepository()
            val recurringRepo = FakeRecurringExpenseRepository()
            val useCase = HandleMonthRolloverUseCase(budgetRepo, expenseRepo, recurringRepo)

            expenseRepo.upsert(
                Expense(
                    id = "prev-one-time",
                    occurredAt = LocalDate(2026, 7, 15),
                    amountCents = 999_00,
                    categoryId = "cat-variable",
                    note = "New shoes",
                    paymentMethod = PaymentMethod.DEBIT,
                    recurrenceType = RecurrenceType.ONE_TIME,
                    createdAt = Instant.parse("2026-07-15T00:00:00Z"),
                ),
            )

            useCase(YearMonth(2026, 8))

            assertEquals(0, expenseRepo.byMonth(YearMonth(2026, 8)).size)
        }

    @Test
    fun rolloverIsIdempotentForAlreadyCopiedRecurringExpenses() =
        runTest {
            val budgetRepo = FakeBudgetRepository()
            val expenseRepo = FakeExpenseRepository()
            val recurringRepo = FakeRecurringExpenseRepository()
            val useCase = HandleMonthRolloverUseCase(budgetRepo, expenseRepo, recurringRepo)

            recurringRepo.upsert(
                RecurringExpenseSeries(
                    id = "prev-rec-1",
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = null,
                    dayOfMonth = 10,
                    amountCents = 400_00,
                    categoryId = "cat-sub",
                    note = "Cloud",
                    merchant = null,
                    paymentMethod = PaymentMethod.DEBIT,
                    active = true,
                ),
            )

            useCase(YearMonth(2026, 8))
            useCase(YearMonth(2026, 8))

            val august = expenseRepo.byMonth(YearMonth(2026, 8))
            assertEquals(1, august.size)
            assertEquals(400_00, august.first().amountCents)
        }
}

private class FakeBudgetRepository : BudgetRepository {
    private val months = mutableMapOf<String, BudgetMonth>()
    private val allocations = mutableMapOf<String, List<BudgetAllocation>>()

    override suspend fun createOrUpdateMonth(
        yearMonth: YearMonth,
        incomeCents: Long,
    ): BudgetMonth {
        val key = yearMonth.toString()
        val value =
            months[key]?.copy(totalIncomeCents = incomeCents)
                ?: BudgetMonth("b-$key", yearMonth, incomeCents, Instant.parse("2026-01-01T00:00:00Z"))
        months[key] = value
        return value
    }

    override suspend fun findByYearMonth(yearMonth: YearMonth): BudgetMonth? = months[yearMonth.toString()]

    override suspend fun setAllocations(
        budgetMonthId: String,
        allocations: List<BudgetAllocation>,
    ) {
        this.allocations[budgetMonthId] = allocations
    }

    override suspend fun allocationsByBudgetMonth(budgetMonthId: String): List<BudgetAllocation> = allocations[budgetMonthId].orEmpty()

    override suspend fun listMonths(): List<YearMonth> = months.values.map { it.yearMonth }.sortedDescending()
}

private class FakeExpenseRepository : ExpenseRepository {
    private val expenses = mutableListOf<Expense>()

    override suspend fun upsert(expense: Expense) {
        expenses.removeAll { it.id == expense.id }
        expenses.add(expense)
    }

    override suspend fun delete(id: String) {
        expenses.removeAll { it.id == id }
    }

    override suspend fun getById(id: String): Expense? = expenses.find { it.id == id }

    override suspend fun byMonth(yearMonth: YearMonth): List<Expense> = expenses.filter { YearMonth.fromDate(it.occurredAt) == yearMonth }

    override suspend fun byMonthPaged(
        yearMonth: YearMonth,
        limit: Long,
        offset: Long,
    ): List<Expense> {
        return byMonth(yearMonth)
            .sortedByDescending { it.occurredAt.toString() + it.createdAt.toString() }
            .drop(offset.toInt())
            .take(limit.toInt())
    }

    override suspend fun search(
        yearMonth: YearMonth,
        categoryId: String?,
        query: String?,
    ): List<Expense> = byMonth(yearMonth)

    override suspend fun deleteFutureRecurringBySeries(
        fromDateExclusive: LocalDate,
        seriesId: String,
    ) {
        expenses.removeAll {
            it.occurredAt > fromDateExclusive &&
                it.recurrenceType == RecurrenceType.MONTHLY &&
                it.scheduleItemId == null &&
                it.recurrenceSeriesId == seriesId
        }
    }

    override suspend fun updateFutureRecurringBySeries(
        fromDateExclusive: LocalDate,
        seriesId: String,
        newTemplate: Expense,
    ) {
        val updated =
            expenses.map {
                if (
                    it.occurredAt > fromDateExclusive &&
                    it.recurrenceType == RecurrenceType.MONTHLY &&
                    it.scheduleItemId == null &&
                    it.recurrenceSeriesId == seriesId
                ) {
                    it.copy(
                        categoryId = newTemplate.categoryId,
                        amountCents = newTemplate.amountCents,
                        note = newTemplate.note,
                        merchant = newTemplate.merchant,
                        paymentMethod = newTemplate.paymentMethod,
                    )
                } else {
                    it
                }
            }
        expenses.clear()
        expenses.addAll(updated)
    }

    override suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long> =
        byMonth(yearMonth).groupBy {
            it.categoryId
        }.mapValues { it.value.sumOf(Expense::amountCents) }

    override suspend fun monthlyTotal(yearMonth: YearMonth): Long = byMonth(yearMonth).sumOf(Expense::amountCents)
}

private class FakeScheduleRepository : ScheduleRepository {
    val commitments = mutableMapOf<String, Long>()
    private val byId = mutableMapOf<String, PaymentScheduleItem>()
    val items: List<PaymentScheduleItem> get() = byId.values.toList()

    override suspend fun upsert(item: PaymentScheduleItem) {
        byId[item.id] = item
    }

    override suspend fun byDateRange(
        from: LocalDate,
        to: LocalDate,
    ): List<PaymentScheduleItem> = byId.values.toList()

    override suspend fun findByRefAndDate(
        refId: String,
        dueDate: LocalDate,
    ): PaymentScheduleItem? =
        byId.values.find {
            it.refId == refId && it.dueDate == dueDate
        }

    override suspend fun findById(id: String): PaymentScheduleItem? = byId[id]

    override suspend fun deleteByRefAndKindFromDate(
        refId: String,
        kind: ScheduleKind,
        fromDateInclusive: LocalDate,
    ) {
        byId.values.removeAll { it.refId == refId && it.kind == kind && it.dueDate >= fromDateInclusive }
    }

    override suspend fun markStatus(
        id: String,
        status: ScheduleStatus,
        paidAtIso: String?,
    ) {}

    override suspend fun commitmentsForMonth(yearMonth: YearMonth): Long = commitments[yearMonth.toString()] ?: 0
}

private class FakeRecurringExpenseRepository : RecurringExpenseRepository {
    private val series = mutableMapOf<String, RecurringExpenseSeries>()

    override suspend fun upsert(series: RecurringExpenseSeries) {
        this.series[series.id] = series
    }

    override suspend fun findById(id: String): RecurringExpenseSeries? = series[id]

    override suspend fun activeForMonth(yearMonth: YearMonth): List<RecurringExpenseSeries> =
        series.values.filter {
            it.startYearMonth <= yearMonth && (it.endYearMonth == null || it.endYearMonth >= yearMonth)
        }

    override suspend fun endBefore(
        id: String,
        lastActiveMonth: YearMonth,
    ) {
        series[id]?.let { series[id] = it.copy(active = false, endYearMonth = lastActiveMonth) }
    }
}

private class FakeInstallmentRepository : InstallmentRepository {
    private val plans = mutableListOf<InstallmentPlan>()

    override suspend fun upsert(plan: InstallmentPlan) {
        plans.removeAll { it.id == plan.id }
        plans.add(plan)
    }

    override suspend fun deactivate(id: String) {
        val index = plans.indexOfFirst { it.id == id }
        if (index >= 0) plans[index] = plans[index].copy(active = false)
    }

    override suspend fun list(): List<InstallmentPlan> = plans.toList()

    override suspend fun listActive(): List<InstallmentPlan> = plans.filter { it.active }
}

private class FakeSubscriptionRepository : SubscriptionRepository {
    private val subscriptions = mutableListOf<Subscription>()

    override suspend fun upsert(subscription: Subscription) {
        subscriptions.removeAll { it.id == subscription.id }
        subscriptions.add(subscription)
    }

    override suspend fun deactivate(id: String) {
        val index = subscriptions.indexOfFirst { it.id == id }
        if (index >= 0) subscriptions[index] = subscriptions[index].copy(active = false)
    }

    override suspend fun list(): List<Subscription> = subscriptions.toList()

    override suspend fun listActive(): List<Subscription> = subscriptions.filter { it.active }
}

private class FakeSettingsRepository : SettingsRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}
