package com.samluiz.gyst.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.presentation.MainState
import com.samluiz.gyst.presentation.MainStore
import org.koin.compose.koinInject

private enum class Screen { RESUMO, DESPESAS, PLANEJAMENTO, PERFIL }

@Composable
fun GystRoot() {
    val store: MainStore = koinInject()
    val state by store.state.collectAsState()
    val s = rememberStrings(state.language)
    var screen by remember { mutableStateOf(Screen.RESUMO) }

    GystTheme(themeMode = state.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            LaunchedEffect(Unit) { store.bootstrap() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(colors = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        BottomNav(
                            s = s,
                            selected = screen,
                            onSelect = { screen = it },
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Header(
                            s = s,
                            month = state.currentMonth.toString(),
                            onPrev = store::goToPreviousMonth,
                            onNext = store::goToNextMonth,
                            showMonthSelector = screen != Screen.PERFIL,
                        )

                        AnimatedContent(targetState = screen, label = "screen") { current ->
                            when (current) {
                                Screen.RESUMO -> ResumoTab(s, state, onSaveIncome = store::saveIncome, onRollover = store::rolloverToNextMonth)
                                Screen.DESPESAS -> DespesasTab(
                                    s,
                                    state,
                                    onAddExpense = store::addExpense,
                                    onAddCategory = store::addCategory,
                                    onAddSubscription = store::addSubscription,
                                    onAddInstallment = store::addInstallment,
                                )
                                Screen.PLANEJAMENTO -> PlanningTab(s, state)
                                Screen.PERFIL -> ProfileTab(
                                    s,
                                    state,
                                    onSetNoInstallments = store::setNoNewInstallments,
                                    onSetLanguage = store::setLanguage,
                                    onSetTheme = store::setThemeMode,
                                    onSignInGoogle = store::signInGoogle,
                                    onSignOutGoogle = store::signOutGoogle,
                                    onSyncGoogleDrive = store::syncGoogleDrive,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    s: AppStrings,
    month: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    showMonthSelector: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Gyst", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(s.monthlyControl, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (showMonthSelector) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCompactButton(onClick = onPrev, icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "previous")
                    Text(month, style = MaterialTheme.typography.labelMedium)
                    IconCompactButton(onClick = onNext, icon = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "next")
                }
            }
        }
    }
}

@Composable
private fun BottomNav(
    s: AppStrings,
    selected: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = selected == Screen.RESUMO,
            onClick = { onSelect(Screen.RESUMO) },
            icon = { Icon(Icons.Default.AutoGraph, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabSummary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.DESPESAS,
            onClick = { onSelect(Screen.DESPESAS) },
            icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabExpenses, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.PLANEJAMENTO,
            onClick = { onSelect(Screen.PLANEJAMENTO) },
            icon = { Icon(Icons.Default.CreditCardOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabPlanning, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.PERFIL,
            onClick = { onSelect(Screen.PERFIL) },
            icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.profile, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

@Composable
private fun ResumoTab(s: AppStrings, state: MainState, onSaveIncome: (Long) -> Unit, onRollover: () -> Unit) {
    var budgetCentsDigits by remember(state.currentMonth, state.summary?.totalIncomeCents) {
        mutableStateOf((state.summary?.totalIncomeCents ?: 0L).toString())
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            BudgetHero(
                s = s,
                budgetCentsDigits = budgetCentsDigits,
                onBudgetCentsDigits = { budgetCentsDigits = it.ifBlank { "0" } },
                onSaveBudget = { onSaveIncome((budgetCentsDigits.toLongOrNull() ?: 0L).coerceAtLeast(0L)) },
                budget = state.summary?.totalIncomeCents ?: 0L,
                expenses = state.summary?.spentTotalCents ?: 0L,
                billings = state.summary?.commitmentsCents ?: 0L,
            )
        }
        item {
            MonthComparisonCard(s, state)
        }
        item {
            CompactPrimaryButton(s.closeMonth, onClick = onRollover)
        }
    }
}

@Composable
private fun MonthComparisonCard(s: AppStrings, state: MainState) {
    val comparison = state.comparison ?: return
    PanelCard(title = s.monthComparison, icon = Icons.Default.AutoGraph) {
        Text(
            "${s.comparedTo} ${comparison.previousMonth}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniMetric(s.expenses, formatSigned(comparison.spentDeltaCents))
            MiniMetric(s.billings, formatSigned(comparison.commitmentsDeltaCents))
        }
    }
}

@Composable
private fun PlanningTab(s: AppStrings, state: MainState) {
    val forecast = state.forecast
    val topSubscription = state.subscriptions.filter { it.active }.maxByOrNull { it.amountCents }?.amountCents ?: 0L
    var cancelTopSubscription by remember { mutableStateOf(false) }
    var recurringCutPercent by remember { mutableStateOf(10f) }
    var goalAmountCentsDigits by remember { mutableStateOf("1000000") }
    var monthlyContributionCentsDigits by remember { mutableStateOf("50000") }

    val adjusted = forecast.map { month ->
        val recurringCut = (month.recurringCents * (recurringCutPercent / 100f)).toLong()
        val subscriptionCut = if (cancelTopSubscription) topSubscription else 0L
        val adjustedSpend = (month.expectedSpendCents - recurringCut - subscriptionCut).coerceAtLeast(0L)
        val safeAllowance = month.incomeCents - adjustedSpend
        val stressRatio = if (month.incomeCents > 0L) adjustedSpend.toDouble() / month.incomeCents.toDouble() else 1.0
        PlanningPoint(month.yearMonth.toString(), adjustedSpend, safeAllowance, stressRatio)
    }
    val baseFreeTotal = forecast.sumOf { it.expectedFreeBalanceCents }
    val adjustedFreeTotal = adjusted.sumOf { it.safeAllowanceCents }
    val deltaTotal = adjustedFreeTotal - baseFreeTotal
    val averageDelta = if (adjusted.isNotEmpty()) deltaTotal / adjusted.size else 0L

    val installmentEndEvents = state.installments
        .filter { it.active }
        .groupBy { it.endYearMonth.toString() }
        .map { (month, items) -> PlanningEvent(month, items.sumOf { it.monthlyAmountCents }) }
        .sortedBy { it.month }

    val goalAmountCents = goalAmountCentsDigits.toLongOrNull() ?: 0L
    val monthlyContributionCents = monthlyContributionCentsDigits.toLongOrNull() ?: 0L
    val monthsToGoal = if (goalAmountCents > 0L && monthlyContributionCents > 0L) {
        ((goalAmountCents + monthlyContributionCents - 1) / monthlyContributionCents).toInt()
    } else 0
    val projectedGoalMonth = if (monthsToGoal > 0) state.currentMonth.plusMonths(monthsToGoal - 1).toString() else "-"

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.simulator, icon = Icons.Default.AutoGraph) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.cancelTopSubscription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Switch(
                        checked = cancelTopSubscription,
                        onCheckedChange = { cancelTopSubscription = it },
                        modifier = Modifier.scale(0.82f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    )
                }
                Text("${s.recurringCut}: ${recurringCutPercent.toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = recurringCutPercent,
                    onValueChange = { recurringCutPercent = it.coerceIn(0f, 40f) },
                    valueRange = 0f..40f,
                )
            }
        }
        item {
            PanelCard(title = s.scenarioImpact, icon = Icons.Default.AutoGraph) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniMetric(s.projectedFree, formatSigned(averageDelta))
                    MiniMetric("12m", formatSigned(deltaTotal))
                }
            }
        }
        item {
            PanelCard(title = s.stressTimeline, icon = Icons.Default.AutoGraph) {
                adjusted.take(8).forEachIndexed { index, point ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(point.month, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${s.safeAllowance}: ${formatBrl(point.safeAllowanceCents)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            when {
                                point.stressRatio >= 0.75 -> s.riskHigh
                                point.stressRatio >= 0.55 -> s.riskMedium
                                else -> s.riskLow
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                point.stressRatio >= 0.75 -> MaterialTheme.colorScheme.error
                                point.stressRatio >= 0.55 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                    if (index < adjusted.take(8).lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    }
                }
            }
        }
        item {
            PanelCard(title = s.freedCashEvents, icon = Icons.Default.CreditCardOff) {
                if (installmentEndEvents.isEmpty()) {
                    Text(s.noEvents, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    installmentEndEvents.take(6).forEachIndexed { index, event ->
                        ScenarioLine("${s.commitmentEnd}: ${event.month}", event.freedCents)
                        if (index < installmentEndEvents.take(6).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
        item {
            PanelCard(title = s.goalProjection, icon = Icons.Default.AutoGraph) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactMoneyInput(goalAmountCentsDigits, { goalAmountCentsDigits = it.filter(Char::isDigit) }, s.goalAmount, modifier = Modifier.weight(1f))
                    CompactMoneyInput(monthlyContributionCentsDigits, { monthlyContributionCentsDigits = it.filter(Char::isDigit) }, s.monthlyContribution, modifier = Modifier.weight(1f))
                }
                Text(
                    "${s.reachesGoalIn}: ${if (monthsToGoal > 0) "$projectedGoalMonth ($monthsToGoal m)" else "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class PlanningPoint(
    val month: String,
    val spendCents: Long,
    val safeAllowanceCents: Long,
    val stressRatio: Double,
)

private data class PlanningEvent(
    val month: String,
    val freedCents: Long,
)

@Composable
private fun ScenarioLine(label: String, monthlyGainCents: Long) {
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
private fun BudgetHero(
    s: AppStrings,
    budgetCentsDigits: String,
    onBudgetCentsDigits: (String) -> Unit,
    onSaveBudget: () -> Unit,
    budget: Long,
    expenses: Long,
    billings: Long,
) {
    val used = expenses + billings
    val progress = if (budget > 0L) (used.toFloat() / budget.toFloat()).coerceIn(0f, 1f) else 0f
    val remaining = budget - used
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val borderAlpha by animateFloatAsState(targetValue = if (editing) 0.95f else 0.45f, label = "budget-border")

    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.monthlyBudget, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)

            if (!editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        formatBrl(budget),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 2.dp)
                            .clickable { editing = true },
                    )
                    IconCompactButton(onClick = { editing = true }, icon = Icons.Default.Edit, contentDescription = s.editBudget)
                }
            } else {
                BasicTextField(
                    value = formatBrlFromCentsDigits(budgetCentsDigits),
                    onValueChange = { typed -> onBudgetCentsDigits(typed.filter(Char::isDigit)) },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            editing = false
                            hadFocus = false
                            onSaveBudget()
                        },
                    ),
                    modifier = Modifier
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniMetric(s.expenses, formatBrl(expenses))
                MiniMetric(s.billings, formatBrl(billings))
                MiniMetric(s.remaining, formatBrl(remaining))
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DespesasTab(
    s: AppStrings,
    state: MainState,
    onAddExpense: (Long, String, String?, Boolean) -> Unit,
    onAddCategory: (String) -> Unit,
    onAddSubscription: (String, Long, Int, String) -> Unit,
    onAddInstallment: (String, Long, Int, String) -> Unit,
) {
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var showAddSubscriptionDialog by remember { mutableStateOf(false) }
    var showAddInstallmentDialog by remember { mutableStateOf(false) }
    val categoryById = remember(state.categories) { state.categories.associateBy { it.id } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.newExpense, icon = Icons.AutoMirrored.Filled.ReceiptLong) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.noRecords, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CompactPrimaryButton(s.add, onClick = { showAddExpenseDialog = true })
                }
            }
        }

        item {
            PanelCard(title = s.subscriptions, icon = Icons.Default.CreditCardOff) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CompactPrimaryButton(s.add, onClick = { showAddSubscriptionDialog = true })
                }
                if (state.subscriptions.isEmpty()) {
                    Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    state.subscriptions.take(8).forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Dia ${item.billingDay}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatBrl(item.amountCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (index < state.subscriptions.take(8).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }

        item {
            PanelCard(title = s.installments, icon = Icons.Default.CreditCardOff) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CompactPrimaryButton(s.add, onClick = { showAddInstallmentDialog = true })
                }
                if (state.installments.isEmpty()) {
                    Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    state.installments.take(8).forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Ate ${item.endYearMonth}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatBrl(item.monthlyAmountCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (index < state.installments.take(8).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }

        item {
            PanelCard(title = s.history, icon = Icons.Default.AutoGraph) {
                if (state.expenses.isEmpty()) {
                    Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.expenses.take(20).forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.note ?: s.noDescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${item.occurredAt} • ${categoryById[item.categoryId]?.name ?: s.category}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(formatBrl(item.amountCents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (index < state.expenses.take(20).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }

    if (showAddExpenseDialog) {
        var description by remember { mutableStateOf("") }
        var amountCentsDigits by remember { mutableStateOf("") }
        var recurring by remember { mutableStateOf(false) }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        AlertDialog(
            onDismissRequest = { showAddExpenseDialog = false },
            title = { DialogHeaderTitle(s.newExpense, onClose = { showAddExpenseDialog = false }, closeLabel = s.close) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactInput(description, { description = it }, s.description)
                    CompactMoneyInput(amountCentsDigits, { amountCentsDigits = it.filter(Char::isDigit) }, s.amount)
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.monthly, style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = recurring,
                            onCheckedChange = { recurring = it },
                            modifier = Modifier.scale(0.82f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                CompactPrimaryButton(
                    s.save,
                    enabled = selectedCategory != null && amountCentsDigits.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedCategory?.let { onAddExpense(amountCentsDigits.toLongOrNull() ?: 0L, it.id, description.takeIf(String::isNotBlank), recurring) }
                        showAddExpenseDialog = false
                    }
                )
            },
        )
    }

    if (showAddSubscriptionDialog) {
        var description by remember { mutableStateOf("") }
        var amountCentsDigits by remember { mutableStateOf("") }
        var day by remember { mutableStateOf("10") }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        AlertDialog(
            onDismissRequest = { showAddSubscriptionDialog = false },
            title = { DialogHeaderTitle(s.subscriptions, onClose = { showAddSubscriptionDialog = false }, closeLabel = s.close) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactInput(description, { description = it }, s.description)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactMoneyInput(amountCentsDigits, { amountCentsDigits = it.filter(Char::isDigit) }, "R$", modifier = Modifier.weight(1f))
                        CompactInput(day, { day = it.filter(Char::isDigit) }, "Dia", modifier = Modifier.width(70.dp))
                    }
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                    )
                }
            },
            confirmButton = {
                CompactPrimaryButton(
                    s.add,
                    enabled = selectedCategory != null && description.isNotBlank() && amountCentsDigits.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedCategory?.let { onAddSubscription(description, amountCentsDigits.toLongOrNull() ?: 0L, day.toIntOrNull() ?: 1, it.id) }
                        showAddSubscriptionDialog = false
                    }
                )
            },
        )
    }

    if (showAddInstallmentDialog) {
        var description by remember { mutableStateOf("") }
        var amountCentsDigits by remember { mutableStateOf("") }
        var total by remember { mutableStateOf("12") }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        AlertDialog(
            onDismissRequest = { showAddInstallmentDialog = false },
            title = { DialogHeaderTitle(s.installments, onClose = { showAddInstallmentDialog = false }, closeLabel = s.close) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactInput(description, { description = it }, s.description)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactMoneyInput(amountCentsDigits, { amountCentsDigits = it.filter(Char::isDigit) }, "R$/mes", modifier = Modifier.weight(1f))
                        CompactInput(total, { total = it.filter(Char::isDigit) }, "#", modifier = Modifier.width(70.dp))
                    }
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                    )
                }
            },
            confirmButton = {
                CompactPrimaryButton(
                    s.add,
                    enabled = selectedCategory != null && description.isNotBlank() && amountCentsDigits.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedCategory?.let { onAddInstallment(description, amountCentsDigits.toLongOrNull() ?: 0L, total.toIntOrNull() ?: 1, it.id) }
                        showAddInstallmentDialog = false
                    }
                )
            },
        )
    }
}

@Composable
private fun CategoryPicker(
    s: AppStrings,
    categories: List<com.samluiz.gyst.domain.model.Category>,
    selectedCategoryId: String?,
    onSelectCategory: (String?) -> Unit,
    onAddCategory: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            CompactPrimaryButton(
                text = selectedCategory?.name ?: s.selectCategory,
                enabled = categories.isNotEmpty(),
                onClick = { menuExpanded = true },
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onSelectCategory(category.id)
                            menuExpanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ ${s.newCategory}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menuExpanded = false
                        showNewCategoryDialog = true
                    }
                )
            }
        }
    }

    if (showNewCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showNewCategoryDialog = false },
            title = { DialogHeaderTitle(s.newCategory, onClose = { showNewCategoryDialog = false }, closeLabel = s.close) },
            text = { CompactInput(value = newCategoryName, onValueChange = { newCategoryName = it }, label = s.newCategory) },
            confirmButton = {
                CompactPrimaryButton(
                    s.add,
                    enabled = newCategoryName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onAddCategory(newCategoryName)
                        newCategoryName = ""
                        showNewCategoryDialog = false
                    }
                )
            },
        )
    }
}

@Composable
private fun DialogHeaderTitle(title: String, onClose: () -> Unit, closeLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconCompactButton(
            onClick = onClose,
            icon = Icons.Default.Close,
            contentDescription = closeLabel,
        )
    }
}

@Composable
private fun ProfileTab(
    s: AppStrings,
    state: MainState,
    onSetNoInstallments: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (String) -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
) {
    val noCard = state.safetyGuard?.noNewInstallments ?: true

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.profile, icon = Icons.Default.Person) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.blockInstallments,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = noCard, onCheckedChange = onSetNoInstallments, modifier = Modifier.scale(0.82f))
                }
            }
        }
        item {
            PanelCard(title = s.language, icon = Icons.Default.Settings) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = state.language == "system", onClick = { onSetLanguage("system") }, label = { Text(s.system) })
                    FilterChip(selected = state.language == "pt", onClick = { onSetLanguage("pt") }, label = { Text("PT") })
                    FilterChip(selected = state.language == "en", onClick = { onSetLanguage("en") }, label = { Text("EN") })
                }
            }
        }
        item {
            PanelCard(title = s.theme, icon = Icons.Default.Settings) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = state.themeMode == "system", onClick = { onSetTheme("system") }, label = { Text(s.system) })
                    FilterChip(selected = state.themeMode == "light", onClick = { onSetTheme("light") }, label = { Text(s.light) })
                    FilterChip(selected = state.themeMode == "dark", onClick = { onSetTheme("dark") }, label = { Text(s.dark) })
                }
            }
        }
        item {
            PanelCard(title = "Google", icon = Icons.Default.Person) {
                val google = state.googleSync
                if (!google.isAvailable) {
                    Text(s.syncUnavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        google.accountEmail ?: s.loginGoogle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (google.lastSyncAtIso != null) {
                        Text(
                            "${s.syncedAt}: ${google.lastSyncAtIso}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (google.lastError != null) {
                        Text(
                            google.lastError,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!google.isSignedIn) {
                            CompactPrimaryButton(s.loginGoogle, modifier = Modifier.weight(1f), onClick = onSignInGoogle)
                        } else {
                            CompactPrimaryButton(
                                s.syncDrive,
                                enabled = !google.isSyncing,
                                modifier = Modifier.weight(1f),
                                onClick = onSyncGoogleDrive,
                            )
                            CompactPrimaryButton(s.logoutGoogle, modifier = Modifier.weight(1f), onClick = onSignOutGoogle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp)) }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
private fun CompactInput(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .heightIn(min = 40.dp)
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
                    RoundedCornerShape(9.dp)
                )
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    RoundedCornerShape(9.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CompactMoneyInput(
    centsDigits: String,
    onCentsDigitsChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f))
        BasicTextField(
            value = formatBrlFromCentsDigits(centsDigits),
            onValueChange = { typed -> onCentsDigitsChange(typed.filter(Char::isDigit)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .heightIn(min = 40.dp)
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
                    RoundedCornerShape(9.dp)
                )
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    RoundedCornerShape(9.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CompactPrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconCompactButton(onClick: () -> Unit, icon: ImageVector, contentDescription: String, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
    }
}

private fun formatBrl(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    val reais = abs / 100
    val cent = abs % 100
    val signal = if (cents < 0) "-" else ""
    return "$signal R$ $reais,${pad2(cent)}"
}

private fun formatBrlFromCentsDigits(centsDigits: String): String {
    val digits = centsDigits.filter(Char::isDigit).ifBlank { "0" }
    val cents = digits.toLongOrNull() ?: 0L
    val reais = cents / 100
    val cent = cents % 100
    val grouped = reais.toString().reversed().chunked(3).joinToString(".").reversed()
    return "R$ $grouped,${pad2(cent)}"
}

private fun formatSigned(cents: Long): String {
    val prefix = if (cents > 0) "+" else ""
    return "$prefix${formatBrl(cents)}"
}

private fun pad2(value: Long): String {
    return if (value < 10L) "0$value" else value.toString()
}
