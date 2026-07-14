package com.samluiz.gyst.presentation

import com.samluiz.gyst.data.repository.DatabaseRuntimeController
import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AppUpdateService
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.usecase.*
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private const val BLOCKING_RESTORE_TOKEN = "sync.restore.applying"
private const val BLOCKING_RELOAD_TOKEN = "sync.reload.applying"
private const val MAX_TEXT_FIELD_LENGTH = 120
private const val MAX_INSTALLMENTS = 360
private const val ERROR_REQUIRED_CATEGORY = "Categoria obrigatoria"
private const val ERROR_REQUIRED_DESCRIPTION = "Descricao obrigatoria"

class MainStore(
    private val seedDataInitializer: SeedDataInitializer,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val installmentRepository: InstallmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val localDataMaintenanceRepository: LocalDataMaintenanceRepository,
    private val googleAuthSyncService: GoogleAuthSyncService,
    private val appUpdateService: AppUpdateService,
    private val advisorService: AdvisorService,
    private val databaseRuntimeController: DatabaseRuntimeController,
    private val createBudgetMonthUseCase: CreateBudgetMonthUseCase,
    private val setBudgetAllocationsUseCase: SetBudgetAllocationsUseCase,
    private val addOrUpdateExpenseUseCase: AddOrUpdateExpenseUseCase,
    private val deleteExpenseUseCase: DeleteExpenseUseCase,
    private val upsertSubscriptionUseCase: UpsertSubscriptionUseCase,
    private val createInstallmentPlanUseCase: CreateInstallmentPlanUseCase,
    private val computeMonthlySummaryUseCase: ComputeMonthlySummaryUseCase,
    private val computeCashFlowForecastUseCase: ComputeCashFlowForecastUseCase,
    private val handleMonthRolloverUseCase: HandleMonthRolloverUseCase,
) {
    private companion object {
        const val TAG = "MainStore"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actionMutex = Mutex()

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()
    private val syncActions =
        StoreSyncActions(
            googleAuthSyncService = googleAuthSyncService,
            appUpdateService = appUpdateService,
            databaseRuntimeController = databaseRuntimeController,
            seedDataInitializer = seedDataInitializer,
            setState = { _state.value = it },
            getState = { _state.value },
            refresh = { showSkeleton -> refreshInternal(showSkeleton) },
        )
    private val expenseActions =
        StoreExpenseActions(
            expenseRepository = expenseRepository,
            subscriptionRepository = subscriptionRepository,
            installmentRepository = installmentRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            scheduleRepository = scheduleRepository,
            addOrUpdateExpenseUseCase = addOrUpdateExpenseUseCase,
            deleteExpenseUseCase = deleteExpenseUseCase,
            upsertSubscriptionUseCase = upsertSubscriptionUseCase,
            createInstallmentPlanUseCase = createInstallmentPlanUseCase,
            getState = { _state.value },
            refresh = { showSkeleton -> refreshInternal(showSkeleton) },
        )
    private val categoryActions =
        StoreCategoryActions(
            categoryRepository = categoryRepository,
            refresh = { showSkeleton -> refreshInternal(showSkeleton) },
        )
    private val advisorActions = StoreAdvisorActions(advisorService, getState = { _state.value })

    init {
        scope.launch {
            googleAuthSyncService.state.collectLatest { google ->
                _state.value = _state.value.copy(googleSync = google)
            }
        }
        scope.launch {
            appUpdateService.state.collectLatest { update ->
                _state.value = _state.value.copy(appUpdate = update)
            }
        }
        scope.launch {
            advisorService.state.collectLatest { advisor ->
                _state.value = _state.value.copy(advisor = advisor)
            }
        }
    }

    fun bootstrap() {
        scope.launchSafely {
            AppLogger.i(TAG, "Bootstrap started")
            val theme = normalizeThemeMode(settingsRepository.getString("app.theme") ?: _state.value.themeMode)
            val language = settingsRepository.getString("app.language") ?: _state.value.language
            _state.value = _state.value.copy(themeMode = theme, language = language, isLoading = true)
            seedDataInitializer.ensureSeedData()
            googleAuthSyncService.initialize()
            advisorService.initialize()
            appUpdateService.checkForUpdates(silent = true)
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val month = YearMonth.fromDate(now)
            _state.value = _state.value.copy(currentMonth = month)
            handleMonthRolloverUseCase(month)
            ensureFutureSchedulesFrom(month, 24)
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
            try {
                val nextPage = snapshot.expensesPage + 1
                val batch =
                    expenseRepository.byMonthPaged(
                        yearMonth = snapshot.currentMonth,
                        limit = EXPENSES_PAGE_SIZE,
                        offset = nextPage * EXPENSES_PAGE_SIZE,
                    )
                val merged = (snapshot.expenses + batch).distinctBy { it.id }
                _state.value =
                    _state.value.copy(
                        expenses = merged,
                        expensesPage = nextPage,
                        hasMoreExpenses = batch.size.toLong() == EXPENSES_PAGE_SIZE,
                    )
            } finally {
                _state.value = _state.value.copy(isLoadingMoreExpenses = false)
            }
        }
    }

    fun setLanguage(language: String) {
        scope.launchSafely {
            if (_state.value.language == language) return@launchSafely
            settingsRepository.setString("app.language", language)
            refreshInternal()
        }
    }

    fun setThemeMode(mode: String) {
        scope.launchSafely {
            val normalized = normalizeThemeMode(mode)
            if (_state.value.themeMode == normalized) return@launchSafely
            settingsRepository.setString("app.theme", normalized)
            refreshInternal()
        }
    }

    fun configureAdvisor(
        baseUrl: String,
        model: String,
        apiFormat: AdvisorApiFormat,
        apiKey: String?,
    ) {
        scope.launchSafely(serialized = false) { advisorActions.configure(baseUrl, model, apiFormat, apiKey) }
    }

    fun askAdvisor(
        prompt: String,
        languageCode: String,
    ) {
        scope.launchSafely(serialized = false) { advisorActions.ask(prompt, languageCode) }
    }

    fun ensureAdvisorOverview(
        force: Boolean = false,
        languageCode: String,
    ) {
        scope.launchSafely(serialized = false) { advisorActions.ensureOverview(force, languageCode) }
    }

    fun clearAdvisorConversation() {
        scope.launchSafely(serialized = false) { advisorActions.clearConversation() }
    }

    fun disconnectAdvisor() {
        scope.launchSafely(serialized = false) { advisorActions.disconnect() }
    }

    fun signInGoogle() {
        scope.launchSafely {
            syncActions.signInGoogle()
        }
    }

    fun signOutGoogle() {
        scope.launchSafely {
            syncActions.signOutGoogle()
        }
    }

    fun checkForUpdates() {
        scope.launchSafely {
            syncActions.checkForUpdates()
        }
    }

    fun startUpdate() {
        scope.launchSafely {
            syncActions.startUpdate()
        }
    }

    fun syncGoogleDrive() {
        scope.launchSafely(allowDuringBlocking = true) {
            syncActions.syncGoogleDrive(::applyHotReloadIfNeeded)
        }
    }

    fun restoreFromGoogleDrive(overwriteLocal: Boolean) {
        scope.launchSafely(allowDuringBlocking = true) {
            syncActions.restoreFromGoogleDrive(
                overwriteLocal = overwriteLocal,
                blockingRestoreToken = BLOCKING_RESTORE_TOKEN,
                applyHotReloadIfNeeded = ::applyHotReloadIfNeeded,
            )
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

    fun saveIncome(
        cents: Long,
        applyForward: Boolean = false,
    ) {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            if (applyForward) {
                saveIncomeForwardFrom(currentMonth, cents)
            } else {
                createBudgetMonthUseCase(currentMonth, cents)
                markExplicitBudgetMonth(currentMonth)
            }
            refreshInternal()
        }
    }

    fun saveAllocations(values: Map<String, Long>) {
        scope.launchSafely {
            val currentMonth = _state.value.currentMonth
            val month =
                budgetRepository.findByYearMonth(currentMonth)
                    ?: createBudgetMonthUseCase(currentMonth, 0)
            val allocations =
                values.map { (categoryId, amount) ->
                    BudgetAllocation(id("alloc"), month.id, categoryId, amount)
                }
            setBudgetAllocationsUseCase(month.id, allocations)
            refreshInternal()
        }
    }

    fun addExpense(
        amountCents: Long,
        categoryId: String,
        note: String?,
        recurringMonthly: Boolean,
    ) {
        scope.launchSafely {
            expenseActions.addExpense(
                draft =
                    ExpenseDraft(
                        amountCents = amountCents,
                        categoryId = categoryId,
                        note = note,
                        recurringMonthly = recurringMonthly,
                    ),
                sanitizeNote = ::sanitizeOptionalNote,
                requireCategoryId = ::requireCategoryId,
            )
        }
    }

    fun addCategory(
        name: String,
        onCreated: ((String) -> Unit)? = null,
    ) {
        scope.launchSafely {
            categoryActions.add(name, onCreated)
        }
    }

    fun updateCategoryName(
        categoryId: String,
        name: String,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        scope.launchSafely {
            categoryActions.rename(categoryId, name, onDone)
        }
    }

    fun deleteCategory(
        categoryId: String,
        onDone: ((Boolean, Long) -> Unit)? = null,
    ) {
        scope.launchSafely {
            categoryActions.delete(categoryId, onDone)
        }
    }

    fun addSubscription(
        name: String,
        amountCents: Long,
        billingDay: Int,
        categoryId: String,
    ) {
        scope.launchSafely {
            expenseActions.addSubscription(
                draft =
                    SubscriptionDraft(
                        name = name,
                        amountCents = amountCents,
                        billingDay = billingDay,
                        categoryId = categoryId,
                    ),
                requireCategoryId = ::requireCategoryId,
                requireName = ::requireName,
            )
        }
    }

    fun addInstallment(
        name: String,
        amountCents: Long,
        totalInstallments: Int,
        categoryId: String,
    ) {
        scope.launchSafely {
            expenseActions.addInstallment(
                draft =
                    InstallmentDraft(
                        name = name,
                        amountCents = amountCents,
                        totalInstallments = totalInstallments,
                        categoryId = categoryId,
                    ),
                requireCategoryId = ::requireCategoryId,
                requireName = ::requireName,
            )
        }
    }

    fun updateExpense(
        expenseId: String,
        amountCents: Long,
        categoryId: String,
        description: String?,
        recurringMonthly: Boolean,
    ) {
        scope.launchSafely {
            expenseActions.updateExpense(expenseId, amountCents, categoryId, description, recurringMonthly)
        }
    }

    fun deleteExpense(expenseId: String) {
        scope.launchSafely {
            expenseActions.deleteExpense(expenseId)
        }
    }

    fun updateSubscription(
        subscriptionId: String,
        name: String,
        amountCents: Long,
        billingDay: Int,
        categoryId: String,
    ) {
        scope.launchSafely {
            expenseActions.updateSubscription(subscriptionId, name, amountCents, billingDay, categoryId)
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        scope.launchSafely {
            expenseActions.deleteSubscription(subscriptionId)
        }
    }

    fun updateInstallment(
        installmentId: String,
        name: String,
        amountCents: Long,
        totalInstallments: Int,
        categoryId: String,
    ) {
        scope.launchSafely {
            expenseActions.updateInstallment(installmentId, name, amountCents, totalInstallments, categoryId)
        }
    }

    fun deleteInstallment(installmentId: String) {
        scope.launchSafely {
            expenseActions.deleteInstallment(installmentId)
        }
    }

    fun duplicateExpense(expenseId: String) {
        scope.launchSafely {
            expenseActions.duplicateExpense(expenseId)
        }
    }

    fun duplicateSubscription(subscriptionId: String) {
        scope.launchSafely {
            expenseActions.duplicateSubscription(subscriptionId)
        }
    }

    fun duplicateInstallment(installmentId: String) {
        scope.launchSafely {
            expenseActions.duplicateInstallment(installmentId)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun clearInfo() {
        _state.value = _state.value.copy(infoMessage = null)
    }

    private fun CoroutineScope.launchSafely(
        allowDuringBlocking: Boolean = false,
        serialized: Boolean = true,
        block: suspend () -> Unit,
    ) {
        launch {
            if (!allowDuringBlocking && _state.value.blockingMessage != null) {
                return@launch
            }
            runCatching {
                suspend fun execute() {
                    _state.value = _state.value.copy(errorMessage = null)
                    block()
                }
                if (serialized) actionMutex.withLock { execute() } else execute()
            }
                .onFailure {
                    AppLogger.e(TAG, "Unhandled store error", it)
                    _state.value = _state.value.copy(errorMessage = it.message ?: "Erro inesperado")
                }
        }
    }

    private suspend fun applyHotReloadIfNeeded(
        reason: String,
        force: Boolean = false,
    ): Boolean {
        if (!force && !googleAuthSyncService.state.value.requiresAppRestart) return false
        AppLogger.i(TAG, "Applying hot DB reload after $reason")
        _state.value = _state.value.copy(isLoading = true, blockingMessage = BLOCKING_RELOAD_TOKEN)
        try {
            databaseRuntimeController.reloadDatabase()
            googleAuthSyncService.initialize()
            return true
        } finally {
            _state.value = _state.value.copy(blockingMessage = null)
        }
    }

    private suspend fun refreshInternal(showSkeleton: Boolean = false) {
        if (showSkeleton) {
            _state.value = _state.value.copy(isLoading = true)
        }
        val perf = mutableListOf<Pair<String, Long>>()

        suspend fun <T> profiled(
            name: String,
            block: suspend () -> T,
        ): T {
            val started = Clock.System.now()
            val value = block()
            val elapsed = (Clock.System.now() - started).inWholeMilliseconds
            perf += name to elapsed
            return value
        }
        val currentMonth = _state.value.currentMonth
        val now = profiled("now_date") { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
        val data =
            coroutineScope {
                val summaryDeferred = async { profiled("compute_monthly_summary") { computeMonthlySummaryUseCase(currentMonth) } }
                val forecastDeferred =
                    async {
                        profiled(
                            "compute_forecast",
                        ) { computeCashFlowForecastUseCase(LocalDate(currentMonth.year, currentMonth.month, 1), 12) }
                    }
                val categoriesDeferred = async { profiled("list_categories") { categoryRepository.list() } }
                val monthBudgetDeferred = async { profiled("find_budget_month") { budgetRepository.findByYearMonth(currentMonth) } }
                val expensesDeferred =
                    async { profiled("list_expenses_page_1") { expenseRepository.byMonthPaged(currentMonth, EXPENSES_PAGE_SIZE, 0) } }
                val duesDeferred =
                    async { profiled("list_dues_14d") { scheduleRepository.byDateRange(now, now.plus(DatePeriod(days = 14))) } }
                val subsDeferred = async { profiled("list_subscriptions") { subscriptionRepository.list() } }
                val installmentsDeferred = async { profiled("list_installments") { installmentRepository.list() } }
                val languageDeferred = async { profiled("load_language") { settingsRepository.getString("app.language") ?: "system" } }
                val themeModeDeferred = async { profiled("load_theme") { normalizeThemeMode(settingsRepository.getString("app.theme")) } }
                val historyDeferred =
                    async { profiled("list_history_months") { budgetRepository.listMonths().ifEmpty { listOf(currentMonth) } } }
                RefreshBundle(
                    summary = summaryDeferred.await(),
                    forecast = forecastDeferred.await(),
                    categories = categoriesDeferred.await(),
                    monthBudget = monthBudgetDeferred.await(),
                    expenses = expensesDeferred.await(),
                    dues = duesDeferred.await(),
                    subscriptions = subsDeferred.await(),
                    installments = installmentsDeferred.await(),
                    language = languageDeferred.await(),
                    themeMode = themeModeDeferred.await(),
                    history = historyDeferred.await(),
                )
            }
        val summary = data.summary
        val forecast = data.forecast
        val categories = data.categories
        val monthBudget = data.monthBudget
        val expenses = data.expenses
        val dues = data.dues
        val subs = data.subscriptions
        val installments = data.installments
        val language = data.language
        val themeMode = data.themeMode
        val history = data.history
        val allocations = profiled("list_allocations") { monthBudget?.let { budgetRepository.allocationsByBudgetMonth(it.id) }.orEmpty() }
        val previousMonth = currentMonth.plusMonths(-1)
        val previousSummary = profiled("compute_previous_summary") { computeMonthlySummaryUseCase(previousMonth) }
        val comparison =
            MonthComparison(
                previousMonth = previousMonth,
                spentDeltaCents = summary.spentTotalCents - previousSummary.spentTotalCents,
                commitmentsDeltaCents = summary.commitmentsCents - previousSummary.commitmentsCents,
            )
        val slowQueries =
            perf
                .filter { it.second >= SLOW_QUERY_MS }
                .sortedByDescending { it.second }
                .map { "${it.first}: ${it.second}ms" }
        if (slowQueries.isNotEmpty()) {
            AppLogger.w("Perf", "Slow queries ($currentMonth): ${slowQueries.joinToString(" | ")}")
        }

        val previous = _state.value
        _state.value =
            MainState(
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
                appUpdate = appUpdateService.state.value,
                advisor = advisorService.state.value,
                errorMessage = previous.errorMessage,
                blockingMessage = previous.blockingMessage,
                infoMessage = previous.infoMessage,
                isLoading = false,
            )
    }

    private suspend fun ensureFutureSchedulesFrom(
        startMonth: YearMonth,
        monthsAhead: Int,
    ) {
        subscriptionRepository.listActive().forEach { sub ->
            upsertSubscriptionUseCase(
                sub,
                monthsAhead = monthsAhead,
                scheduleStartYearMonth = startMonth,
                persistSubscription = false,
            )
        }
    }

    private suspend fun saveIncomeForwardFrom(
        startMonth: YearMonth,
        cents: Long,
    ) {
        createBudgetMonthUseCase(startMonth, cents)
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

    private suspend fun markExplicitBudgetMonth(month: YearMonth) {
        settingsRepository.setString("$EXPLICIT_BUDGET_KEY_PREFIX$month", "1")
    }

    private suspend fun isExplicitBudgetMonth(month: YearMonth): Boolean {
        return settingsRepository.getString("$EXPLICIT_BUDGET_KEY_PREFIX$month") == "1"
    }

    private fun requireCategoryId(categoryId: String) {
        require(categoryId.isNotBlank()) { ERROR_REQUIRED_CATEGORY }
    }

    private fun requireName(name: String): String {
        val safeName = name.trim().take(MAX_TEXT_FIELD_LENGTH)
        require(safeName.isNotBlank()) { ERROR_REQUIRED_DESCRIPTION }
        return safeName
    }

    private fun sanitizeOptionalNote(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_FIELD_LENGTH)
    }
}

private data class RefreshBundle(
    val summary: MonthlySummary,
    val forecast: List<ForecastMonth>,
    val categories: List<Category>,
    val monthBudget: BudgetMonth?,
    val expenses: List<Expense>,
    val dues: List<PaymentScheduleItem>,
    val subscriptions: List<Subscription>,
    val installments: List<InstallmentPlan>,
    val language: String,
    val themeMode: String,
    val history: List<YearMonth>,
)
