package com.samluiz.gyst.domain

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinanceUseCasesTest {
    @Test
    fun createBudgetMonthRejectsNegative() = runTest {
        val budgetRepo = FakeBudgetRepository()
        val useCase = CreateBudgetMonthUseCase(budgetRepo)

        assertFailsWith<FinanceError.NegativeAmount> {
            useCase(YearMonth(2026, 2), -1)
        }
    }

    @Test
    fun monthlySummaryComputesRemainingAndCommitments() = runTest {
        val budgetRepo = FakeBudgetRepository()
        val expenseRepo = FakeExpenseRepository()
        val scheduleRepo = FakeScheduleRepository()

        val month = budgetRepo.createOrUpdateMonth(YearMonth(2026, 2), 5_000_00)
        budgetRepo.setAllocations(
            month.id,
            listOf(
                BudgetAllocation("a1", month.id, "cat1", 2_000_00),
                BudgetAllocation("a2", month.id, "cat2", 1_000_00),
            )
        )
        expenseRepo.upsert(
            Expense(
                id = "e1",
                occurredAt = LocalDate(2026, 2, 5),
                amountCents = 500_00,
                categoryId = "cat1",
                paymentMethod = PaymentMethod.PIX,
                createdAt = Instant.parse("2026-02-05T00:00:00Z"),
            )
        )
        scheduleRepo.commitments[YearMonth(2026, 2).toString()] = 1_700_00

        val result = ComputeMonthlySummaryUseCase(budgetRepo, expenseRepo, scheduleRepo)(YearMonth(2026, 2))

        assertEquals(5_000_00, result.totalIncomeCents)
        assertEquals(3_000_00, result.plannedTotalCents)
        assertEquals(500_00, result.spentTotalCents)
        assertEquals(4_500_00, result.remainingTotalCents)
        assertEquals(1_700_00, result.commitmentsCents)
    }

    @Test
    fun noNewInstallmentsGuardBlocksCreation() = runTest {
        val useCase = CreateInstallmentPlanUseCase(
            installmentRepository = FakeInstallmentRepository(),
            scheduleRepository = FakeScheduleRepository(),
            settingsRepository = FakeSettingsRepository(
                SafetyGuard(id = "g1", noNewInstallments = true)
            ),
        )

        assertFailsWith<FinanceError.GuardBlockedInstallments> {
            useCase(
                InstallmentPlan(
                    id = "i1",
                    name = "Fogao",
                    totalInstallments = 10,
                    monthlyAmountCents = 200_00,
                    startYearMonth = YearMonth(2026, 2),
                    endYearMonth = YearMonth(2026, 11),
                    categoryId = "cat1",
                    active = true,
                )
            )
        }
    }

    @Test
    fun billingDayClampsToMonthEnd() {
        val due = dueDateForMonth(YearMonth(2026, 2), 31)
        assertEquals(LocalDate(2026, 2, 28), due)
    }

    @Test
    fun forecastShowsFreedCashAfterCommitmentEnds() = runTest {
        val budgetRepo = FakeBudgetRepository()
        val scheduleRepo = FakeScheduleRepository()

        val monthA = budgetRepo.createOrUpdateMonth(YearMonth(2026, 7), 4_000_00)
        budgetRepo.setAllocations(monthA.id, listOf(BudgetAllocation("a1", monthA.id, "cat1", 2_000_00)))
        scheduleRepo.commitments[YearMonth(2026, 7).toString()] = 1_700_00

        val monthB = budgetRepo.createOrUpdateMonth(YearMonth(2026, 8), 4_000_00)
        budgetRepo.setAllocations(monthB.id, listOf(BudgetAllocation("a2", monthB.id, "cat1", 2_000_00)))
        scheduleRepo.commitments[YearMonth(2026, 8).toString()] = 0

        val out = ComputeCashFlowForecastUseCase(budgetRepo, scheduleRepo, FakeExpenseRepository())(LocalDate(2026, 7, 1), 2)

        assertEquals(2_300_00, out[0].expectedFreeBalanceCents)
        assertEquals(4_000_00, out[1].expectedFreeBalanceCents)
    }
}

private class FakeBudgetRepository : BudgetRepository {
    private val months = mutableMapOf<String, BudgetMonth>()
    private val allocations = mutableMapOf<String, List<BudgetAllocation>>()

    override suspend fun createOrUpdateMonth(yearMonth: YearMonth, incomeCents: Long): BudgetMonth {
        val key = yearMonth.toString()
        val value = months[key]?.copy(totalIncomeCents = incomeCents)
            ?: BudgetMonth("b-$key", yearMonth, incomeCents, Instant.parse("2026-01-01T00:00:00Z"))
        months[key] = value
        return value
    }

    override suspend fun findByYearMonth(yearMonth: YearMonth): BudgetMonth? = months[yearMonth.toString()]

    override suspend fun setAllocations(budgetMonthId: String, allocations: List<BudgetAllocation>) {
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

    override suspend fun delete(id: String) { expenses.removeAll { it.id == id } }
    override suspend fun getById(id: String): Expense? = expenses.find { it.id == id }
    override suspend fun byMonth(yearMonth: YearMonth): List<Expense> = expenses.filter { YearMonth.fromDate(it.occurredAt) == yearMonth }
    override suspend fun search(yearMonth: YearMonth, categoryId: String?, query: String?): List<Expense> = byMonth(yearMonth)
    override suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long> = byMonth(yearMonth).groupBy { it.categoryId }.mapValues { it.value.sumOf(Expense::amountCents) }
    override suspend fun monthlyTotal(yearMonth: YearMonth): Long = byMonth(yearMonth).sumOf(Expense::amountCents)
    override suspend fun monthlyRecurringTotal(yearMonth: YearMonth): Long =
        byMonth(yearMonth).filter { it.recurrenceType == RecurrenceType.MONTHLY && it.scheduleItemId == null }.sumOf(Expense::amountCents)
}

private class FakeScheduleRepository : ScheduleRepository {
    val commitments = mutableMapOf<String, Long>()
    private val byId = mutableMapOf<String, PaymentScheduleItem>()

    override suspend fun upsert(item: PaymentScheduleItem) { byId[item.id] = item }
    override suspend fun byDateRange(from: LocalDate, to: LocalDate): List<PaymentScheduleItem> = byId.values.toList()
    override suspend fun findByRefAndDate(refId: String, dueDate: LocalDate): PaymentScheduleItem? = byId.values.find { it.refId == refId && it.dueDate == dueDate }
    override suspend fun findById(id: String): PaymentScheduleItem? = byId[id]
    override suspend fun markStatus(id: String, status: ScheduleStatus, paidAtIso: String?) {}
    override suspend fun commitmentsForMonth(yearMonth: YearMonth): Long = commitments[yearMonth.toString()] ?: 0
}

private class FakeInstallmentRepository : InstallmentRepository {
    override suspend fun upsert(plan: InstallmentPlan) {}
    override suspend fun list(): List<InstallmentPlan> = emptyList()
    override suspend fun listActive(): List<InstallmentPlan> = emptyList()
}

private class FakeSettingsRepository(private val guard: SafetyGuard?) : SettingsRepository {
    override suspend fun getSafetyGuard(): SafetyGuard? = guard
    override suspend fun upsertSafetyGuard(guard: SafetyGuard) {}
    override suspend fun getString(key: String): String? = null
    override suspend fun setString(key: String, value: String) {}
}
