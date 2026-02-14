package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.domain.usecase.monthBounds
import com.samluiz.gyst.domain.usecase.nowInstantUtc
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

interface SqlDriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseHolder(
    initialDriver: SqlDriver,
    private val driverFactory: SqlDriverFactory,
) {
    private val reloadMutex = Mutex()
    private var activeDriver: SqlDriver = initialDriver
    @Volatile
    private var activeDb: GystDatabase = GystDatabase(initialDriver)

    val db: GystDatabase
        get() = activeDb

    suspend fun reloadDatabase() {
        reloadMutex.withLock {
            val newDriver = driverFactory.createDriver()
            val oldDriver = activeDriver
            activeDriver = newDriver
            activeDb = GystDatabase(newDriver)
            runCatching { oldDriver.close() }
        }
    }
}

class SqlCategoryRepository(private val holder: DatabaseHolder) : CategoryRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun list(): List<Category> = q.selectAllCategories().executeAsList().map {
        Category(
            id = it.id,
            name = it.name,
            type = CategoryType.valueOf(it.type),
            color = it.color,
            icon = it.icon,
        )
    }

    override suspend fun upsert(category: Category) {
        q.upsertCategory(category.id, category.name, category.type.name, category.color, category.icon)
    }

    override suspend fun delete(id: String) {
        q.deleteCategory(id)
    }
}

class SqlBudgetRepository(private val holder: DatabaseHolder) : BudgetRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun createOrUpdateMonth(yearMonth: YearMonth, incomeCents: Long): BudgetMonth {
        val existing = findByYearMonth(yearMonth)
        val budget = existing?.copy(totalIncomeCents = incomeCents)
            ?: BudgetMonth(
                id = id("bud"),
                yearMonth = yearMonth,
                totalIncomeCents = incomeCents,
                createdAt = nowInstantUtc(),
            )
        q.upsertBudgetMonth(budget.id, budget.yearMonth.toString(), budget.totalIncomeCents, budget.createdAt.toString())
        return budget
    }

    override suspend fun findByYearMonth(yearMonth: YearMonth): BudgetMonth? {
        return q.findBudgetMonthByYearMonth(yearMonth.toString()).executeAsOneOrNull()?.let {
            BudgetMonth(
                id = it.id,
                yearMonth = YearMonth.parse(it.year_month),
                totalIncomeCents = it.total_income_cents,
                createdAt = Instant.parse(it.created_at),
            )
        }
    }

    override suspend fun setAllocations(budgetMonthId: String, allocations: List<BudgetAllocation>) {
        q.deleteBudgetAllocationsByMonth(budgetMonthId)
        allocations.forEach {
            q.upsertBudgetAllocation(
                id = it.id,
                budget_month_id = it.budgetMonthId,
                category_id = it.categoryId,
                planned_cents = it.plannedCents,
            )
        }
    }

    override suspend fun allocationsByBudgetMonth(budgetMonthId: String): List<BudgetAllocation> {
        return q.selectBudgetAllocationsByMonth(budgetMonthId).executeAsList().map {
            BudgetAllocation(
                id = it.id,
                budgetMonthId = it.budget_month_id,
                categoryId = it.category_id,
                plannedCents = it.planned_cents,
            )
        }
    }

    override suspend fun listMonths(): List<YearMonth> {
        return q.selectBudgetMonths().executeAsList().map { YearMonth.parse(it) }
    }
}

class SqlExpenseRepository(private val holder: DatabaseHolder) : ExpenseRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(expense: Expense) {
        q.upsertExpense(
            id = expense.id,
            occurred_at = expense.occurredAt.toString(),
            amount_cents = expense.amountCents,
            category_id = expense.categoryId,
            note = expense.note,
            merchant = expense.merchant,
            payment_method = expense.paymentMethod.name,
            recurrence_type = expense.recurrenceType.name,
            created_at = expense.createdAt.toString(),
            schedule_item_id = expense.scheduleItemId,
        )
    }

    override suspend fun delete(id: String) {
        q.deleteExpense(id)
    }

    override suspend fun getById(id: String): Expense? {
        return q.selectExpenseById(id).executeAsOneOrNull()?.let {
            Expense(
                id = it.id,
                occurredAt = LocalDate.parse(it.occurred_at),
                amountCents = it.amount_cents,
                categoryId = it.category_id,
                note = it.note,
                merchant = it.merchant,
                paymentMethod = PaymentMethod.valueOf(it.payment_method),
                recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                createdAt = Instant.parse(it.created_at),
                scheduleItemId = it.schedule_item_id,
            )
        }
    }

    override suspend fun byMonth(yearMonth: YearMonth): List<Expense> {
        val (from, to) = monthBounds(yearMonth)
        return q.selectExpensesByMonth(fromDate = from.toString(), toDate = to.toString()).executeAsList().map {
            Expense(
                id = it.id,
                occurredAt = LocalDate.parse(it.occurred_at),
                amountCents = it.amount_cents,
                categoryId = it.category_id,
                note = it.note,
                merchant = it.merchant,
                paymentMethod = PaymentMethod.valueOf(it.payment_method),
                recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                createdAt = Instant.parse(it.created_at),
                scheduleItemId = it.schedule_item_id,
            )
        }
    }

    override suspend fun byMonthPaged(yearMonth: YearMonth, limit: Long, offset: Long): List<Expense> {
        val (from, to) = monthBounds(yearMonth)
        return q.selectExpensesByMonthPaged(
            fromDate = from.toString(),
            toDate = to.toString(),
            limit = limit,
            offset = offset,
        ).executeAsList().map {
            Expense(
                id = it.id,
                occurredAt = LocalDate.parse(it.occurred_at),
                amountCents = it.amount_cents,
                categoryId = it.category_id,
                note = it.note,
                merchant = it.merchant,
                paymentMethod = PaymentMethod.valueOf(it.payment_method),
                recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                createdAt = Instant.parse(it.created_at),
                scheduleItemId = it.schedule_item_id,
            )
        }
    }

    override suspend fun search(yearMonth: YearMonth, categoryId: String?, query: String?): List<Expense> {
        val (from, to) = monthBounds(yearMonth)
        return q.searchExpenses(
            fromDate = from.toString(),
            toDate = to.toString(),
            categoryId = categoryId,
            query = query,
        ).executeAsList().map {
            Expense(
                id = it.id,
                occurredAt = LocalDate.parse(it.occurred_at),
                amountCents = it.amount_cents,
                categoryId = it.category_id,
                note = it.note,
                merchant = it.merchant,
                paymentMethod = PaymentMethod.valueOf(it.payment_method),
                recurrenceType = RecurrenceType.valueOf(it.recurrence_type),
                createdAt = Instant.parse(it.created_at),
                scheduleItemId = it.schedule_item_id,
            )
        }
    }

    override suspend fun deleteFutureRecurringByTemplate(fromDateExclusive: LocalDate, template: Expense) {
        q.deleteFutureRecurringByTemplate(
            fromDate = fromDateExclusive.toString(),
            categoryId = template.categoryId,
            amountCents = template.amountCents,
            paymentMethod = template.paymentMethod.name,
            note = template.note,
            merchant = template.merchant,
        )
    }

    override suspend fun updateFutureRecurringByTemplate(fromDateExclusive: LocalDate, oldTemplate: Expense, newTemplate: Expense) {
        q.updateFutureRecurringByTemplate(
            fromDate = fromDateExclusive.toString(),
            oldCategoryId = oldTemplate.categoryId,
            oldAmountCents = oldTemplate.amountCents,
            oldPaymentMethod = oldTemplate.paymentMethod.name,
            oldNote = oldTemplate.note,
            oldMerchant = oldTemplate.merchant,
            newCategoryId = newTemplate.categoryId,
            newAmountCents = newTemplate.amountCents,
            newPaymentMethod = newTemplate.paymentMethod.name,
            newNote = newTemplate.note,
            newMerchant = newTemplate.merchant,
        )
    }

    override suspend fun monthlySpentByCategory(yearMonth: YearMonth): Map<String, Long> {
        val (from, to) = monthBounds(yearMonth)
        return q.sumExpensesByMonthAndCategory(fromDate = from.toString(), toDate = to.toString())
            .executeAsList()
            .associate { it.category_id to it.total }
    }

    override suspend fun monthlyTotal(yearMonth: YearMonth): Long {
        val (from, to) = monthBounds(yearMonth)
        return q.sumExpensesByMonth(fromDate = from.toString(), toDate = to.toString()).executeAsOne()
    }

    override suspend fun monthlyRecurringTotal(yearMonth: YearMonth): Long {
        val (from, to) = monthBounds(yearMonth)
        return q.sumRecurringExpensesByMonth(fromDate = from.toString(), toDate = to.toString()).executeAsOne()
    }
}

class SqlSubscriptionRepository(private val holder: DatabaseHolder) : SubscriptionRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(subscription: Subscription) {
        q.upsertSubscription(
            id = subscription.id,
            name = subscription.name,
            amount_cents = subscription.amountCents,
            billing_day = subscription.billingDay.toLong(),
            category_id = subscription.categoryId,
            active = if (subscription.active) 1 else 0,
            renewal_policy = subscription.renewalPolicy.name,
            next_due_date = subscription.nextDueDate.toString(),
        )
    }

    override suspend fun list(): List<Subscription> = q.selectSubscriptions().executeAsList().map {
        Subscription(
            id = it.id,
            name = it.name,
            amountCents = it.amount_cents,
            billingDay = it.billing_day.toInt(),
            categoryId = it.category_id,
            active = it.active == 1L,
            renewalPolicy = RenewalPolicy.valueOf(it.renewal_policy),
            nextDueDate = LocalDate.parse(it.next_due_date),
        )
    }

    override suspend fun listActive(): List<Subscription> = q.selectActiveSubscriptions().executeAsList().map {
        Subscription(
            id = it.id,
            name = it.name,
            amountCents = it.amount_cents,
            billingDay = it.billing_day.toInt(),
            categoryId = it.category_id,
            active = it.active == 1L,
            renewalPolicy = RenewalPolicy.valueOf(it.renewal_policy),
            nextDueDate = LocalDate.parse(it.next_due_date),
        )
    }

    override suspend fun delete(id: String) {
        q.deleteSubscription(id)
    }
}

class SqlInstallmentRepository(private val holder: DatabaseHolder) : InstallmentRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(plan: InstallmentPlan) {
        q.upsertInstallmentPlan(
            id = plan.id,
            name = plan.name,
            total_installments = plan.totalInstallments.toLong(),
            monthly_amount_cents = plan.monthlyAmountCents,
            start_year_month = plan.startYearMonth.toString(),
            end_year_month = plan.endYearMonth.toString(),
            category_id = plan.categoryId,
            active = if (plan.active) 1 else 0,
        )
    }

    override suspend fun list(): List<InstallmentPlan> = q.selectInstallmentPlans().executeAsList().map {
        InstallmentPlan(
            id = it.id,
            name = it.name,
            totalInstallments = it.total_installments.toInt(),
            monthlyAmountCents = it.monthly_amount_cents,
            startYearMonth = YearMonth.parse(it.start_year_month),
            endYearMonth = YearMonth.parse(it.end_year_month),
            categoryId = it.category_id,
            active = it.active == 1L,
        )
    }

    override suspend fun listActive(): List<InstallmentPlan> = q.selectActiveInstallments().executeAsList().map {
        InstallmentPlan(
            id = it.id,
            name = it.name,
            totalInstallments = it.total_installments.toInt(),
            monthlyAmountCents = it.monthly_amount_cents,
            startYearMonth = YearMonth.parse(it.start_year_month),
            endYearMonth = YearMonth.parse(it.end_year_month),
            categoryId = it.category_id,
            active = it.active == 1L,
        )
    }

    override suspend fun delete(id: String) {
        q.deleteInstallmentPlan(id)
    }
}

class SqlScheduleRepository(private val holder: DatabaseHolder) : ScheduleRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun upsert(item: PaymentScheduleItem) {
        q.upsertScheduleItem(
            id = item.id,
            kind = item.kind.name,
            ref_id = item.refId,
            due_date = item.dueDate.toString(),
            amount_cents = item.amountCents,
            status = item.status.name,
            paid_at = item.paidAt?.toString(),
        )
    }

    override suspend fun byDateRange(from: LocalDate, to: LocalDate): List<PaymentScheduleItem> {
        return q.selectScheduleByDateRange(fromDate = from.toString(), toDate = to.toString()).executeAsList().map {
            PaymentScheduleItem(
                id = it.id,
                kind = ScheduleKind.valueOf(it.kind),
                refId = it.ref_id,
                dueDate = LocalDate.parse(it.due_date),
                amountCents = it.amount_cents,
                status = ScheduleStatus.valueOf(it.status),
                paidAt = it.paid_at?.let(Instant::parse),
            )
        }
    }

    override suspend fun findByRefAndDate(refId: String, dueDate: LocalDate): PaymentScheduleItem? {
        return q.selectDueScheduleItemsByRefAndDate(refId = refId, dueDate = dueDate.toString()).executeAsOneOrNull()?.let {
            PaymentScheduleItem(
                id = it.id,
                kind = ScheduleKind.valueOf(it.kind),
                refId = it.ref_id,
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
                dueDate = LocalDate.parse(it.due_date),
                amountCents = it.amount_cents,
                status = ScheduleStatus.valueOf(it.status),
                paidAt = it.paid_at?.let(Instant::parse),
            )
        }
    }

    override suspend fun deleteByRefAndKind(refId: String, kind: ScheduleKind) {
        q.deleteScheduleItemsByRefAndKind(refId, kind.name)
    }

    override suspend fun deleteByRefAndKindFromDate(refId: String, kind: ScheduleKind, fromDateInclusive: LocalDate) {
        q.deleteScheduleItemsByRefAndKindFromDate(
            refId = refId,
            kind = kind.name,
            fromDate = fromDateInclusive.toString(),
        )
    }

    override suspend fun markStatus(id: String, status: ScheduleStatus, paidAtIso: String?) {
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

class SqlSettingsRepository(private val holder: DatabaseHolder) : SettingsRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun getSafetyGuard(): SafetyGuard? {
        return q.getSafetyGuard().executeAsOneOrNull()?.let {
            SafetyGuard(
                id = it.id,
                discretionaryCapCents = it.discretionary_cap_cents,
                alert70Enabled = it.alert70_enabled == 1L,
                alert90Enabled = it.alert90_enabled == 1L,
                alert100Enabled = it.alert100_enabled == 1L,
            )
        }
    }

    override suspend fun upsertSafetyGuard(guard: SafetyGuard) {
        q.upsertSafetyGuard(
            id = guard.id,
            no_new_installments = 0,
            discretionary_cap_cents = guard.discretionaryCapCents,
            alert70_enabled = if (guard.alert70Enabled) 1 else 0,
            alert90_enabled = if (guard.alert90Enabled) 1 else 0,
            alert100_enabled = if (guard.alert100Enabled) 1 else 0,
        )
    }

    override suspend fun getString(key: String): String? = q.getAppSetting(key).executeAsOneOrNull()

    override suspend fun setString(key: String, value: String) {
        q.upsertAppSetting(key, value)
    }
}

class SqlCommitmentPaymentRepository(private val holder: DatabaseHolder) : CommitmentPaymentRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun markSchedulePaidAndCreateExpense(
        scheduleItemId: String,
        categoryId: String,
        paymentMethod: PaymentMethod,
        note: String?,
    ) {
        holder.db.transaction {
            val item = q.selectScheduleItemById(scheduleItemId).executeAsOneOrNull() ?: return@transaction
            val paidAt = Clock.System.now().toString()
            q.updateScheduleStatus(ScheduleStatus.PAID.name, paidAt, scheduleItemId)
            q.upsertExpense(
                id = id("exp-sch"),
                occurred_at = item.due_date,
                amount_cents = item.amount_cents,
                category_id = categoryId,
                note = note,
                merchant = null,
                payment_method = paymentMethod.name,
                recurrence_type = RecurrenceType.ONE_TIME.name,
                created_at = paidAt,
                schedule_item_id = scheduleItemId,
            )
        }
    }
}

class SqlLocalDataMaintenanceRepository(private val holder: DatabaseHolder) : LocalDataMaintenanceRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun resetLocalData() {
        holder.db.transaction {
            q.deleteAllScheduleItems()
            q.deleteAllExpenses()
            q.deleteAllSubscriptions()
            q.deleteAllInstallments()
            q.deleteAllBudgetAllocations()
            q.deleteAllBudgetMonths()
            q.deleteAllCategories()
            q.deleteAllSafetyGuards()
            q.deleteAllSettings()
        }
    }
}
