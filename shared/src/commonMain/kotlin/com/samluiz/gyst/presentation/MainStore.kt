package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
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

class MainStore(
    private val seedDataInitializer: SeedDataInitializer,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val installmentRepository: InstallmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val googleAuthSyncService: GoogleAuthSyncService,
    private val computeMonthlySummaryUseCase: ComputeMonthlySummaryUseCase,
    private val computeCashFlowForecastUseCase: ComputeCashFlowForecastUseCase,
    private val handleMonthRolloverUseCase: HandleMonthRolloverUseCase,
) {
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
            seedDataInitializer.ensureSeedData()
            googleAuthSyncService.initialize()
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val month = YearMonth.fromDate(now)
            _state.value = _state.value.copy(currentMonth = month)
            handleMonthRolloverUseCase(month)
            refresh()
        }
    }

    fun refresh() {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            handleMonthRolloverUseCase(currentMonth)
            ensureFutureSchedulesFrom(currentMonth, 24)
            val summary = computeMonthlySummaryUseCase(currentMonth)
            val forecast = computeCashFlowForecastUseCase(LocalDate(currentMonth.year, currentMonth.month, 1), 12)
            val categories = categoryRepository.list()
            val monthBudget = budgetRepository.findByYearMonth(currentMonth)
            val allocations = monthBudget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty()
            val expenses = expenseRepository.byMonth(currentMonth)
            val dues = scheduleRepository.byDateRange(now, now.plus(DatePeriod(days = 14)))
            val subs = subscriptionRepository.list()
            val installments = installmentRepository.list()
            val guard = settingsRepository.getSafetyGuard()
            val language = settingsRepository.getString("app.language") ?: "system"
            val themeMode = settingsRepository.getString("app.theme") ?: "system"
            val history = budgetRepository.listMonths().ifEmpty { listOf(currentMonth) }
            val previousMonth = currentMonth.plusMonths(-1)
            val previousSummary = computeMonthlySummaryUseCase(previousMonth)
            val comparison = MonthComparison(
                previousMonth = previousMonth,
                spentDeltaCents = summary.spentTotalCents - previousSummary.spentTotalCents,
                commitmentsDeltaCents = summary.commitmentsCents - previousSummary.commitmentsCents,
            )

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
                safetyGuard = guard,
                monthHistory = history,
                comparison = comparison,
                canGoNextMonth = true,
                language = language,
                themeMode = themeMode,
                googleSync = googleAuthSyncService.state.value,
            )
        }
    }

    fun goToPreviousMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(-1)
            _state.value = _state.value.copy(currentMonth = target)
            refresh()
        }
    }

    fun goToNextMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(1)
            _state.value = _state.value.copy(currentMonth = target)
            refresh()
        }
    }

    fun rolloverToNextMonth() {
        scope.launchSafely {
            val target = _state.value.currentMonth.plusMonths(1)
            _state.value = _state.value.copy(currentMonth = target)
            handleMonthRolloverUseCase(target)
            refresh()
        }
    }

    fun setNoNewInstallments(enabled: Boolean) {
        scope.launchSafely {
            val current = settingsRepository.getSafetyGuard() ?: SafetyGuard(id("guard"))
            settingsRepository.upsertSafetyGuard(current.copy(noNewInstallments = enabled))
            refresh()
        }
    }

    fun setLanguage(language: String) {
        scope.launchSafely {
            settingsRepository.setString("app.language", language)
            refresh()
        }
    }

    fun setThemeMode(mode: String) {
        scope.launchSafely {
            settingsRepository.setString("app.theme", mode)
            refresh()
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
        }
    }

    fun saveIncome(cents: Long) {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            CreateBudgetMonthUseCase(budgetRepository)(currentMonth, cents)
            refresh()
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
            refresh()
        }
    }

    fun addExpense(amountCents: Long, categoryId: String, note: String?, recurringMonthly: Boolean) {
        scope.launchSafely {
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
                    note = note,
                    paymentMethod = PaymentMethod.DEBIT,
                    recurrenceType = if (recurringMonthly) RecurrenceType.MONTHLY else RecurrenceType.ONE_TIME,
                    createdAt = Clock.System.now(),
                )
            )
            refresh()
        }
    }

    fun addCategory(name: String) {
        scope.launchSafely {
            val normalized = name.trim()
            if (normalized.isBlank()) return@launchSafely
            categoryRepository.upsert(
                Category(
                    id = id("cat"),
                    name = normalized,
                    type = CategoryType.VARIABLE,
                )
            )
            refresh()
        }
    }

    fun addSubscription(name: String, amountCents: Long, billingDay: Int, categoryId: String) {
        scope.launchSafely {
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                Subscription(
                    id = id("sub"),
                    name = name,
                    amountCents = amountCents,
                    billingDay = billingDay,
                    categoryId = categoryId,
                    active = true,
                    renewalPolicy = RenewalPolicy.MONTHLY,
                    nextDueDate = dueDateForMonth(_state.value.currentMonth, billingDay),
                )
            )
            refresh()
        }
    }

    fun addInstallment(name: String, amountCents: Long, totalInstallments: Int, categoryId: String) {
        scope.launchSafely {
            val start = _state.value.currentMonth
            val end = start.plusMonths(totalInstallments - 1)
            CreateInstallmentPlanUseCase(installmentRepository, scheduleRepository, settingsRepository)(
                InstallmentPlan(
                    id = id("inst"),
                    name = name,
                    totalInstallments = totalInstallments,
                    monthlyAmountCents = amountCents,
                    startYearMonth = start,
                    endYearMonth = end,
                    categoryId = categoryId,
                    active = true,
                )
            )
            refresh()
        }
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit) {
        launch {
            runCatching { block() }
                .onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Erro inesperado") }
        }
    }

    private suspend fun ensureFutureSchedulesFrom(startMonth: YearMonth, monthsAhead: Int) {
        subscriptionRepository.listActive().forEach { sub ->
            UpsertSubscriptionUseCase(subscriptionRepository, scheduleRepository)(
                sub.copy(nextDueDate = dueDateForMonth(startMonth, sub.billingDay)),
                monthsAhead = monthsAhead,
            )
        }
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
    val safetyGuard: SafetyGuard? = null,
    val monthHistory: List<YearMonth> = emptyList(),
    val comparison: MonthComparison? = null,
    val canGoNextMonth: Boolean = true,
    val language: String = "system",
    val themeMode: String = "system",
    val googleSync: GoogleSyncState = GoogleSyncState(
        isAvailable = false,
        isSignedIn = false,
    ),
    val errorMessage: String? = null,
)
