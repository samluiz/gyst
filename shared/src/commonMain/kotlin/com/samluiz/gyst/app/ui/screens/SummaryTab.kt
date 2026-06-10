package com.samluiz.gyst.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.CategorySummary
import com.samluiz.gyst.presentation.MainState

@Composable
internal fun ResumoTab(
    s: AppStrings,
    state: MainState,
    onSaveIncome: (Long, Boolean) -> Unit,
) {
    var budgetCentsDigits by remember(state.currentMonth, state.summary?.totalIncomeCents) {
        mutableStateOf((state.summary?.totalIncomeCents ?: 0L).toString())
    }
    var applyForward by rememberSaveable(state.currentMonth) { mutableStateOf(false) }
    val budget = state.summary?.totalIncomeCents ?: 0L

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            BudgetHero(
                s = s,
                budgetCentsDigits = budgetCentsDigits,
                onBudgetCentsDigits = { budgetCentsDigits = it.ifBlank { "0" } },
                onSaveBudget = {
                    onSaveIncome(
                        (budgetCentsDigits.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                        applyForward,
                    )
                },
                budget = budget,
                expenses = state.summary?.spentTotalCents ?: 0L,
                billings = state.summary?.commitmentsCents ?: 0L,
                applyForward = applyForward,
                onApplyForwardChange = { applyForward = it },
                showApplyForward = true,
            )
        }
        item {
            MonthComparisonCard(s, state)
        }
        item {
            CategoryDistributionCard(s, state)
        }
    }
}

@Composable
private fun CategoryDistributionCard(
    s: AppStrings,
    state: MainState,
) {
    val summary = state.summary ?: return
    val categoryById = remember(state.categories) { state.categories.associateBy { it.id } }
    val rows =
        summary.perCategory
            .filter { it.spentCents > 0L }
            .sortedByDescending { it.spentCents }

    PanelCard(title = s.categoryDistribution, icon = Icons.Default.PieChart) {
        if (rows.isEmpty()) {
            Text(
                s.noCategoryData,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PanelCard
        }

        val totalSpent = rows.sumOf { it.spentCents }.coerceAtLeast(1L)
        rows.forEachIndexed { index, row ->
            CategoryDistributionRow(
                row = row,
                totalSpent = totalSpent,
                label = categoryById[row.categoryId]?.name ?: s.category,
                tint =
                    when (index % 4) {
                        0 -> MaterialTheme.colorScheme.primary
                        1 -> MaterialTheme.colorScheme.secondary
                        2 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    },
            )
            if (index < rows.lastIndex) {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CategoryDistributionRow(
    row: CategorySummary,
    totalSpent: Long,
    label: String,
    tint: Color,
) {
    val ratio = (row.spentCents.toFloat() / totalSpent.toFloat()).coerceIn(0f, 1f)
    val percentage = (ratio * 100f).toInt()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$percentage%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatBrl(row.spentCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .background(tint.copy(alpha = 0.9f)),
            )
        }
    }
}

@Composable
private fun MonthComparisonCard(
    s: AppStrings,
    state: MainState,
) {
    val comparison = state.comparison ?: return
    PanelCard(title = s.monthComparison, icon = Icons.Default.AutoGraph) {
        Text(
            "${s.comparedTo} ${formatYearMonthHuman(comparison.previousMonth, s.languageCode)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniMetric(s.expenses, formatSigned(comparison.spentDeltaCents))
            MiniMetric(s.billings, formatSigned(comparison.commitmentsDeltaCents))
        }
    }
}


