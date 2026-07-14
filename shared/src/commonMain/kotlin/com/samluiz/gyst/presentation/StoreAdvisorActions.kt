package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorCategoryContext
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorService

internal class StoreAdvisorActions(
    private val advisorService: AdvisorService,
    private val getState: () -> MainState,
) {
    suspend fun configure(
        baseUrl: String,
        model: String,
        apiFormat: AdvisorApiFormat,
        apiKey: String?,
    ) {
        advisorService.configure(AdvisorConfig(baseUrl, model, apiFormat), apiKey)
    }

    suspend fun ask(prompt: String) {
        val state = getState()
        advisorService.ask(
            prompt = prompt,
            context = state.toAdvisorFinancialContext(),
            languageCode = state.language,
        )
    }

    suspend fun ensureOverview(force: Boolean) {
        val state = getState()
        advisorService.ensureOverview(
            context = state.toAdvisorFinancialContext(),
            languageCode = state.language,
            force = force,
        )
    }

    suspend fun clearConversation() = advisorService.clearConversation()

    suspend fun disconnect() = advisorService.disconnect()
}

internal fun MainState.toAdvisorFinancialContext(): com.samluiz.gyst.domain.service.AdvisorFinancialContext {
    val nextEnd =
        installments
            .filter { it.active && it.endYearMonth >= currentMonth }
            .minByOrNull { it.endYearMonth.year * 100 + it.endYearMonth.month }
    val categoriesById = categories.associateBy { it.id }
    val categoryBreakdown =
        summary
            ?.perCategory
            .orEmpty()
            .mapNotNull { categorySummary ->
                val category = categoriesById[categorySummary.categoryId] ?: return@mapNotNull null
                AdvisorCategoryContext(
                    name = category.name,
                    type = category.type.name,
                    plannedCents = categorySummary.plannedCents,
                    spentCents = categorySummary.spentCents,
                    remainingCents = categorySummary.remainingCents,
                )
            }
            .sortedByDescending { it.spentCents }
    return com.samluiz.gyst.domain.service.AdvisorFinancialContext(
        month = currentMonth,
        summary = summary,
        forecast = forecast,
        activeSubscriptions = subscriptions.count { it.active },
        activeInstallments = installments.count { it.active },
        nextFreedCashMonth = nextEnd?.endYearMonth,
        nextFreedCashCents = nextEnd?.monthlyAmountCents ?: 0L,
        categoryBreakdown = categoryBreakdown,
        previousMonthComparison = comparison,
        recordedMonthCount = monthHistory.size,
    )
}
