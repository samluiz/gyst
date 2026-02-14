package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.logging.AppLogger
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.usecase.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val EXPENSES_PAGE_SIZE = 50L
private const val SLOW_QUERY_MS = 120L
private const val RETRO_BUDGET_FILL_MONTHS = 24
private const val EXPLICIT_BUDGET_KEY_PREFIX = "budget.explicit."
private const val PLANNING_USE_POST_SAVINGS_KEY = "planning.usePostSavingsBudget"
private const val PLANNING_MONTHLY_CONTRIBUTION_KEY = "planning.monthlyContributionCents"
private const val PLANNING_GOAL_AMOUNT_KEY = "planning.goalAmountCents"
private const val PLANNING_DESIRED_MARGIN_KEY = "planning.desiredMarginCents"

class MainStore(
    private val seedDataInitializer: SeedDataInitializer,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val installmentRepository: InstallmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val localDataMaintenanceRepository: LocalDataMaintenanceRepository,
    private val googleAuthSyncService: GoogleAuthSyncService,
    private val computeMonthlySummaryUseCase: ComputeMonthlySummaryUseCase,
    private val computeCashFlowForecastUseCase: ComputeCashFlowForecastUseCase,
    private val handleMonthRolloverUseCase: HandleMonthRolloverUseCase,
) {
    private companion object {
        const val TAG = "MainStore"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    init {
        scope.launch {
            googleAuthSyncService.state.collectLatest { google ->
                _state.value = _state.value.copy(googleSync = google)
            }
        }
    }

    fun bootstrap() {
        scope.launchSafely {
            AppLogger.i(TAG, "Bootstrap started")
            val theme = settingsRepository.getString("app.theme") ?: _state.value.themeMode
            val language = settingsRepository.getString("app.language") ?: _state.value.language
            _state.value = _state.value.copy(themeMode = theme, language = language, isLoading = true)
            seedDataInitializer.ensureSeedData()
            googleAuthSyncService.initialize()
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val month = YearMonth.fromDate(now)
            _state.value = _state.value.copy(currentMonth = month)
            handleMonthRolloverUseCase(month)
            refreshInternal(showSkeleton = true)
            AppLogger.i(TAG, "Bootstrap finished")
        }
    }

    fun refresh() {
        scope.launchSafely {
            refreshInternal(showSkeleton = true)
        }
    }

    fun goToPreviousMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(-1)
            _state.value = _state.value.copy(currentMonth = target)
            refreshInternal(showSkeleton = true)
        }
    }

    fun goToNextMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(1)
            _state.value = _state.value.copy(currentMonth = target)
            refreshInternal(showSkeleton = true)
        }
    }

    fun goToCurrentMonth() {
        scope.launchSafely {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val target = YearMonth.fromDate(now)
            _state.value = _state.value.copy(currentMonth = target)
            refreshInternal(showSkeleton = true)
        }
    }

    fun loadMoreExpenses() {
        scope.launchSafely {
            val snapshot = _state.value
            if (snapshot.isLoadingMoreExpenses || !snapshot.hasMoreExpenses) return@launchSafely
            _state.value = snapshot.copy(isLoadingMoreExpenses = true)
            val nextPage = snapshot.expensesPage + 1
            val batch = expenseRepository.byMonthPaged(
                yearMonth = snapshot.currentMonth,
                limit = EXPENSES_PAGE_SIZE,
                offset = nextPage * EXPENSES_PAGE_SIZE,
            )
            val merged = (snapshot.expenses + batch).distinctBy { it.id }
            _state.value = _state.value.copy(
                expenses = merged,
                expensesPage = nextPage,
                hasMoreExpenses = batch.size.toLong() == EXPENSES_PAGE_SIZE,
                isLoadingMoreExpenses = false,
            )
        }
    }

    fun rolloverToNextMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(1)
            _state.value = _state.value.copy(currentMonth = target)
            handleMonthRolloverUseCase(target)
            refreshInternal(showSkeleton = true)
        }
    }

    fun setLanguage(language: String) {
        scope.launchSafely {
            settingsRepository.setString("app.language", language)
            refreshInternal()
        }
    }

    fun setThemeMode(mode: String) {
        scope.launchSafely {
            settingsRepository.setString("app.theme", mode)
            refreshInternal()
        }
    }

    fun signInGoogle() {
        scope.launchSafely {
            googleAuthSyncService.signIn()
        }
    }

    fun signOutGoogle() {
        scope.launchSafely {
            googleAuthSyncService.signOut()
        }
    }

    fun syncGoogleDrive() {
        scope.launchSafely {
            googleAuthSyncService.syncNow()
            refreshInternal()
        }
    }

    fun restoreFromGoogleDrive(overwriteLocal: Boolean) {
        scope.launchSafely {
            googleAuthSyncService.restoreFromCloud(overwriteLocal)
            if (googleAuthSyncService.state.value.requiresAppRestart) {
                // DB file was replaced; avoid using stale open connections before app restart.
                return@launchSafely
            }
            seedDataInitializer.ensureSeedData()
            refreshInternal()
        }
    }

    fun resetLocalData() {
        scope.launchSafely {
            _state.value = _state.value.copy(isLoading = true)
            localDataMaintenanceRepository.resetLocalData()
            seedDataInitializer.ensureSeedData()
            googleAuthSyncService.initialize()
            refreshInternal(showSkeleton = true)
        }
    }

    fun saveIncome(cents: Long, applyForward: Boolean = false) {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            if (applyForward) {
                saveIncomeForwardFrom(currentMonth, cents)
            } else {
                CreateBudgetMonthUseCase(budgetRepository)(currentMonth, cents)
                markExplicitBudgetMonth(currentMonth)
            }
            refreshInternal()
        }
    }

    fun setPlanningUsePostSavingsBudget(enabled: Boolean) {
        scope.launchSafely {
            settingsRepository.setString(PLANNING_USE_POST_SAVINGS_KEY, enabled.toString())
            _state.value = _state.value.copy(planningUsePostSavingsBudget = enabled)
        }
    }

    fun setPlanningMonthlyContribution(cents: Long) {
        scope.launchSafely {
            val safe = cents.coerceAtLeast(0L)
            settingsRepository.setString(PLANNING_MONTHLY_CONTRIBUTION_KEY, safe.toString())
            _state.value = _state.value.copy(planningMonthlyContributionCents = safe)
        }
    }

    fun setPlanningGoalAmount(cents: Long) {
        scope.launchSafely {
            val safe = cents.coerceAtLeast(0L)
            settingsRepository.setString(PLANNING_GOAL_AMOUNT_KEY, safe.toString())
            _state.value = _state.value.copy(planningGoalAmountCents = safe)
        }
    }

    fun setPlanningDesiredMargin(cents: Long) {
        scope.launchSafely {
            val safe = cents.coerceAtLeast(0L)
            settingsRepository.setString(PLANNING_DESIRED_MARGIN_KEY, safe.toString())
            _state.value = _state.value.copy(planningDesiredMarginCents = safe)
        }
    }

    fun saveAllocations(values: Map<String, Long>) {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            val month = budgetRepository.findByYearMonth(currentMonth)
                ?: CreateBudgetMonthUseCase(budgetRepository)(currentMonth, 0)
            val allocations = values.map { (categoryId, amount) ->
                BudgetAllocation(id("alloc"), month.id, categoryId, amount)
            }
            SetBudgetAllocationsUseCase(budgetRepository)(month.id, allocations)
            refreshInternal()
        }
    }

    fun addExpense(amountCents: Long, categoryId: String, note: String?, recurringMonthly: Boolean) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val nowDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val targetMonth = _state.value.currentMonth
            val day = nowDate.day.coerceAtMost(monthBounds(targetMonth).second.day)
            val occurredAt = LocalDate(targetMonth.year, targetMonth.month, day)
            AddOrUpdateExpenseUseCase(expenseRepository)(
                Expense(
                    id = id("exp"),
                    occurredAt = occurredAt,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    note = note?.trim()?.takeIf { it.isNotBlank() }?.take(120),
                    paymentMethod = PaymentMethod.DEBIT,
                    recurrenceType = if (recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
                    createdAt = Clock.System.now(),
                )
            )
            refreshInternal()
        }
    }

    fun addCategory(name: String, onCreated: ((String) -> Unit)? = null) {
        scope.launchSafely {
            val normalized = name.trim()
            if (normalized.isBlank()) return@launchSafely
            val existing = categoryRepository.list().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            if (existing != null) {
                onCreated?.invoke(existing.id)
                refreshInternal()
                return@launchSafely
            }
            val categoryId = id("cat")
            categoryRepository.upsert(
                Category(
                    id = categoryId,
                    name = normalized,
                    type = CategoryType.VARIABLE,
                )
            )
            onCreated?.invoke(categoryId)
            refreshInternal()
        }
    }

    fun addSubscription(name: String, amountCents: Long, billingDay: Int, categoryId: String) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val safeName = name.trim().take(120)
            require(safeName.isNotBlank()) { "Descricao obrigatoria" }
            val safeDay = billingDay.coerceIn(1, 31)
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                Subscription(
                    id = id("sub"),
                    name = safeName,
                    amountCents = amountCents,
                    billingDay = safeDay,
                    categoryId = categoryId,
                    active = true,
                    renewalPolicy = RenewalPolicy.MONTHLY,
                    nextDueDate = dueDateForMonth(_state.value.currentMonth, safeDay),
                )
            )
            refreshInternal()
        }
    }

    fun addInstallment(name: String, amountCents: Long, totalInstallments: Int, categoryId: String) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val safeName = name.trim().take(120)
            require(safeName.isNotBlank()) { "Descricao obrigatoria" }
            val safeInstallments = totalInstallments.coerceIn(1, 360)
            val monthlyAmountCents = toMonthlyInstallmentCents(amountCents, safeInstallments)
            val start = _state.value.currentMonth
            val end = start.plusMonths(safeInstallments - 1)
            CreateInstallmentPlanUseCase(installmentRepository, scheduleRepository)(
                InstallmentPlan(
                    id = id("inst"),
                    name = safeName,
                    totalInstallments = safeInstallments,
                    monthlyAmountCents = monthlyAmountCents,
                    startYearMonth = start,
                    endYearMonth = end,
                    categoryId = categoryId,
                    active = true,
                )
            )
            refreshInternal()
        }
    }

    fun updateExpense(expenseId: String, amountCents: Long, categoryId: String, description: String?, recurringMonthly: Boolean) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val current = expenseRepository.getById(expenseId) ?: return@launchSafely
            val updated = current.copy(
                amountCents = amountCents,
                categoryId = categoryId,
                note = description?.trim()?.takeIf { it.isNotBlank() }?.take(120),
                recurrenceType = if (recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
            )
            if (current.recurrenceType == RecurrenceType.MONTHLY && current.scheduleItemId == null) {
                if (recurringMonthly) {
                    expenseRepository.updateFutureRecurringByTemplate(
                        fromDateExclusive = current.occurredAt,
                        oldTemplate = current,
                        newTemplate = updated.copy(recurrenceType = RecurrenceType.MONTHLY),
                    )
                } else {
                    expenseRepository.deleteFutureRecurringByTemplate(
                        fromDateExclusive = current.occurredAt,
                        template = current,
                    )
                }
            }
            AddOrUpdateExpenseUseCase(expenseRepository)(updated)
            refreshInternal()
        }
    }

    fun deleteExpense(expenseId: String) {
        scope.launchSafely {
            val current = expenseRepository.getById(expenseId)
            if (current != null && current.recurrenceType == RecurrenceType.MONTHLY && current.scheduleItemId == null) {
                expenseRepository.deleteFutureRecurringByTemplate(
                    fromDateExclusive = current.occurredAt,
                    template = current,
                )
            }
            DeleteExpenseUseCase(expenseRepository)(expenseId)
            refreshInternal()
        }
    }

    fun updateSubscription(subscriptionId: String, name: String, amountCents: Long, billingDay: Int, categoryId: String) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val safeName = name.trim().take(120)
            require(safeName.isNotBlank()) { "Descricao obrigatoria" }
            val safeDay = billingDay.coerceIn(1, 31)
            val currentMonth = _state.value.currentMonth
            val monthStart = LocalDate(currentMonth.year, currentMonth.month, 1)
            scheduleRepository.deleteByRefAndKindFromDate(subscriptionId, ScheduleKind.SUBSCRIPTION, monthStart)
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                Subscription(
                    id = subscriptionId,
                    name = safeName,
                    amountCents = amountCents,
                    billingDay = safeDay,
                    categoryId = categoryId,
                    active = true,
                    renewalPolicy = RenewalPolicy.MONTHLY,
                    nextDueDate = dueDateForMonth(currentMonth, safeDay),
                )
            )
            refreshInternal()
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        scope.launchSafely {
            scheduleRepository.deleteByRefAndKind(subscriptionId, ScheduleKind.SUBSCRIPTION)
            subscriptionRepository.delete(subscriptionId)
            refreshInternal()
        }
    }

    fun updateInstallment(installmentId: String, name: String, amountCents: Long, totalInstallments: Int, categoryId: String) {
        scope.launchSafely {
            requireNonNegative(amountCents)
            require(categoryId.isNotBlank()) { "Categoria obrigatoria" }
            val safeName = name.trim().take(120)
            require(safeName.isNotBlank()) { "Descricao obrigatoria" }
            val safeInstallments = totalInstallments.coerceIn(1, 360)
            val monthlyAmountCents = toMonthlyInstallmentCents(amountCents, safeInstallments)
            val current = installmentRepository.list().firstOrNull { it.id == installmentId } ?: return@launchSafely
            val currentMonth = _state.value.currentMonth
            val start = current.startYearMonth
            val monthStart = LocalDate(currentMonth.year, currentMonth.month, 1)
            scheduleRepository.deleteByRefAndKindFromDate(installmentId, ScheduleKind.INSTALLMENT, monthStart)
            CreateInstallmentPlanUseCase(installmentRepository, scheduleRepository)(
                InstallmentPlan(
                    id = installmentId,
                    name = safeName,
                    totalInstallments = safeInstallments,
                    monthlyAmountCents = monthlyAmountCents,
                    startYearMonth = start,
                    endYearMonth = start.plusMonths(safeInstallments - 1),
                    categoryId = categoryId,
                    active = true,
                )
            )
            refreshInternal()
        }
    }

    fun deleteInstallment(installmentId: String) {
        scope.launchSafely {
            scheduleRepository.deleteByRefAndKind(installmentId, ScheduleKind.INSTALLMENT)
            installmentRepository.delete(installmentId)
            refreshInternal()
        }
    }

    fun duplicateExpense(expenseId: String) {
        scope.launchSafely {
            val current = expenseRepository.getById(expenseId) ?: return@launchSafely
            val targetMonth = _state.value.currentMonth
            val day = current.occurredAt.day.coerceAtMost(monthBounds(targetMonth).second.day)
            AddOrUpdateExpenseUseCase(expenseRepository)(
                current.copy(
                    id = id("exp"),
                    occurredAt = LocalDate(targetMonth.year, targetMonth.month, day),
                    createdAt = Clock.System.now(),
                    scheduleItemId = null,
                )
            )
            refreshInternal()
        }
    }

    fun duplicateSubscription(subscriptionId: String) {
        scope.launchSafely {
            val current = subscriptionRepository.list().firstOrNull { it.id == subscriptionId } ?: return@launchSafely
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                current.copy(
                    id = id("sub"),
                    nextDueDate = dueDateForMonth(_state.value.currentMonth, current.billingDay),
                )
            )
            refreshInternal()
        }
    }

    fun duplicateInstallment(installmentId: String) {
        scope.launchSafely {
            val current = installmentRepository.list().firstOrNull { it.id == installmentId } ?: return@launchSafely
            val start = _state.value.currentMonth
            val safeInstallments = current.totalInstallments.coerceIn(1, 360)
            val totalAmountCents = current.monthlyAmountCents * safeInstallments.toLong()
            val monthlyAmountCents = toMonthlyInstallmentCents(totalAmountCents, safeInstallments)
            CreateInstallmentPlanUseCase(installmentRepository, scheduleRepository)(
                current.copy(
                    id = id("inst"),
                    monthlyAmountCents = monthlyAmountCents,
                    startYearMonth = start,
                    endYearMonth = start.plusMonths(safeInstallments - 1),
                )
            )
            refreshInternal()
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit) {
        launch {
            _state.value = _state.value.copy(errorMessage = null)
            runCatching { block() }
                .onFailure {
                    AppLogger.e(TAG, "Unhandled store error", it)
                    _state.value = _state.value.copy(errorMessage = it.message ?: "Erro inesperado")
                }
        }
    }

    private suspend fun refreshInternal(showSkeleton: Boolean = false) {
        if (showSkeleton) {
            _state.value = _state.value.copy(isLoading = true)
        }
        val perf = mutableListOf<Pair<String, Long>>()
        suspend fun <T> profiled(name: String, block: suspend () -> T): T {
            val started = Clock.System.now()
            val value = block()
            val elapsed = (Clock.System.now() - started).inWholeMilliseconds
            perf += name to elapsed
            return value
        }
        val currentMonth = _state.value.currentMonth
        val now = profiled("now_date") { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
        handleMonthRolloverUseCase(currentMonth)
        ensureFutureSchedulesFrom(currentMonth, 24)
        val summary = profiled("compute_monthly_summary") { computeMonthlySummaryUseCase(currentMonth) }
        val forecast = profiled("compute_forecast") { computeCashFlowForecastUseCase(LocalDate(currentMonth.year, currentMonth.month, 1), 12) }
        val categories = profiled("list_categories") { categoryRepository.list() }
        val monthBudget = profiled("find_budget_month") { budgetRepository.findByYearMonth(currentMonth) }
        val allocations = profiled("list_allocations") { monthBudget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty() }
        val expenses = profiled("list_expenses_page_1") { expenseRepository.byMonthPaged(currentMonth, EXPENSES_PAGE_SIZE, 0) }
        val dues = profiled("list_dues_14d") { scheduleRepository.byDateRange(now, now.plus(DatePeriod(days = 14))) }
        val subs = profiled("list_subscriptions") { subscriptionRepository.list() }
        val installments = profiled("list_installments") { installmentRepository.list() }
        val language = profiled("load_language") { settingsRepository.getString("app.language") ?: "system" }
        val themeMode = profiled("load_theme") { settingsRepository.getString("app.theme") ?: "system" }
        val planningUsePostSavings = profiled("load_planning_use_post_savings") {
            settingsRepository.getString(PLANNING_USE_POST_SAVINGS_KEY)?.toBooleanStrictOrNull()
                ?: _state.value.planningUsePostSavingsBudget
        }
        val planningMonthlyContribution = profiled("load_planning_monthly_contribution") {
            settingsRepository.getString(PLANNING_MONTHLY_CONTRIBUTION_KEY)?.toLongOrNull()
                ?: _state.value.planningMonthlyContributionCents
        }
        val planningGoalAmount = profiled("load_planning_goal_amount") {
            settingsRepository.getString(PLANNING_GOAL_AMOUNT_KEY)?.toLongOrNull()
                ?: _state.value.planningGoalAmountCents
        }
        val planningDesiredMargin = profiled("load_planning_desired_margin") {
            settingsRepository.getString(PLANNING_DESIRED_MARGIN_KEY)?.toLongOrNull()
                ?: _state.value.planningDesiredMarginCents
        }
        val history = profiled("list_history_months") { budgetRepository.listMonths().ifEmpty { listOf(currentMonth) } }
        val previousMonth = currentMonth.plusMonths(-1)
        val previousSummary = profiled("compute_previous_summary") { computeMonthlySummaryUseCase(previousMonth) }
        val comparison = MonthComparison(
            previousMonth = previousMonth,
            spentDeltaCents = summary.spentTotalCents - previousSummary.spentTotalCents,
            commitmentsDeltaCents = summary.commitmentsCents - previousSummary.commitmentsCents,
        )
        val slowQueries = perf
            .filter { it.second >= SLOW_QUERY_MS }
            .sortedByDescending { it.second }
            .map { "${it.first}: ${it.second}ms" }
        if (slowQueries.isNotEmpty()) {
            AppLogger.w("Perf", "Slow queries (${currentMonth}): ${slowQueries.joinToString(" | ")}")
        }

        val previous = _state.value
        _state.value = MainState(
            currentMonth = currentMonth,
            summary = summary,
            forecast = forecast,
            categories = categories,
            allocations = allocations,
            expenses = expenses,
            upcomingDues = dues,
            subscriptions = subs,
            installments = installments,
            monthHistory = history,
            comparison = comparison,
            canGoNextMonth = true,
            expensesPage = 0,
            hasMoreExpenses = expenses.size.toLong() == EXPENSES_PAGE_SIZE,
            isLoadingMoreExpenses = false,
            language = language,
            themeMode = themeMode,
            slowQueries = slowQueries,
            googleSync = googleAuthSyncService.state.value,
            errorMessage = previous.errorMessage,
            isLoading = false,
            planningUsePostSavingsBudget = planningUsePostSavings,
            planningMonthlyContributionCents = planningMonthlyContribution,
            planningGoalAmountCents = planningGoalAmount,
            planningDesiredMarginCents = planningDesiredMargin,
        )
    }

    private suspend fun ensureFutureSchedulesFrom(startMonth: YearMonth, monthsAhead: Int) {
        subscriptionRepository.listActive().forEach { sub ->
            val originMonth = YearMonth.fromDate(sub.nextDueDate)
            val generationStart = if (startMonth < originMonth) originMonth else startMonth
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                sub.copy(nextDueDate = dueDateForMonth(generationStart, sub.billingDay)),
                monthsAhead = monthsAhead,
            )
        }
    }

    private suspend fun saveIncomeForwardFrom(startMonth: YearMonth, cents: Long) {
        CreateBudgetMonthUseCase(budgetRepository)(startMonth, cents)
        markExplicitBudgetMonth(startMonth)
        var cursor = startMonth.plusMonths(1)
        var filled = 0
        while (filled < RETRO_BUDGET_FILL_MONTHS) {
            if (isExplicitBudgetMonth(cursor)) {
                break
            }
            budgetRepository.createOrUpdateMonth(cursor, cents)
            cursor = cursor.plusMonths(1)
            filled++
        }
    }

    private fun toMonthlyInstallmentCents(totalAmountCents: Long, totalInstallments: Int): Long {
        if (totalInstallments <= 0) return totalAmountCents.coerceAtLeast(0L)
        val split = totalAmountCents / totalInstallments.toLong()
        return split.coerceAtLeast(if (totalAmountCents > 0L) 1L else 0L)
    }

    private suspend fun markExplicitBudgetMonth(month: YearMonth) {
        settingsRepository.setString("$EXPLICIT_BUDGET_KEY_PREFIX$month", "1")
    }

    private suspend fun isExplicitBudgetMonth(month: YearMonth): Boolean {
        return settingsRepository.getString("$EXPLICIT_BUDGET_KEY_PREFIX$month") == "1"
    }
}

data class MainState(
    val currentMonth: YearMonth = YearMonth(2026, 1),
    val summary: MonthlySummary? = null,
    val forecast: List<ForecastMonth> = emptyList(),
    val categories: List<Category> = emptyList(),
    val allocations: List<BudgetAllocation> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val upcomingDues: List<PaymentScheduleItem> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val installments: List<InstallmentPlan> = emptyList(),
    val monthHistory: List<YearMonth> = emptyList(),
    val comparison: MonthComparison? = null,
    val canGoNextMonth: Boolean = true,
    val expensesPage: Long = 0,
    val hasMoreExpenses: Boolean = false,
    val isLoadingMoreExpenses: Boolean = false,
    val language: String = "system",
    val themeMode: String = "system",
    val slowQueries: List<String> = emptyList(),
    val googleSync: GoogleSyncState = GoogleSyncState(
        isAvailable = false,
        isSignedIn = false,
    ),
    val planningUsePostSavingsBudget: Boolean = true,
    val planningMonthlyContributionCents: Long = 50_000L,
    val planningGoalAmountCents: Long = 1_000_000L,
    val planningDesiredMarginCents: Long = 0L,
    val errorMessage: String? = null,
    val isLoading: Boolean = true,
)
