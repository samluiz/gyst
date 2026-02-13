package com.samluiz.gyst.domain.usecase

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateBudgetMonthUseCase(private val budgetRepository: BudgetRepository) {
    suspend operator fun invoke(yearMonth: YearMonth, incomeCents: Long): BudgetMonth {
        requireNonNegative(incomeCents)
        return budgetRepository.createOrUpdateMonth(yearMonth, incomeCents)
    }
}

class SetBudgetAllocationsUseCase(private val budgetRepository: BudgetRepository) {
    suspend operator fun invoke(budgetMonthId: String, allocations: List<BudgetAllocation>) {
        allocations.forEach { requireNonNegative(it.plannedCents) }
        budgetRepository.setAllocations(budgetMonthId, allocations)
    }
}

class AddOrUpdateExpenseUseCase(private val expenseRepository: ExpenseRepository) {
    suspend operator fun invoke(expense: Expense) {
        requireNonNegative(expense.amountCents)
        expenseRepository.upsert(expense)
    }
}

class DeleteExpenseUseCase(private val expenseRepository: ExpenseRepository) {
    suspend operator fun invoke(expenseId: String) {
        expenseRepository.delete(expenseId)
    }
}

class ComputeMonthlySummaryUseCase(
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val scheduleRepository: ScheduleRepository,
) {
    suspend operator fun invoke(yearMonth: YearMonth): MonthlySummary {
        val budget = budgetRepository.findByYearMonth(yearMonth)
        val allocations = budget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty()
        val spentMap = expenseRepository.monthlySpentByCategory(yearMonth)

        val plannedTotal = allocations.sumOf { it.plannedCents }
        val spentTotal = expenseRepository.monthlyTotal(yearMonth)
        val commitments = scheduleRepository.commitmentsForMonth(yearMonth)

        val categories = allocations.map {
            val spent = spentMap[it.categoryId] ?: 0L
            val remaining = it.plannedCents - spent
            val percent = if (it.plannedCents == 0L) 0.0 else (spent.toDouble() / it.plannedCents.toDouble())
            CategorySummary(it.categoryId, it.plannedCents, spent, remaining, percent)
        }

        return MonthlySummary(
            yearMonth = yearMonth,
            totalIncomeCents = budget?.totalIncomeCents ?: 0L,
            plannedTotalCents = plannedTotal,
            spentTotalCents = spentTotal,
            remainingTotalCents = (budget?.totalIncomeCents ?: 0L) - spentTotal,
            commitmentsCents = commitments,
            perCategory = categories,
        )
    }
}

class ComputeCashFlowForecastUseCase(
    private val budgetRepository: BudgetRepository,
    private val scheduleRepository: ScheduleRepository,
    private val expenseRepository: ExpenseRepository,
) {
    suspend operator fun invoke(fromDate: LocalDate, monthsAhead: Int): List<ForecastMonth> {
        val start = YearMonth.fromDate(fromDate)
        val baselineRecurring = expenseRepository.monthlyRecurringTotal(start)
        var carryIncome = budgetRepository.findByYearMonth(start)?.totalIncomeCents ?: 0L
        var carryPlanned = budgetRepository.findByYearMonth(start)
            ?.let { budgetRepository.allocationsByBudgetMonth(it.id).sumOf { alloc -> alloc.plannedCents } }
            ?: 0L
        return (0 until monthsAhead).map { offset ->
            val ym = start.plusMonths(offset)
            val budget = budgetRepository.findByYearMonth(ym)
            val allocations = budget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty()
            val planned = when {
                budget == null -> carryPlanned
                allocations.isEmpty() -> carryPlanned
                else -> allocations.sumOf { it.plannedCents }
            }
            val commitments = scheduleRepository.commitmentsForMonth(ym)
            val recurring = expenseRepository.monthlyRecurringTotal(ym).takeIf { it > 0L } ?: baselineRecurring
            val income = budget?.totalIncomeCents ?: carryIncome
            if (budget != null) {
                carryIncome = income
                if (allocations.isNotEmpty()) carryPlanned = allocations.sumOf { it.plannedCents }
            }
            val expectedSpend = commitments + recurring
            ForecastMonth(
                yearMonth = ym,
                incomeCents = income,
                plannedCents = planned,
                commitmentsCents = commitments,
                recurringCents = recurring,
                expectedSpendCents = expectedSpend,
                expectedFreeBalanceCents = income - expectedSpend,
            )
        }
    }
}

class UpsertSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository,
    private val scheduleRepository: ScheduleRepository,
) {
    suspend operator fun invoke(subscription: Subscription, monthsAhead: Int = 6) {
        requireNonNegative(subscription.amountCents)
        subscriptionRepository.upsert(subscription)

        if (!subscription.active) return
        val nowYm = YearMonth.fromDate(subscription.nextDueDate)
        repeat(monthsAhead) { offset ->
            val ym = nowYm.plusMonths(offset)
            val due = dueDateForMonth(ym, subscription.billingDay)
            val existing = scheduleRepository.findByRefAndDate(subscription.id, due)
            if (existing == null) {
                scheduleRepository.upsert(
                    PaymentScheduleItem(
                        id = id("sch-sub"),
                        kind = ScheduleKind.SUBSCRIPTION,
                        refId = subscription.id,
                        dueDate = due,
                        amountCents = subscription.amountCents,
                        status = ScheduleStatus.DUE,
                    )
                )
            }
        }
    }
}

class CreateInstallmentPlanUseCase(
    private val installmentRepository: InstallmentRepository,
    private val scheduleRepository: ScheduleRepository,
) {
    suspend operator fun invoke(plan: InstallmentPlan) {
        requireNonNegative(plan.monthlyAmountCents)

        installmentRepository.upsert(plan)

        var month = plan.startYearMonth
        while (month <= plan.endYearMonth) {
            val due = LocalDate(month.year, month.month, 1)
            val existing = scheduleRepository.findByRefAndDate(plan.id, due)
            if (existing == null) {
                scheduleRepository.upsert(
                    PaymentScheduleItem(
                        id = id("sch-ins"),
                        kind = ScheduleKind.INSTALLMENT,
                        refId = plan.id,
                        dueDate = due,
                        amountCents = plan.monthlyAmountCents,
                        status = ScheduleStatus.DUE,
                    )
                )
            }
            month = month.plusMonths(1)
        }
    }
}

class MarkSchedulePaidUseCase(
    private val commitmentPaymentRepository: CommitmentPaymentRepository,
) {
    suspend operator fun invoke(scheduleItemId: String, categoryId: String, paymentMethod: PaymentMethod = PaymentMethod.TRANSFER) {
        commitmentPaymentRepository.markSchedulePaidAndCreateExpense(
            scheduleItemId = scheduleItemId,
            categoryId = categoryId,
            paymentMethod = paymentMethod,
            note = "Pagamento agendado",
        )
    }
}

class HandleMonthRolloverUseCase(
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(targetMonth: YearMonth) {
        val previousMonth = targetMonth.plusMonths(-1)
        val previousBudget = budgetRepository.findByYearMonth(previousMonth)
        val targetBudget = budgetRepository.findByYearMonth(targetMonth)
            ?: budgetRepository.createOrUpdateMonth(targetMonth, previousBudget?.totalIncomeCents ?: 0L)

        if (previousBudget != null) {
            val targetAllocations = budgetRepository.allocationsByBudgetMonth(targetBudget.id)
            if (targetAllocations.isEmpty()) {
                val copied = budgetRepository.allocationsByBudgetMonth(previousBudget.id).map { alloc ->
                    alloc.copy(id = id("alloc"), budgetMonthId = targetBudget.id)
                }
                if (copied.isNotEmpty()) {
                    budgetRepository.setAllocations(targetBudget.id, copied)
                }
            }
        }

        val previousRecurring = expenseRepository.byMonth(previousMonth)
            .filter { it.recurrenceType == RecurrenceType.MONTHLY && it.scheduleItemId == null }

        val existingInTarget = expenseRepository.byMonth(targetMonth)
        previousRecurring.forEach { source ->
            val alreadyCopied = existingInTarget.any {
                it.categoryId == source.categoryId &&
                    it.amountCents == source.amountCents &&
                    it.note == source.note &&
                    it.merchant == source.merchant &&
                    it.recurrenceType == RecurrenceType.MONTHLY
            }
            if (!alreadyCopied) {
                val newDay = source.occurredAt.day.coerceAtMost(monthBounds(targetMonth).second.day)
                expenseRepository.upsert(
                    source.copy(
                        id = id("exp-rec"),
                        occurredAt = LocalDate(targetMonth.year, targetMonth.month, newDay),
                        createdAt = nowInstantUtc(),
                    )
                )
            }
        }

        settingsRepository.setString("rollover_done_${targetMonth}", "true")
    }
}

@OptIn(ExperimentalUuidApi::class)
fun id(prefix: String): String = "$prefix-${Uuid.random()}"
