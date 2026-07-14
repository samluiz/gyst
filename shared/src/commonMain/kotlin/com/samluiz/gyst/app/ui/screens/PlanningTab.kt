package com.samluiz.gyst.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.presentation.MainState

@Composable
internal fun PlanningTab(
    s: AppStrings,
    state: MainState,
    onConfigureAdvisor: (String, String, AdvisorApiFormat, String?) -> Unit,
    onAskAdvisor: (String) -> Unit,
    onEnsureAdvisorOverview: (Boolean) -> Unit,
    onClearAdvisor: () -> Unit,
    onDisconnectAdvisor: () -> Unit,
) {
    var selectedView by remember { mutableStateOf(0) }
    val labels = listOf(s.planningFuture, s.planningAdvisor)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                AppToggleChip(selected = selectedView == index, onClick = { selectedView = index }, text = label)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (selectedView == 0) {
                FuturePlanningTab(s, state)
            } else {
                AdvisorPlanningContent(
                    s = s,
                    state = state,
                    onConfigure = onConfigureAdvisor,
                    onAsk = onAskAdvisor,
                    onEnsureOverview = onEnsureAdvisorOverview,
                    onClear = onClearAdvisor,
                    onDisconnect = onDisconnectAdvisor,
                )
            }
        }
    }
}

@Composable
private fun FuturePlanningTab(
    s: AppStrings,
    state: MainState,
) {
    var showFreedCashDialog by remember { mutableStateOf(false) }
    val summary = state.summary
    val budgetCents = summary?.totalIncomeCents ?: 0L
    val expensesCents = summary?.spentTotalCents ?: 0L
    val commitmentsCents = summary?.commitmentsCents ?: 0L
    val committedCents = expensesCents + commitmentsCents
    val remainingCents = summary?.remainingTotalCents ?: 0L
    val pressureProgress = if (budgetCents > 0L) (committedCents.toFloat() / budgetCents.toFloat()).coerceIn(0f, 1f) else 0f
    val pressureLabel =
        when {
            budgetCents <= 0L -> s.noRecords
            pressureProgress >= 0.9f -> s.riskHigh
            pressureProgress >= 0.7f -> s.riskMedium
            else -> s.riskLow
        }

    val installmentEndEvents =
        state.installments
            .filter { it.active }
            .groupBy { it.endYearMonth }
            .map { (month, items) -> PlanningEvent(month, items.sumOf { it.monthlyAmountCents }) }
            .sortedBy { it.month.year * 100 + it.month.month }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.monthlyControl, icon = Icons.Default.Savings) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MiniMetric(s.monthlyBudget, formatBrl(budgetCents))
                    MiniMetric(s.projectedSpend, formatBrl(committedCents))
                    MiniMetric(s.remaining, formatBrl(remainingCents))
                }
                LinearProgressIndicator(
                    progress = { pressureProgress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                )
                Text(
                    pressureLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        when {
                            pressureProgress >= 0.9f -> MaterialTheme.colorScheme.error
                            pressureProgress >= 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        },
                )
            }
        }
        item {
            PanelCard(title = s.longTermForecast, icon = Icons.Default.AutoGraph) {
                if (state.forecast.isEmpty()) {
                    Text(s.noRecords, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.forecast.take(6).forEachIndexed { index, month ->
                        ForecastLine(
                            month = formatYearMonthHuman(month.yearMonth, s.languageCode),
                            commitmentsCents = month.commitmentsCents,
                            freeCents = month.expectedFreeBalanceCents,
                            s = s,
                        )
                        if (index < state.forecast.take(6).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
        item {
            PanelCard(
                title = s.freedCashEvents,
                icon = Icons.Default.CreditCardOff,
                headerTrailing = {
                    IconCompactButton(
                        onClick = { showFreedCashDialog = true },
                        icon = Icons.Default.Info,
                        contentDescription = s.freedCashHowItWorks,
                        compact = true,
                        subtle = true,
                    )
                },
            ) {
                if (installmentEndEvents.isEmpty()) {
                    Text(s.noEvents, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    installmentEndEvents.take(6).forEachIndexed { index, event ->
                        ScenarioLine("${s.commitmentEnd}: ${formatYearMonthHuman(event.month, s.languageCode)}", event.freedCents)
                        if (index < installmentEndEvents.take(6).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }

    if (showFreedCashDialog) {
        AppDialog(
            title = s.freedCashHowItWorks,
            onClose = { showFreedCashDialog = false },
            closeLabel = s.close,
            onDismissRequest = { showFreedCashDialog = false },
            maxWidth = 460.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp),
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.freedCashExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class PlanningEvent(
    val month: com.samluiz.gyst.domain.model.YearMonth,
    val freedCents: Long,
)

@Composable
private fun ForecastLine(
    month: String,
    commitmentsCents: Long,
    freeCents: Long,
    s: AppStrings,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                month,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${s.billings}: ${formatBrl(commitmentsCents)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatBrl(freeCents),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (freeCents < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ScenarioLine(
    label: String,
    monthlyGainCents: Long,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("+ ${formatBrl(monthlyGainCents)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun BudgetHero(
    s: AppStrings,
    budgetCentsDigits: String,
    onBudgetCentsDigits: (String) -> Unit,
    onSaveBudget: () -> Unit,
    budget: Long,
    expenses: Long,
    billings: Long,
    applyForward: Boolean,
    onApplyForwardChange: (Boolean) -> Unit,
    showApplyForward: Boolean,
) {
    val used = expenses + billings
    val progress = if (budget > 0L) (used.toFloat() / budget.toFloat()).coerceIn(0f, 1f) else 0f
    val remaining = budget - used
    val focusRequester = remember { FocusRequester() }
    var budgetField by remember {
        mutableStateOf(
            TextFieldValue(
                text = formatBrlFromCentsDigits(budgetCentsDigits),
                selection = TextRange(formatBrlFromCentsDigits(budgetCentsDigits).length),
            ),
        )
    }
    var hadFocus by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val borderAlpha by animateFloatAsState(targetValue = if (editing) 0.95f else 0.45f, label = "budget-border")

    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    LaunchedEffect(budgetCentsDigits, editing) {
        if (!editing) return@LaunchedEffect
        val formatted = formatBrlFromCentsDigits(budgetCentsDigits)
        budgetField =
            TextFieldValue(
                text = formatted,
                selection = TextRange(formatted.length),
            )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.18f),
                ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha * 0.6f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.monthlyBudget, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)

            if (!editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatBrl(budget),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp)
                                .clickable { editing = true },
                    )
                    IconCompactButton(onClick = { editing = true }, icon = Icons.Default.Edit, contentDescription = s.editBudget)
                }
            } else {
                BasicTextField(
                    value = budgetField,
                    onValueChange = { typed ->
                        val digits = typed.text.filter(Char::isDigit)
                        onBudgetCentsDigits(digits)
                        val formatted = formatBrlFromCentsDigits(digits)
                        budgetField =
                            TextFieldValue(
                                text = formatted,
                                selection = TextRange(formatted.length),
                            )
                    },
                    textStyle =
                        MaterialTheme.typography.headlineMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                editing = false
                                hadFocus = false
                                onSaveBudget()
                            },
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                if (it.isFocused) hadFocus = true
                                if (!it.isFocused && hadFocus) {
                                    hadFocus = false
                                    editing = false
                                    onSaveBudget()
                                }
                            },
                )
            }

            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp))
            if (showApplyForward) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        s.applyToNextMonths,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = applyForward,
                        onCheckedChange = onApplyForwardChange,
                        modifier = Modifier.scale(0.82f),
                        colors = appSwitchColors(),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniMetric(s.expenses, formatBrl(expenses))
                MiniMetric(s.billings, formatBrl(billings))
                MiniMetric(s.remaining, formatBrl(remaining))
            }
        }
    }
}

@Composable
internal fun MiniMetric(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

internal enum class ExpensesSection { DESPESAS, ASSINATURAS, PARCELAMENTOS }
