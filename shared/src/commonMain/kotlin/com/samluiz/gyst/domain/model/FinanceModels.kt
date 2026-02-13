package com.samluiz.gyst.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class CategoryType { ESSENTIAL, FIXED, VARIABLE, GOAL, RESERVE }

data class Category(
    val id: String,
    val name: String,
    val type: CategoryType,
    val color: String? = null,
    val icon: String? = null,
)

data class BudgetMonth(
    val id: String,
    val yearMonth: YearMonth,
    val totalIncomeCents: Long,
    val createdAt: Instant,
)

data class BudgetAllocation(
    val id: String,
    val budgetMonthId: String,
    val categoryId: String,
    val plannedCents: Long,
)

enum class PaymentMethod { PIX, DEBIT, CASH, TRANSFER }
enum class RecurrenceType { ONE_TIME, MONTHLY }

data class Expense(
    val id: String,
    val occurredAt: LocalDate,
    val amountCents: Long,
    val categoryId: String,
    val note: String? = null,
    val merchant: String? = null,
    val paymentMethod: PaymentMethod,
    val recurrenceType: RecurrenceType = RecurrenceType.ONE_TIME,
    val createdAt: Instant,
    val scheduleItemId: String? = null,
)

enum class RenewalPolicy { MONTHLY }

data class Subscription(
    val id: String,
    val name: String,
    val amountCents: Long,
    val billingDay: Int,
    val categoryId: String,
    val active: Boolean,
    val renewalPolicy: RenewalPolicy,
    val nextDueDate: LocalDate,
)

data class InstallmentPlan(
    val id: String,
    val name: String,
    val totalInstallments: Int,
    val monthlyAmountCents: Long,
    val startYearMonth: YearMonth,
    val endYearMonth: YearMonth,
    val categoryId: String,
    val active: Boolean,
)

enum class ScheduleKind { SUBSCRIPTION, INSTALLMENT }

enum class ScheduleStatus { DUE, PAID, SKIPPED }

data class PaymentScheduleItem(
    val id: String,
    val kind: ScheduleKind,
    val refId: String,
    val dueDate: LocalDate,
    val amountCents: Long,
    val status: ScheduleStatus,
    val paidAt: Instant? = null,
)

data class SafetyGuard(
    val id: String,
    val noNewInstallments: Boolean = true,
    val discretionaryCapCents: Long? = null,
    val alert70Enabled: Boolean = true,
    val alert90Enabled: Boolean = true,
    val alert100Enabled: Boolean = true,
)

data class CategorySummary(
    val categoryId: String,
    val plannedCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
    val percentUsed: Double,
)

data class MonthlySummary(
    val yearMonth: YearMonth,
    val totalIncomeCents: Long,
    val plannedTotalCents: Long,
    val spentTotalCents: Long,
    val remainingTotalCents: Long,
    val commitmentsCents: Long,
    val perCategory: List<CategorySummary>,
)

data class ForecastMonth(
    val yearMonth: YearMonth,
    val incomeCents: Long,
    val plannedCents: Long,
    val commitmentsCents: Long,
    val recurringCents: Long,
    val expectedSpendCents: Long,
    val expectedFreeBalanceCents: Long,
)

data class MonthComparison(
    val previousMonth: YearMonth,
    val spentDeltaCents: Long,
    val commitmentsDeltaCents: Long,
)
