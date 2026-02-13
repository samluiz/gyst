package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.*
import kotlinx.datetime.LocalDate

interface CategoryRepository {
    suspend fun list(): List<Category>
    suspend fun upsert(category: Category)
    suspend fun delete(id: String)
}

interface BudgetRepository {
    suspend fun createOrUpdateMonth(yearMonth: YearMonth, incomeCents: Long): BudgetMonth
    suspend fun findByYearMonth(yearMonth: YearMonth): BudgetMonth?
    suspend fun setAllocations(budgetMonthId: String, allocations: List<BudgetAllocation>)
    suspend fun allocationsByBudgetMonth(budgetMonthId: String): List<BudgetAllocation>
    suspend fun listMonths(): List<YearMonth>
}

interface ExpenseRepository {
    suspend fun upsert(expense: Expense)
    suspend fun delete(id: String)
    suspend fun getById(id: String): Expense?
    suspend fun byMonth(yearMonth: YearMonth): List<Expense>
    suspend fun search(yearMonth: YearMonth, categoryId: String?, query: String?): List<Expense>
    suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long>
    suspend fun monthlyTotal(yearMonth: YearMonth): Long
    suspend fun monthlyRecurringTotal(yearMonth: YearMonth): Long
}

interface SubscriptionRepository {
    suspend fun upsert(subscription: Subscription)
    suspend fun list(): List<Subscription>
    suspend fun listActive(): List<Subscription>
}

interface InstallmentRepository {
    suspend fun upsert(plan: InstallmentPlan)
    suspend fun list(): List<InstallmentPlan>
    suspend fun listActive(): List<InstallmentPlan>
}

interface ScheduleRepository {
    suspend fun upsert(item: PaymentScheduleItem)
    suspend fun byDateRange(from: LocalDate, to: LocalDate): List<PaymentScheduleItem>
    suspend fun findByRefAndDate(refId: String, dueDate: LocalDate): PaymentScheduleItem?
    suspend fun findById(id: String): PaymentScheduleItem?
    suspend fun markStatus(id: String, status: ScheduleStatus, paidAtIso: String?)
    suspend fun commitmentsForMonth(yearMonth: YearMonth): Long
}

interface SettingsRepository {
    suspend fun getSafetyGuard(): SafetyGuard?
    suspend fun upsertSafetyGuard(guard: SafetyGuard)
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
}

interface CommitmentPaymentRepository {
    suspend fun markSchedulePaidAndCreateExpense(
        scheduleItemId: String,
        categoryId: String,
        paymentMethod: PaymentMethod,
        note: String? = null,
    )
}
