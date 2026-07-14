package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.service.AdvisorState
import com.samluiz.gyst.domain.service.AppUpdateState
import com.samluiz.gyst.domain.service.GoogleSyncState

internal fun normalizeThemeMode(mode: String?): String =
    when (mode) {
        "light", "dark", "amoled" -> mode
        "system" -> "dark"
        else -> "dark"
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
    val themeMode: String = "dark",
    val slowQueries: List<String> = emptyList(),
    val googleSync: GoogleSyncState =
        GoogleSyncState(
            isAvailable = false,
            isSignedIn = false,
        ),
    val appUpdate: AppUpdateState =
        AppUpdateState(
            isAvailable = false,
        ),
    val advisor: AdvisorState = AdvisorState(),
    val blockingMessage: String? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = true,
)
