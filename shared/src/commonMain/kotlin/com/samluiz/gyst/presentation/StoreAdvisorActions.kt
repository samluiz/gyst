package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.model.displayDescription
import com.samluiz.gyst.domain.service.AdvisorCategoryContext
import com.samluiz.gyst.domain.service.AdvisorCommitmentContext
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorExpenseContext
import com.samluiz.gyst.domain.service.AdvisorService

internal class StoreAdvisorActions(
    private val advisorService: AdvisorService,
    private val getState: () -> MainState,
) {
    suspend fun configure(
        config: AdvisorConfig,
        apiKey: String?,
    ) {
        advisorService.configure(config, apiKey)
    }

    suspend fun ask(
        prompt: String,
        languageCode: String,
    ) {
        val state = getState()
        advisorService.ask(
            prompt = prompt,
            context = state.toAdvisorFinancialContext(),
            languageCode = languageCode,
        )
    }

    suspend fun ensureOverview(
        force: Boolean,
        languageCode: String,
    ) {
        val state = getState()
        advisorService.ensureOverview(
            context = state.toAdvisorFinancialContext(),
            languageCode = languageCode,
            force = force,
        )
    }

    suspend fun clearConversation() = advisorService.clearConversation()

    suspend fun createConversation(title: String?) = advisorService.createConversation(title)

    suspend fun selectConversation(conversationId: String) = advisorService.selectConversation(conversationId)

    suspend fun renameConversation(
        conversationId: String,
        title: String,
    ) = advisorService.renameConversation(conversationId, title)

    suspend fun deleteConversation(conversationId: String) = advisorService.deleteConversation(conversationId)

    suspend fun retryMessage(
        messageId: String,
        languageCode: String,
    ) {
        advisorService.retryMessage(
            messageId = messageId,
            context = getState().toAdvisorFinancialContext(),
            languageCode = languageCode,
        )
    }

    suspend fun cancelResponse() = advisorService.cancelResponse()

    suspend fun disconnect() = advisorService.disconnect()
}

internal fun MainState.toAdvisorFinancialContext(): com.samluiz.gyst.domain.service.AdvisorFinancialContext {
    val activeInstallments =
        installments
            .filter { it.active && it.endYearMonth >= currentMonth }
    val nextFreedCashMonth = activeInstallments.minOfOrNull { it.endYearMonth }
    val nextFreedCashCents =
        activeInstallments
            .filter { it.endYearMonth == nextFreedCashMonth }
            .sumOf { it.monthlyAmountCents }
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
    val largestExpenses =
        expenses
            .sortedByDescending { it.amountCents }
            .take(8)
            .map { expense ->
                val categoryName = categoriesById[expense.categoryId]?.name.orEmpty().ifBlank { "Uncategorized" }
                AdvisorExpenseContext(
                    description = expense.displayDescription() ?: categoryName,
                    category = categoryName,
                    occurredAt = expense.occurredAt.toString(),
                    amountCents = expense.amountCents,
                    recurring = expense.recurrenceType == com.samluiz.gyst.domain.model.RecurrenceType.MONTHLY,
                )
            }
    val commitmentDetails =
        subscriptions.filter { it.active }.map {
            AdvisorCommitmentContext(
                name = it.name,
                kind = "SUBSCRIPTION",
                monthlyCents = it.amountCents,
                endMonth = null,
            )
        } +
            installments.filter { it.active }.map {
                AdvisorCommitmentContext(
                    name = it.name,
                    kind = "INSTALLMENT",
                    monthlyCents = it.monthlyAmountCents,
                    endMonth = it.endYearMonth,
                )
            }
    return com.samluiz.gyst.domain.service.AdvisorFinancialContext(
        month = currentMonth,
        summary = summary,
        forecast = forecast,
        activeSubscriptions = subscriptions.count { it.active },
        activeInstallments = installments.count { it.active },
        nextFreedCashMonth = nextFreedCashMonth,
        nextFreedCashCents = nextFreedCashCents,
        categoryBreakdown = categoryBreakdown,
        largestExpenses = largestExpenses,
        commitments = commitmentDetails.sortedByDescending { it.monthlyCents }.take(16),
        previousMonthComparison = comparison,
        recordedMonthCount = monthHistory.size,
    )
}
