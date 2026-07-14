package com.samluiz.gyst.domain.usecase

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateBudgetMonthUseCase(private val budgetRepository: BudgetRepository) {
    suspend operator fun invoke(
        yearMonth: YearMonth,
        incomeCents: Long,
    ): BudgetMonth {
        requireNonNegative(incomeCents)
        return budgetRepository.createOrUpdateMonth(yearMonth, incomeCents)
    }
}

class SetBudgetAllocationsUseCase(private val budgetRepository: BudgetRepository) {
    suspend operator fun invoke(
        budgetMonthId: String,
        allocations: List<BudgetAllocation>,
    ) {
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
        val monthRange = monthBounds(yearMonth)
        val monthScheduleItems =
            scheduleRepository
                .byDateRange(monthRange.first, monthRange.second)
                .filter { it.status == ScheduleStatus.DUE }

        val plannedTotal = allocations.sumOf { it.plannedCents }
        val spentTotal = expenseRepository.monthlyTotal(yearMonth)
        val commitments = scheduleRepository.commitmentsForMonth(yearMonth)
        val commitmentsByCategory = mutableMapOf<String, Long>()
        monthScheduleItems.forEach { item ->
            commitmentsByCategory[item.categoryId] =
                (commitmentsByCategory[item.categoryId] ?: 0L) + item.amountCents
        }

        val plannedByCategory = allocations.associate { it.categoryId to it.plannedCents }
        val categoryIds =
            buildSet {
                addAll(plannedByCategory.keys)
                addAll(spentMap.keys)
                addAll(commitmentsByCategory.keys)
            }
        val categories =
            categoryIds.map { categoryId ->
                val planned = plannedByCategory[categoryId] ?: 0L
                val spent = (spentMap[categoryId] ?: 0L) + (commitmentsByCategory[categoryId] ?: 0L)
                val remaining = planned - spent
                val percent = if (planned == 0L) 0.0 else (spent.toDouble() / planned.toDouble())
                CategorySummary(categoryId, planned, spent, remaining, percent)
            }

        return MonthlySummary(
            yearMonth = yearMonth,
            totalIncomeCents = budget?.totalIncomeCents ?: 0L,
            plannedTotalCents = plannedTotal,
            spentTotalCents = spentTotal,
            remainingTotalCents = (budget?.totalIncomeCents ?: 0L) - spentTotal - commitments,
            commitmentsCents = commitments,
            perCategory = categories,
        )
    }
}

class ComputeCashFlowForecastUseCase(
    private val budgetRepository: BudgetRepository,
    private val scheduleRepository: ScheduleRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
) {
    suspend operator fun invoke(
        fromDate: LocalDate,
        monthsAhead: Int,
    ): List<ForecastMonth> {
        val start = YearMonth.fromDate(fromDate)
        var carryIncome = budgetRepository.findByYearMonth(start)?.totalIncomeCents ?: 0L
        var carryPlanned =
            budgetRepository.findByYearMonth(start)
                ?.let { budgetRepository.allocationsByBudgetMonth(it.id).sumOf { alloc -> alloc.plannedCents } }
                ?: 0L
        return (0 until monthsAhead).map { offset ->
            val ym = start.plusMonths(offset)
            val budget = budgetRepository.findByYearMonth(ym)
            val allocations = budget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty()
            val planned =
                when {
                    budget == null -> carryPlanned
                    allocations.isEmpty() -> carryPlanned
                    else -> allocations.sumOf { it.plannedCents }
                }
            val commitments = scheduleRepository.commitmentsForMonth(ym)
            val recurring = recurringExpenseRepository.activeForMonth(ym).sumOf { it.amountCents }
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
    suspend operator fun invoke(
        subscription: Subscription,
        monthsAhead: Int = 6,
        scheduleStartYearMonth: YearMonth,
        persistSubscription: Boolean = true,
    ) {
        requireNonNegative(subscription.amountCents)
        if (persistSubscription) {
            subscriptionRepository.upsert(subscription)
        }

        if (!subscription.active) return
        val nowYm =
            if (scheduleStartYearMonth < subscription.startYearMonth) {
                subscription.startYearMonth
            } else {
                scheduleStartYearMonth
            }
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
                        categoryId = subscription.categoryId,
                        dueDate = due,
                        amountCents = subscription.amountCents,
                        status = ScheduleStatus.DUE,
                    ),
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
        requireNonNegative(plan.totalAmountCents)
        require(plan.totalInstallments > 0)

        installmentRepository.upsert(plan)

        repeat(plan.totalInstallments) { index ->
            val month = plan.startYearMonth.plusMonths(index)
            val due = LocalDate(month.year, month.month, 1)
            val existing = scheduleRepository.findByRefAndDate(plan.id, due)
            if (existing == null) {
                scheduleRepository.upsert(
                    PaymentScheduleItem(
                        id = id("sch-ins"),
                        kind = ScheduleKind.INSTALLMENT,
                        refId = plan.id,
                        categoryId = plan.categoryId,
                        dueDate = due,
                        amountCents = installmentAmountAt(plan.totalAmountCents, plan.totalInstallments, index),
                        status = ScheduleStatus.DUE,
                    ),
                )
            }
        }
    }
}

fun installmentAmountAt(
    totalAmountCents: Long,
    totalInstallments: Int,
    index: Int,
): Long {
    require(totalAmountCents >= 0L)
    require(totalInstallments > 0)
    require(index in 0 until totalInstallments)
    val base = totalAmountCents / totalInstallments
    val remainder = totalAmountCents % totalInstallments
    return base + if (index.toLong() < remainder) 1L else 0L
}

class MarkSchedulePaidUseCase(
    private val commitmentPaymentRepository: CommitmentPaymentRepository,
) {
    suspend operator fun invoke(
        scheduleItemId: String,
        categoryId: String,
        paymentMethod: PaymentMethod = PaymentMethod.TRANSFER,
    ) {
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
    private val recurringExpenseRepository: RecurringExpenseRepository,
) {
    suspend operator fun invoke(targetMonth: YearMonth) {
        val previousMonth = targetMonth.plusMonths(-1)
        val previousBudget = budgetRepository.findByYearMonth(previousMonth)
        val targetBudget =
            budgetRepository.findByYearMonth(targetMonth)
                ?: budgetRepository.createOrUpdateMonth(targetMonth, previousBudget?.totalIncomeCents ?: 0L)

        if (previousBudget != null) {
            val targetAllocations = budgetRepository.allocationsByBudgetMonth(targetBudget.id)
            if (targetAllocations.isEmpty()) {
                val copied =
                    budgetRepository.allocationsByBudgetMonth(previousBudget.id).map { alloc ->
                        alloc.copy(id = id("alloc"), budgetMonthId = targetBudget.id)
                    }
                if (copied.isNotEmpty()) {
                    budgetRepository.setAllocations(targetBudget.id, copied)
                }
            }
        }

        val existingSeriesIds = expenseRepository.byMonth(targetMonth).mapNotNullTo(mutableSetOf()) { it.recurrenceSeriesId }
        recurringExpenseRepository.activeForMonth(targetMonth).forEach { series ->
            if (series.id !in existingSeriesIds) {
                val newDay = series.dayOfMonth.coerceAtMost(monthBounds(targetMonth).second.day)
                expenseRepository.upsert(
                    Expense(
                        id = id("exp-rec"),
                        occurredAt = LocalDate(targetMonth.year, targetMonth.month, newDay),
                        amountCents = series.amountCents,
                        categoryId = series.categoryId,
                        note = series.note,
                        merchant = series.merchant,
                        paymentMethod = series.paymentMethod,
                        recurrenceType = RecurrenceType.MONTHLY,
                        createdAt = nowInstantUtc(),
                        recurrenceSeriesId = series.id,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
fun id(prefix: String): String = "$prefix-${Uuid.random()}"
