package com.samluiz.gyst.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gyst.shared.generated.resources.OpenspaceBlack
import gyst.shared.generated.resources.Res
import com.samluiz.gyst.domain.model.RecurrenceType
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.service.SyncPolicy
import com.samluiz.gyst.domain.service.SyncSource
import com.samluiz.gyst.presentation.MainState
import com.samluiz.gyst.presentation.MainStore
import com.samluiz.gyst.domain.service.GoogleSyncState
import gyst.shared.generated.resources.OpenspaceLine
import org.jetbrains.compose.resources.Font
import org.koin.compose.koinInject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

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
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
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
                            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.errorMessage?.let { error ->
                            PanelCard(title = "Erro", icon = Icons.Default.CreditCardOff) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconCompactButton(
                                        onClick = store::clearError,
                                        icon = Icons.Default.Close,
                                        contentDescription = s.close,
                                        compact = true,
                                    )
                                }
                            }
                        }

                        Header(
                            s = s,
                            month = capitalizeFirst(formatYearMonthHuman(state.currentMonth, s.languageCode)),
                            onPrev = store::goToPreviousMonth,
                            onNext = store::goToNextMonth,
                            showMonthSelector = screen != Screen.PERFIL,
                        )

                        AnimatedContent(targetState = screen to state.isLoading, label = "screen") { (current, loading) ->
                            if (loading) {
                                ScreenSkeleton(current = current)
                            } else {
                                when (current) {
                                    Screen.RESUMO -> ResumoTab(
                                        s = s,
                                        state = state,
                                        onSaveIncome = store::saveIncome,
                                        onRollover = store::rolloverToNextMonth,
                                    )
                                Screen.DESPESAS -> DespesasTab(
                                    s,
                                    state,
                                    onLoadMoreExpenses = store::loadMoreExpenses,
                                    onAddExpense = store::addExpense,
                                    onAddCategory = store::addCategory,
                                    onAddSubscription = store::addSubscription,
                                        onAddInstallment = store::addInstallment,
                                        onUpdateExpense = store::updateExpense,
                                        onDeleteExpense = store::deleteExpense,
                                        onUpdateSubscription = store::updateSubscription,
                                        onDeleteSubscription = store::deleteSubscription,
                                        onUpdateInstallment = store::updateInstallment,
                                        onDeleteInstallment = store::deleteInstallment,
                                        onDuplicateExpense = store::duplicateExpense,
                                        onDuplicateSubscription = store::duplicateSubscription,
                                        onDuplicateInstallment = store::duplicateInstallment,
                                    )
                                    Screen.PLANEJAMENTO -> PlanningTab(
                                        s = s,
                                        state = state,
                                        onSetUsePostSavingsBudget = store::setPlanningUsePostSavingsBudget,
                                        onSetMonthlyContribution = store::setPlanningMonthlyContribution,
                                    )
                                    Screen.PERFIL -> ProfileTab(
                                        s,
                                        state,
                                        onSetLanguage = store::setLanguage,
                                        onSetTheme = store::setThemeMode,
                                        onSignInGoogle = store::signInGoogle,
                                        onSignOutGoogle = store::signOutGoogle,
                                        onSyncGoogleDrive = store::syncGoogleDrive,
                                        onRestoreGoogleDrive = store::restoreFromGoogleDrive,
                                        onResetLocalData = store::resetLocalData,
                                    )
                                }
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
    val logoFont = FontFamily(Font(Res.font.OpenspaceLine))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "GYST",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = logoFont),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.alpha(if (showMonthSelector) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCompactButton(
                    onClick = { if (showMonthSelector) onPrev() },
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "previous",
                    enabled = showMonthSelector,
                    compact = true,
                )
                Text(month, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 2.dp))
                IconCompactButton(
                    onClick = { if (showMonthSelector) onNext() },
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "next",
                    enabled = showMonthSelector,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ScreenSkeleton(current: Screen) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PanelCard(
                title = when (current) {
                    Screen.RESUMO -> " "
                    Screen.DESPESAS -> " "
                    Screen.PLANEJAMENTO -> " "
                    Screen.PERFIL -> " "
                }
            ) {
                SkeletonLine(width = 0.42f)
                SkeletonLine(width = 0.78f)
                SkeletonLine(width = 0.64f)
            }
        }
        item {
            PanelCard(title = " ") {
                SkeletonLine(width = 0.92f)
                SkeletonLine(width = 0.86f)
                SkeletonLine(width = 0.73f)
                SkeletonLine(width = 0.55f)
            }
        }
        item {
            PanelCard(title = " ") {
                SkeletonLine(width = 0.88f)
                SkeletonLine(width = 0.60f)
                SkeletonLine(width = 0.82f)
            }
        }
    }
}

@Composable
private fun SkeletonLine(width: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width.coerceIn(0.15f, 1f))
            .height(12.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                RoundedCornerShape(7.dp),
            )
    )
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
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Black.copy(alpha = 0.16f),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = selected == Screen.RESUMO,
            onClick = { onSelect(Screen.RESUMO) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            icon = { Icon(Icons.Default.AutoGraph, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabSummary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.DESPESAS,
            onClick = { onSelect(Screen.DESPESAS) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabExpenses, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.PLANEJAMENTO,
            onClick = { onSelect(Screen.PLANEJAMENTO) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            icon = { Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.tabPlanning, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        NavigationBarItem(
            selected = selected == Screen.PERFIL,
            onClick = { onSelect(Screen.PERFIL) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(s.profile, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

@Composable
private fun ResumoTab(
    s: AppStrings,
    state: MainState,
    onSaveIncome: (Long, Boolean) -> Unit,
    onRollover: () -> Unit,
) {
    var budgetCentsDigits by remember(state.currentMonth, state.summary?.totalIncomeCents) {
        mutableStateOf((state.summary?.totalIncomeCents ?: 0L).toString())
    }
    var applyForward by rememberSaveable(state.currentMonth) { mutableStateOf(false) }
    val budget = state.summary?.totalIncomeCents ?: 0L
    val postSavingsBudget = if (state.planningUsePostSavingsBudget) {
        (budget - state.planningMonthlyContributionCents).coerceAtLeast(0L)
    } else {
        null
    }

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
                postSavingsBudget = postSavingsBudget,
            )
        }
        item {
            MonthComparisonCard(s, state)
        }
        item {
            CompactPrimaryButton(
                text = s.closeMonth,
                compact = true,
                squared = true,
                subtle = true,
                onClick = onRollover,
            )
        }
    }
}

@Composable
private fun MonthComparisonCard(s: AppStrings, state: MainState) {
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

@Composable
private fun PlanningTab(
    s: AppStrings,
    state: MainState,
    onSetUsePostSavingsBudget: (Boolean) -> Unit,
    onSetMonthlyContribution: (Long) -> Unit,
) {
    val forecast = state.forecast
    val topSubscription = state.subscriptions.filter { it.active }.maxByOrNull { it.amountCents }?.amountCents ?: 0L
    var cancelTopSubscription by remember { mutableStateOf(false) }
    var recurringCutPercent by remember { mutableStateOf(10f) }
    var goalAmountCentsDigits by remember { mutableStateOf("1000000") }
    var monthlyContributionCentsDigits by remember(state.planningMonthlyContributionCents) {
        mutableStateOf(state.planningMonthlyContributionCents.toString())
    }
    var desiredMarginCentsDigits by remember { mutableStateOf("0") }
    var useAfterSavingsBudget by remember(state.planningUsePostSavingsBudget) {
        mutableStateOf(state.planningUsePostSavingsBudget)
    }

    val monthlyContributionCents = monthlyContributionCentsDigits.toLongOrNull() ?: 0L
    val desiredMarginCents = desiredMarginCentsDigits.toLongOrNull() ?: 0L

    val adjusted = forecast.map { month ->
        val recurringCut = (month.recurringCents * (recurringCutPercent / 100f)).toLong()
        val subscriptionCut = if (cancelTopSubscription) topSubscription else 0L
        val adjustedSpend = (month.expectedSpendCents - recurringCut - subscriptionCut).coerceAtLeast(0L)
        val projectedIncome = if (useAfterSavingsBudget) {
            (month.incomeCents - monthlyContributionCents).coerceAtLeast(0L)
        } else {
            month.incomeCents
        }
        val safeAllowance = projectedIncome - adjustedSpend
        PlanningPoint(month.yearMonth, adjustedSpend, safeAllowance)
    }
    val baseFreeTotal = forecast.sumOf { it.expectedFreeBalanceCents }
    val adjustedFreeTotal = adjusted.sumOf { it.safeAllowanceCents }
    val deltaTotal = adjustedFreeTotal - baseFreeTotal
    val averageDelta = if (adjusted.isNotEmpty()) deltaTotal / adjusted.size else 0L

    val installmentEndEvents = state.installments
        .filter { it.active }
        .groupBy { it.endYearMonth }
        .map { (month, items) -> PlanningEvent(month, items.sumOf { it.monthlyAmountCents }) }
        .sortedBy { it.month.year * 100 + it.month.month }

    val goalAmountCents = goalAmountCentsDigits.toLongOrNull() ?: 0L
    val monthsToGoal = if (goalAmountCents > 0L && monthlyContributionCents > 0L) {
        ((goalAmountCents + monthlyContributionCents - 1) / monthlyContributionCents).toInt()
    } else 0
    val projectedGoalMonth = if (monthsToGoal > 0) {
        formatYearMonthHuman(state.currentMonth.plusMonths(monthsToGoal - 1), s.languageCode)
    } else "-"

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.simulator, icon = Icons.Default.AutoGraph) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.cancelTopSubscription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Switch(
                        checked = cancelTopSubscription,
                        onCheckedChange = { cancelTopSubscription = it },
                        modifier = Modifier.scale(0.82f),
                        colors = appSwitchColors(),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.predictUsingBudgetAfterGoal,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = useAfterSavingsBudget,
                        onCheckedChange = {
                            useAfterSavingsBudget = it
                            onSetUsePostSavingsBudget(it)
                        },
                        modifier = Modifier.scale(0.82f),
                        colors = appSwitchColors(),
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
            PanelCard(title = s.marginGoalProjection, icon = Icons.Default.Savings) {
                CompactMoneyInput(
                    desiredMarginCentsDigits,
                    { desiredMarginCentsDigits = it.filter(Char::isDigit) },
                    s.desiredMonthlyMargin,
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
                            Text(
                                capitalizeFirst(formatYearMonthHuman(point.month, s.languageCode)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("${s.safeAllowance}: ${formatBrl(point.safeAllowanceCents)}", style = MaterialTheme.typography.bodySmall)
                        }
                        val deltaToTarget = point.safeAllowanceCents - desiredMarginCents
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (deltaToTarget >= 0) s.aboveDesiredMargin else s.belowDesiredMargin,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (deltaToTarget >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "${s.marginDelta}: ${formatSigned(deltaToTarget)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        ScenarioLine("${s.commitmentEnd}: ${formatYearMonthHuman(event.month, s.languageCode)}", event.freedCents)
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
                    CompactMoneyInput(
                        monthlyContributionCentsDigits,
                        {
                            val digits = it.filter(Char::isDigit)
                            monthlyContributionCentsDigits = digits
                            onSetMonthlyContribution(digits.toLongOrNull() ?: 0L)
                        },
                        s.monthlyContribution,
                        modifier = Modifier.weight(1f),
                    )
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
    val month: com.samluiz.gyst.domain.model.YearMonth,
    val spendCents: Long,
    val safeAllowanceCents: Long,
)

private data class PlanningEvent(
    val month: com.samluiz.gyst.domain.model.YearMonth,
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
    applyForward: Boolean,
    onApplyForwardChange: (Boolean) -> Unit,
    showApplyForward: Boolean,
    postSavingsBudget: Long?,
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
            )
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
        budgetField = TextFieldValue(
            text = formatted,
            selection = TextRange(formatted.length),
        )
    }

    Card(
        modifier = Modifier
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 2.dp)
                            .clickable { editing = true },
                    )
                    IconCompactButton(onClick = { editing = true }, icon = Icons.Default.Edit, contentDescription = s.editBudget)
                }
                postSavingsBudget?.let { projected ->
                    Text(
                        "${s.postSavingsBudget}: ${formatBrl(projected)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BasicTextField(
                    value = budgetField,
                    onValueChange = { typed ->
                        val digits = typed.text.filter(Char::isDigit)
                        onBudgetCentsDigits(digits)
                        val formatted = formatBrlFromCentsDigits(digits)
                        budgetField = TextFieldValue(
                            text = formatted,
                            selection = TextRange(formatted.length),
                        )
                    },
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
private fun MiniMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

private enum class ExpensesSection { DESPESAS, ASSINATURAS, PARCELAMENTOS }

@Composable
private fun DespesasTab(
    s: AppStrings,
    state: MainState,
    onLoadMoreExpenses: () -> Unit,
    onAddExpense: (Long, String, String?, Boolean) -> Unit,
    onAddCategory: (String, ((String) -> Unit)?) -> Unit,
    onAddSubscription: (String, Long, Int, String) -> Unit,
    onAddInstallment: (String, Long, Int, String) -> Unit,
    onUpdateExpense: (String, Long, String, String?, Boolean) -> Unit,
    onDeleteExpense: (String) -> Unit,
    onUpdateSubscription: (String, String, Long, Int, String) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onUpdateInstallment: (String, String, Long, Int, String) -> Unit,
    onDeleteInstallment: (String) -> Unit,
    onDuplicateExpense: (String) -> Unit,
    onDuplicateSubscription: (String) -> Unit,
    onDuplicateInstallment: (String) -> Unit,
) {
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var showAddSubscriptionDialog by remember { mutableStateOf(false) }
    var showAddInstallmentDialog by remember { mutableStateOf(false) }
    var editingExpenseId by remember { mutableStateOf<String?>(null) }
    var editingSubscriptionId by remember { mutableStateOf<String?>(null) }
    var editingInstallmentId by remember { mutableStateOf<String?>(null) }
    val categoryById = remember(state.categories) { state.categories.associateBy { it.id } }
    val visibleSubscriptions = remember(state.currentMonth, state.subscriptions) {
        state.subscriptions.filter { YearMonth.fromDate(it.nextDueDate) <= state.currentMonth }
    }
    val visibleInstallments = remember(state.currentMonth, state.installments) {
        state.installments.filter { it.startYearMonth <= state.currentMonth && it.endYearMonth >= state.currentMonth }
    }
    val sections = remember { ExpensesSection.entries.toList() }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { sections.size })
    val section = sections[pagerState.currentPage]
    var rowMenuId by remember { mutableStateOf<String?>(null) }
    var subscriptionsVisibleCount by remember { mutableStateOf(30) }
    var installmentsVisibleCount by remember { mutableStateOf(30) }
    val expensesListState = rememberLazyListState()
    val subscriptionsListState = rememberLazyListState()
    val installmentsListState = rememberLazyListState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            val title = when (sections[pagerState.currentPage]) {
                ExpensesSection.DESPESAS -> s.expenses
                ExpensesSection.ASSINATURAS -> s.subscriptions
                ExpensesSection.PARCELAMENTOS -> s.installments
            }
            val icon = when (sections[pagerState.currentPage]) {
                ExpensesSection.DESPESAS -> Icons.AutoMirrored.Filled.ReceiptLong
                ExpensesSection.ASSINATURAS -> Icons.Default.Subscriptions
                ExpensesSection.PARCELAMENTOS -> Icons.Default.CalendarMonth
            }
            PanelCard(
                title = title,
                icon = icon,
                truncateTitle = false,
                autoShrinkTitle = true,
                headerCenter = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        sections.forEachIndexed { index, _ ->
                            val selectedDot = index == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(if (selectedDot) 7.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedDot) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        }
                                    ),
                            )
                        }
                    }
                },
                headerTrailing = {
                    CompactPrimaryButton(
                        text = s.add,
                        compact = true,
                        squared = true,
                        subtle = true,
                        onClick = {
                            when (section) {
                                ExpensesSection.DESPESAS -> showAddExpenseDialog = true
                                ExpensesSection.ASSINATURAS -> showAddSubscriptionDialog = true
                                ExpensesSection.PARCELAMENTOS -> showAddInstallmentDialog = true
                            }
                        }
                    )
                },
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 420.dp),
                ) { page ->
                    when (sections[page]) {
                        ExpensesSection.DESPESAS -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), state = expensesListState) {
                                if (state.expenses.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                        }
                                    }
                                } else {
                                    itemsIndexed(state.expenses) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(item.note ?: s.noDescription, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    "${formatLocalDateHuman(item.occurredAt, s.languageCode)} • ${categoryById[item.categoryId]?.name ?: s.category}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(formatBrl(item.amountCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Box {
                                                IconCompactButton(
                                                    onClick = { rowMenuId = if (rowMenuId == item.id) null else item.id },
                                                    icon = Icons.Default.MoreVert,
                                                    contentDescription = s.settings,
                                                    compact = true,
                                                    subtle = true,
                                                )
                                                DropdownMenu(expanded = rowMenuId == item.id, onDismissRequest = { rowMenuId = null }) {
                                                    DropdownMenuItem(
                                                        text = { Text(s.edit) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            editingExpenseId = item.id
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateExpense(item.id)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteExpense(item.id)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        if (index < state.expenses.lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                        }
                                    }
                                    if (state.hasMoreExpenses) {
                                        item {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                                CompactPrimaryButton(
                                                    text = s.loadMore,
                                                    compact = true,
                                                    subtle = true,
                                                    squared = true,
                                                    loading = state.isLoadingMoreExpenses,
                                                    enabled = !state.isLoadingMoreExpenses,
                                                    onClick = onLoadMoreExpenses,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ExpensesSection.ASSINATURAS -> {
                            val subscriptionItems = visibleSubscriptions.take(subscriptionsVisibleCount)
                            LazyColumn(modifier = Modifier.fillMaxSize(), state = subscriptionsListState) {
                                if (subscriptionItems.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                        }
                                    }
                                } else {
                                    itemsIndexed(subscriptionItems) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("Dia ${item.billingDay}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(formatBrl(item.amountCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Box {
                                                IconCompactButton(
                                                    onClick = { rowMenuId = if (rowMenuId == item.id) null else item.id },
                                                    icon = Icons.Default.MoreVert,
                                                    contentDescription = s.settings,
                                                    compact = true,
                                                    subtle = true,
                                                )
                                                DropdownMenu(expanded = rowMenuId == item.id, onDismissRequest = { rowMenuId = null }) {
                                                    DropdownMenuItem(
                                                        text = { Text(s.edit) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            editingSubscriptionId = item.id
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateSubscription(item.id)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteSubscription(item.id)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        if (index < subscriptionItems.lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                        }
                                    }
                                    if (visibleSubscriptions.size > subscriptionsVisibleCount) {
                                        item {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                                CompactPrimaryButton(
                                                    text = s.loadMore,
                                                    compact = true,
                                                    subtle = true,
                                                    squared = true,
                                                    onClick = { subscriptionsVisibleCount += 30 },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ExpensesSection.PARCELAMENTOS -> {
                            val installmentItems = visibleInstallments.take(installmentsVisibleCount)
                            LazyColumn(modifier = Modifier.fillMaxSize(), state = installmentsListState) {
                                if (installmentItems.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(s.noRecords, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                        }
                                    }
                                } else {
                                    itemsIndexed(installmentItems) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    "${formatYearMonthHuman(item.startYearMonth, s.languageCode)} -> ${formatYearMonthHuman(item.endYearMonth, s.languageCode)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(formatBrl(item.monthlyAmountCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Box {
                                                IconCompactButton(
                                                    onClick = { rowMenuId = if (rowMenuId == item.id) null else item.id },
                                                    icon = Icons.Default.MoreVert,
                                                    contentDescription = s.settings,
                                                    compact = true,
                                                    subtle = true,
                                                )
                                                DropdownMenu(expanded = rowMenuId == item.id, onDismissRequest = { rowMenuId = null }) {
                                                    DropdownMenuItem(
                                                        text = { Text(s.edit) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            editingInstallmentId = item.id
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateInstallment(item.id)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteInstallment(item.id)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        if (index < installmentItems.lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                        }
                                    }
                                    if (visibleInstallments.size > installmentsVisibleCount) {
                                        item {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                                CompactPrimaryButton(
                                                    text = s.loadMore,
                                                    compact = true,
                                                    subtle = true,
                                                    squared = true,
                                                    onClick = { installmentsVisibleCount += 30 },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
        var attemptedSave by remember { mutableStateOf(false) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        val amountCents = amountCentsDigits.toLongOrNull() ?: 0L
        val amountInvalid = amountCents <= 0L
        val categoryInvalid = selectedCategory == null
        val canSave = !amountInvalid && !categoryInvalid
        AppDialog(
            title = s.newExpense,
            onClose = { showAddExpenseDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { showAddExpenseDialog = false },
        ) {
                    CompactInput(description, { description = it }, s.description)
                    CompactMoneyInput(
                        amountCentsDigits,
                        { amountCentsDigits = it.filter(Char::isDigit) },
                        s.amount,
                        isError = attemptedSave && amountInvalid,
                    )
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                        isError = attemptedSave && categoryInvalid,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.monthly, style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = recurring,
                            onCheckedChange = { recurring = it },
                            modifier = Modifier.scale(0.82f),
                            colors = appSwitchColors(),
                        )
                    }
                    CompactPrimaryButton(
                        s.save,
                        enabled = canSave,
                        compact = true,
                        squared = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            attemptedSave = true
                            if (!canSave) return@CompactPrimaryButton
                            val category = selectedCategory
                            onAddExpense(amountCents, category.id, description.takeIf(String::isNotBlank), recurring)
                            showAddExpenseDialog = false
                        }
                    )
        }
    }

    if (showAddSubscriptionDialog) {
        var description by remember { mutableStateOf("") }
        var amountCentsDigits by remember { mutableStateOf("") }
        var day by remember { mutableStateOf("10") }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        var attemptedSave by remember { mutableStateOf(false) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        val amountCents = amountCentsDigits.toLongOrNull() ?: 0L
        val dayInt = day.toIntOrNull() ?: 0
        val descriptionInvalid = description.isBlank()
        val amountInvalid = amountCents <= 0L
        val dayInvalid = dayInt !in 1..31
        val categoryInvalid = selectedCategory == null
        val canSave = !descriptionInvalid && !amountInvalid && !dayInvalid && !categoryInvalid
        AppDialog(
            title = s.subscriptions,
            onClose = { showAddSubscriptionDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { showAddSubscriptionDialog = false },
        ) {
                    CompactInput(description, { description = it }, s.description, isError = attemptedSave && descriptionInvalid)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactMoneyInput(
                            amountCentsDigits,
                            { amountCentsDigits = it.filter(Char::isDigit) },
                            s.amount,
                            modifier = Modifier.weight(1f),
                            isError = attemptedSave && amountInvalid,
                        )
                        CompactInput(
                            day,
                            { day = it.filter(Char::isDigit) },
                            "Dia",
                            modifier = Modifier.width(78.dp),
                            isError = attemptedSave && dayInvalid,
                        )
                    }
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                        isError = attemptedSave && categoryInvalid,
                    )
                    CompactPrimaryButton(
                        s.add,
                        enabled = canSave,
                        compact = true,
                        squared = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            attemptedSave = true
                            if (!canSave) return@CompactPrimaryButton
                            val category = selectedCategory
                            onAddSubscription(
                                description,
                                amountCents,
                                dayInt.coerceIn(1, 31),
                                category.id,
                            )
                            showAddSubscriptionDialog = false
                        }
                    )
        }
    }

    if (showAddInstallmentDialog) {
        var description by remember { mutableStateOf("") }
        var amountCentsDigits by remember { mutableStateOf("") }
        var total by remember { mutableStateOf("12") }
        var selectedCategoryId by remember { mutableStateOf<String?>(null) }
        var attemptedSave by remember { mutableStateOf(false) }
        val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
        val totalAmountCents = amountCentsDigits.toLongOrNull() ?: 0L
        val totalInstallments = total.toIntOrNull() ?: 0
        val descriptionInvalid = description.isBlank()
        val amountInvalid = totalAmountCents <= 0L
        val totalInvalid = totalInstallments !in 1..360
        val categoryInvalid = selectedCategory == null
        val canSave = !descriptionInvalid && !amountInvalid && !totalInvalid && !categoryInvalid
        AppDialog(
            title = s.installments,
            onClose = { showAddInstallmentDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { showAddInstallmentDialog = false },
        ) {
                    CompactInput(description, { description = it }, s.description, isError = attemptedSave && descriptionInvalid)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactMoneyInput(
                            amountCentsDigits,
                            { amountCentsDigits = it.filter(Char::isDigit) },
                            s.totalAmount,
                            modifier = Modifier.weight(1f),
                            isError = attemptedSave && amountInvalid,
                        )
                        CompactInput(
                            total,
                            { total = it.filter(Char::isDigit) },
                            s.months,
                            modifier = Modifier.width(86.dp),
                            isError = attemptedSave && totalInvalid,
                        )
                    }
                    CategoryPicker(
                        s = s,
                        categories = state.categories,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        onAddCategory = onAddCategory,
                        isError = attemptedSave && categoryInvalid,
                    )
                    CompactPrimaryButton(
                        s.add,
                        enabled = canSave,
                        compact = true,
                        squared = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            attemptedSave = true
                            if (!canSave) return@CompactPrimaryButton
                            val category = selectedCategory
                            onAddInstallment(
                                description,
                                totalAmountCents,
                                totalInstallments.coerceIn(1, 360),
                                category.id,
                            )
                            showAddInstallmentDialog = false
                        }
                    )
        }
    }

    editingExpenseId?.let { expenseId ->
        val current = state.expenses.firstOrNull { it.id == expenseId }
        if (current != null) {
            var description by remember(current.id) { mutableStateOf(current.note.orEmpty()) }
            var amountCentsDigits by remember(current.id) { mutableStateOf(current.amountCents.toString()) }
            var recurring by remember(current.id) { mutableStateOf(current.recurrenceType == RecurrenceType.MONTHLY) }
            var selectedCategoryId by remember(current.id) { mutableStateOf<String?>(current.categoryId) }
            var attemptedSave by remember(current.id) { mutableStateOf(false) }
            val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
            val amountCents = amountCentsDigits.toLongOrNull() ?: 0L
            val amountInvalid = amountCents <= 0L
            val categoryInvalid = selectedCategory == null
            val canSave = !amountInvalid && !categoryInvalid
            AppDialog(
                title = s.newExpense,
                onClose = { editingExpenseId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { editingExpenseId = null },
            ) {
                        CompactInput(description, { description = it }, s.description)
                        CompactMoneyInput(
                            amountCentsDigits,
                            { amountCentsDigits = it.filter(Char::isDigit) },
                            s.amount,
                            isError = attemptedSave && amountInvalid,
                        )
                        CategoryPicker(
                            s = s,
                            categories = state.categories,
                            selectedCategoryId = selectedCategoryId,
                            onSelectCategory = { selectedCategoryId = it },
                            onAddCategory = onAddCategory,
                            isError = attemptedSave && categoryInvalid,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.monthly, style = MaterialTheme.typography.labelMedium)
                            Switch(
                                checked = recurring,
                                onCheckedChange = { recurring = it },
                                modifier = Modifier.scale(0.82f),
                                colors = appSwitchColors(),
                            )
                        }
                        CompactPrimaryButton(
                            s.save,
                            enabled = canSave,
                            compact = true,
                            squared = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                attemptedSave = true
                                if (!canSave) return@CompactPrimaryButton
                                val category = selectedCategory
                                onUpdateExpense(
                                    expenseId,
                                    amountCents,
                                    category.id,
                                    description.takeIf(String::isNotBlank),
                                    recurring,
                                )
                                editingExpenseId = null
                            }
                        )
            }
        }
    }

    editingSubscriptionId?.let { subscriptionId ->
        val current = state.subscriptions.firstOrNull { it.id == subscriptionId }
        if (current != null) {
            var description by remember(current.id) { mutableStateOf(current.name) }
            var amountCentsDigits by remember(current.id) { mutableStateOf(current.amountCents.toString()) }
            var day by remember(current.id) { mutableStateOf(current.billingDay.toString()) }
            var selectedCategoryId by remember(current.id) { mutableStateOf<String?>(current.categoryId) }
            var attemptedSave by remember(current.id) { mutableStateOf(false) }
            val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
            val amountCents = amountCentsDigits.toLongOrNull() ?: 0L
            val dayInt = day.toIntOrNull() ?: 0
            val descriptionInvalid = description.isBlank()
            val amountInvalid = amountCents <= 0L
            val dayInvalid = dayInt !in 1..31
            val categoryInvalid = selectedCategory == null
            val canSave = !descriptionInvalid && !amountInvalid && !dayInvalid && !categoryInvalid
            AppDialog(
                title = s.subscriptions,
                onClose = { editingSubscriptionId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { editingSubscriptionId = null },
            ) {
                        CompactInput(description, { description = it }, s.description, isError = attemptedSave && descriptionInvalid)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactMoneyInput(
                                amountCentsDigits,
                                { amountCentsDigits = it.filter(Char::isDigit) },
                                s.amount,
                                modifier = Modifier.weight(1f),
                                isError = attemptedSave && amountInvalid,
                            )
                            CompactInput(
                                day,
                                { day = it.filter(Char::isDigit) },
                                "Dia",
                                modifier = Modifier.width(78.dp),
                                isError = attemptedSave && dayInvalid,
                            )
                        }
                        CategoryPicker(
                            s = s,
                            categories = state.categories,
                            selectedCategoryId = selectedCategoryId,
                            onSelectCategory = { selectedCategoryId = it },
                            onAddCategory = onAddCategory,
                            isError = attemptedSave && categoryInvalid,
                        )
                        CompactPrimaryButton(
                            s.save,
                            enabled = canSave,
                            compact = true,
                            squared = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                attemptedSave = true
                                if (!canSave) return@CompactPrimaryButton
                                val category = selectedCategory
                                onUpdateSubscription(
                                    subscriptionId,
                                    description,
                                    amountCents,
                                    dayInt.coerceIn(1, 31),
                                    category.id,
                                )
                                editingSubscriptionId = null
                            }
                        )
            }
        }
    }

    editingInstallmentId?.let { installmentId ->
        val current = state.installments.firstOrNull { it.id == installmentId }
        if (current != null) {
            var description by remember(current.id) { mutableStateOf(current.name) }
            var amountCentsDigits by remember(current.id) {
                mutableStateOf((current.monthlyAmountCents * current.totalInstallments.toLong()).toString())
            }
            var total by remember(current.id) { mutableStateOf(current.totalInstallments.toString()) }
            var selectedCategoryId by remember(current.id) { mutableStateOf<String?>(current.categoryId) }
            var attemptedSave by remember(current.id) { mutableStateOf(false) }
            val selectedCategory = state.categories.firstOrNull { it.id == selectedCategoryId }
            val totalAmountCents = amountCentsDigits.toLongOrNull() ?: 0L
            val totalInstallments = total.toIntOrNull() ?: 0
            val descriptionInvalid = description.isBlank()
            val amountInvalid = totalAmountCents <= 0L
            val totalInvalid = totalInstallments !in 1..360
            val categoryInvalid = selectedCategory == null
            val canSave = !descriptionInvalid && !amountInvalid && !totalInvalid && !categoryInvalid
            AppDialog(
                title = s.installments,
                onClose = { editingInstallmentId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { editingInstallmentId = null },
            ) {
                        CompactInput(description, { description = it }, s.description, isError = attemptedSave && descriptionInvalid)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactMoneyInput(
                                amountCentsDigits,
                                { amountCentsDigits = it.filter(Char::isDigit) },
                                s.totalAmount,
                                modifier = Modifier.weight(1f),
                                isError = attemptedSave && amountInvalid,
                            )
                            CompactInput(
                                total,
                                { total = it.filter(Char::isDigit) },
                                s.months,
                                modifier = Modifier.width(86.dp),
                                isError = attemptedSave && totalInvalid,
                            )
                        }
                        CategoryPicker(
                            s = s,
                            categories = state.categories,
                            selectedCategoryId = selectedCategoryId,
                            onSelectCategory = { selectedCategoryId = it },
                            onAddCategory = onAddCategory,
                            isError = attemptedSave && categoryInvalid,
                        )
                        CompactPrimaryButton(
                            s.save,
                            enabled = canSave,
                            compact = true,
                            squared = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                attemptedSave = true
                                if (!canSave) return@CompactPrimaryButton
                                val category = selectedCategory
                                onUpdateInstallment(
                                    installmentId,
                                    description,
                                    totalAmountCents,
                                    totalInstallments.coerceIn(1, 360),
                                    category.id,
                                )
                                editingInstallmentId = null
                            }
                        )
            }
        }
    }
}

@Composable
private fun CategoryPicker(
    s: AppStrings,
    categories: List<com.samluiz.gyst.domain.model.Category>,
    selectedCategoryId: String?,
    onSelectCategory: (String?) -> Unit,
    onAddCategory: (String, ((String) -> Unit)?) -> Unit,
    isError: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            s.category,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            CompactPrimaryButton(
                text = selectedCategory?.name ?: s.selectCategory,
                enabled = categories.isNotEmpty(),
                compact = true,
                subtle = true,
                squared = true,
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
        if (isError) {
            Text(
                s.requiredField,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showNewCategoryDialog) {
        AppDialog(
            title = s.newCategory,
            onClose = { showNewCategoryDialog = false },
            closeLabel = s.close,
            maxWidth = 380.dp,
            onDismissRequest = { showNewCategoryDialog = false },
        ) {
                    CompactInput(value = newCategoryName, onValueChange = { newCategoryName = it }, label = s.description, isError = false)
                    CompactPrimaryButton(
                        s.add,
                        enabled = newCategoryName.isNotBlank(),
                        compact = true,
                        squared = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onAddCategory(newCategoryName) { createdId ->
                                onSelectCategory(createdId)
                            }
                            newCategoryName = ""
                            showNewCategoryDialog = false
                        }
                    )
        }
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
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconCompactButton(
            onClick = onClose,
            icon = Icons.Default.Close,
            contentDescription = closeLabel,
            compact = true,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppDialog(
    title: String,
    onClose: () -> Unit,
    closeLabel: String,
    maxWidth: androidx.compose.ui.unit.Dp = 420.dp,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogHeaderTitle(title = title, onClose = onClose, closeLabel = closeLabel)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable
private fun AppToggleChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

@Composable
private fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
)

@Composable
private fun ProfileTab(
    s: AppStrings,
    state: MainState,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (String) -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onRestoreGoogleDrive: (Boolean) -> Unit,
    onResetLocalData: () -> Unit,
) {
    val google = state.googleSync
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(title = s.profile, icon = Icons.Default.Person) {
                ProfileIdentitySection(
                    s = s,
                    name = google.accountName,
                    email = google.accountEmail,
                    photoUrl = google.accountPhotoUrl,
                    google = google,
                    onSignInGoogle = onSignInGoogle,
                    onSignOutGoogle = onSignOutGoogle,
                    onSyncGoogleDrive = onSyncGoogleDrive,
                    onRestoreGoogleDrive = { showRestoreConfirm = true },
                )
            }
        }
        item {
            PanelCard(title = s.language, icon = Icons.Default.Settings) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppToggleChip(selected = state.language == "system", onClick = { onSetLanguage("system") }, text = s.system)
                    AppToggleChip(selected = state.language == "pt", onClick = { onSetLanguage("pt") }, text = "PT")
                    AppToggleChip(selected = state.language == "en", onClick = { onSetLanguage("en") }, text = "EN")
                }
            }
        }
        item {
            PanelCard(title = s.theme, icon = Icons.Default.Settings) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppToggleChip(selected = state.themeMode == "system", onClick = { onSetTheme("system") }, text = s.system)
                    AppToggleChip(selected = state.themeMode == "light", onClick = { onSetTheme("light") }, text = s.light)
                    AppToggleChip(selected = state.themeMode == "dark", onClick = { onSetTheme("dark") }, text = s.dark)
                }
            }
        }
        item {
            PanelCard(title = s.appVersion, icon = Icons.Default.Info) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "v${BuildInfo.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CompactPrimaryButton(
                        text = s.viewLicenses,
                        compact = true,
                        squared = true,
                        subtle = true,
                        onClick = { showLicenses = true },
                    )
                }
            }
        }
        item {
            PanelCard(
                title = s.dangerZone,
                icon = Icons.Default.Warning,
                accentColor = MaterialTheme.colorScheme.error,
                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
            ) {
                Text(
                    s.resetLocalDataDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.95f),
                )
                CompactPrimaryButton(
                    text = s.resetLocalData,
                    compact = true,
                    squared = true,
                    subtle = true,
                    danger = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showResetConfirm = true },
                )
            }
        }
    }

    if (showRestoreConfirm) {
        AppDialog(
            title = "Google Drive",
            onClose = { showRestoreConfirm = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { showRestoreConfirm = false },
        ) {
                    Text(
                        s.restoreConfirm,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactPrimaryButton(
                        s.save,
                        compact = true,
                        squared = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showRestoreConfirm = false
                            onRestoreGoogleDrive(true)
                        }
                    )
        }
    }

    if (showLicenses) {
        AppDialog(
            title = s.openSourceLicenses,
            onClose = { showLicenses = false },
            closeLabel = s.close,
            maxWidth = 520.dp,
            onDismissRequest = { showLicenses = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenSourceLibraries.entries.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(item.license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item != OpenSourceLibraries.entries.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AppDialog(
            title = s.resetLocalData,
            onClose = { showResetConfirm = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { showResetConfirm = false },
        ) {
            Text(
                s.resetLocalDataConfirm,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactPrimaryButton(
                text = s.delete,
                compact = true,
                squared = true,
                subtle = true,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showResetConfirm = false
                    onResetLocalData()
                },
            )
        }
    }
}

private data class OpenSourceLibrary(val name: String, val license: String, val url: String)

private object OpenSourceLibraries {
    val entries = listOf(
        OpenSourceLibrary("Kotlin", "Apache-2.0", "https://kotlinlang.org"),
        OpenSourceLibrary("Compose Multiplatform", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
        OpenSourceLibrary("Koin", "Apache-2.0", "https://github.com/InsertKoinIO/koin"),
        OpenSourceLibrary("SQLDelight", "Apache-2.0", "https://github.com/cashapp/sqldelight"),
        OpenSourceLibrary("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        OpenSourceLibrary("kotlinx.datetime", "Apache-2.0", "https://github.com/Kotlin/kotlinx-datetime"),
        OpenSourceLibrary("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    )
}

@Composable
private fun ProfileIdentitySection(
    s: AppStrings,
    name: String?,
    email: String?,
    photoUrl: String?,
    google: GoogleSyncState,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onRestoreGoogleDrive: () -> Unit,
) {
    val displayName = name?.takeIf { it.isNotBlank() } ?: s.guestUser
    val displayEmail = email?.takeIf { it.isNotBlank() } ?: s.noGoogleConnected
    val initial = displayName.firstOrNull()?.uppercase() ?: "G"
    val remotePhoto = rememberRemoteProfileImage(photoUrl)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.20f), spotColor = Color.Black.copy(alpha = 0.18f))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (remotePhoto != null) {
                Image(
                    bitmap = remotePhoto,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            } else {
                Text(initial, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    displayEmail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (google.isSignedIn) {
                    GoogleMark(modifier = Modifier.size(14.dp))
                }
            }
            if (!google.isAvailable) {
                Text(
                    s.syncUnavailable,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
            } else {
                if (google.statusMessage != null) {
                    Text(
                        google.statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (google.lastSyncAtIso != null) {
                    Text(
                        "${s.syncedAt}: ${formatInstantHuman(google.lastSyncAtIso, s.languageCode)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (google.lastSyncSource != null) {
                    Text(
                        "${s.syncSource}: ${syncSourceLabel(google.lastSyncSource, s)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (google.hadSyncConflict) {
                    Text(
                        s.conflictResolvedByPolicy,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (google.requiresAppRestart) {
                    Text(
                        s.restartAppToApply,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (google.lastError != null) {
                    Text(
                        google.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
    if (google.isAvailable) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!google.isSignedIn) {
                CompactPrimaryButton(
                    s.loginGoogle,
                    loading = google.isAuthInProgress,
                    compact = true,
                    squared = true,
                    leadingContent = { GoogleMark() },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSignInGoogle,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactPrimaryButton(
                            s.syncDrive,
                            enabled = !google.isSyncing,
                            loading = google.isSyncing,
                            compact = true,
                            squared = true,
                            modifier = Modifier.weight(1f),
                            onClick = onSyncGoogleDrive,
                        )
                        CompactPrimaryButton(
                            s.restoreDrive,
                            enabled = !google.isSyncing,
                            compact = true,
                            squared = true,
                            modifier = Modifier.weight(1f),
                            onClick = onRestoreGoogleDrive,
                        )
                    }
                    CompactPrimaryButton(
                        text = s.logoutGoogle,
                        compact = true,
                        squared = true,
                        subtle = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSignOutGoogle,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelCard(
    title: String,
    icon: ImageVector? = null,
    truncateTitle: Boolean = true,
    autoShrinkTitle: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
    headerCenter: (@Composable () -> Unit)? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedTitleStyle = when {
        !autoShrinkTitle -> MaterialTheme.typography.titleSmall
        title.length > 18 -> MaterialTheme.typography.labelMedium
        title.length > 12 -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.titleSmall
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.20f),
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        icon?.let { Icon(it, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp)) }
                        Text(
                            title,
                            style = resolvedTitleStyle,
                            color = accentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = if (truncateTitle) TextOverflow.Ellipsis else TextOverflow.Visible,
                        )
                    }
                    headerTrailing?.invoke()
                }
                Box(modifier = Modifier.align(Alignment.Center)) {
                    headerCenter?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
private fun CompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var fieldValue by remember(value) {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            )
        )
    }
    var focusHandled by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f))
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValueChange(it.text)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused && !focusHandled && fieldValue.text.isNotEmpty() && fieldValue.selection.start == 0 && fieldValue.selection.end == 0) {
                        fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
                    }
                    if (it.isFocused) focusHandled = true
                    if (!it.isFocused) focusHandled = false
                }
                .heightIn(min = 36.dp)
                .border(
                    1.dp,
                    when {
                        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    },
                    RoundedCornerShape(10.dp)
                )
                .background(
                    if (focused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    RoundedCornerShape(10.dp)
                ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun CompactMoneyInput(
    centsDigits: String,
    onCentsDigitsChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var fieldValue by remember(centsDigits) {
        val formatted = formatBrlFromCentsDigits(centsDigits)
        mutableStateOf(
            TextFieldValue(
                text = formatted,
                selection = TextRange(formatted.length),
            )
        )
    }
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f))
        BasicTextField(
            value = fieldValue,
            onValueChange = { typed ->
                val digits = typed.text.filter(Char::isDigit)
                onCentsDigitsChange(digits)
                val formatted = formatBrlFromCentsDigits(digits)
                fieldValue = TextFieldValue(
                    text = formatted,
                    selection = TextRange(formatted.length),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .heightIn(min = 36.dp)
                .shadow(
                    elevation = if (focused) 1.dp else 5.dp,
                    shape = RoundedCornerShape(10.dp),
                    ambientColor = Color.Black.copy(alpha = 0.15f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .border(
                    1.dp,
                    when {
                        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
                    },
                    RoundedCornerShape(10.dp)
                )
                .background(
                    if (focused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    RoundedCornerShape(10.dp)
                ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun CompactPrimaryButton(
    text: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    squared: Boolean = false,
    subtle: Boolean = false,
    danger: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = if (compact || subtle) 8.dp else 10.dp,
            vertical = if (compact || subtle) 4.dp else 6.dp
        ),
        modifier = modifier
            .heightIn(min = if (compact || subtle) 30.dp else 34.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingContent?.invoke()
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun IconCompactButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    subtle: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (subtle || compact) 2.dp else 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
        ),
        modifier = Modifier
            .heightIn(min = if (subtle || compact) 24.dp else 28.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(if (subtle || compact) 14.dp else 16.dp))
    }
}

private fun formatBrl(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    val reais = abs / 100
    val cent = abs % 100
    val signal = if (cents < 0) "-" else ""
    val grouped = groupThousands(reais)
    return "$signal R$ $grouped,${pad2(cent)}"
}

private fun formatBrlFromCentsDigits(centsDigits: String): String {
    val digits = centsDigits.filter(Char::isDigit).ifBlank { "0" }
    val cents = digits.toLongOrNull() ?: 0L
    val reais = cents / 100
    val cent = cents % 100
    val grouped = groupThousands(reais)
    return "R$ $grouped,${pad2(cent)}"
}

private fun formatSigned(cents: Long): String {
    val prefix = if (cents > 0) "+" else ""
    return "$prefix${formatBrl(cents)}"
}

private fun syncSourceLabel(source: SyncSource, s: AppStrings): String = when (source) {
    SyncSource.LOCAL_TO_CLOUD -> s.syncSourceLocalToCloud
    SyncSource.CLOUD_TO_LOCAL -> s.syncSourceCloudToLocal
}

private fun syncPolicyLabel(policy: SyncPolicy, s: AppStrings): String = when (policy) {
    SyncPolicy.NEWEST_WINS -> s.syncPolicyNewestWins
    SyncPolicy.OVERWRITE_LOCAL -> s.syncPolicyOverwriteLocal
}

private fun pad2(value: Long): String {
    return if (value < 10L) "0$value" else value.toString()
}

private fun pad2(value: Int): String {
    return if (value < 10) "0$value" else value.toString()
}

private fun groupThousands(value: Long): String {
    return value.toString().reversed().chunked(3).joinToString(".").reversed()
}

private fun formatYearMonthHuman(yearMonth: com.samluiz.gyst.domain.model.YearMonth, languageCode: String): String {
    val month = monthShortName(yearMonth.month, languageCode)
    return "$month ${yearMonth.year}"
}

private fun formatLocalDateHuman(date: LocalDate, languageCode: String): String {
    val relative = relativeDateLabel(date, languageCode)
    if (relative != null) return relative
    val month = monthShortName(date.month.ordinal + 1, languageCode)
    return if (languageCode == "pt") {
        "${pad2(date.day)} $month ${date.year}"
    } else {
        "$month ${date.day}, ${date.year}"
    }
}

private fun formatInstantHuman(iso: String, languageCode: String): String {
    return runCatching {
        val local = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
        val timeLabel = "${pad2(local.hour)}:${pad2(local.minute)}"
        val relative = relativeDateLabel(local.date, languageCode)
        if (relative != null) {
            "$relative, $timeLabel"
        } else {
            val dateLabel = formatLocalDateHuman(local.date, languageCode)
            "$dateLabel, $timeLabel"
        }
    }.getOrDefault(iso)
}

private fun relativeDateLabel(date: LocalDate, languageCode: String): String? {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val diff = date.toEpochDays() - today.toEpochDays()
    return when {
        diff == 0L -> if (languageCode == "pt") "Hoje" else "Today"
        diff == -1L -> if (languageCode == "pt") "Ontem" else "Yesterday"
        diff == 1L -> if (languageCode == "pt") "Amanha" else "Tomorrow"
        diff in 2L..7L -> if (languageCode == "pt") "Em $diff dias" else "In $diff days"
        diff in -7L..-2L -> {
            val days = -diff
            if (languageCode == "pt") "Ha $days dias" else "$days days ago"
        }
        else -> null
    }
}

private fun monthShortName(month: Int, languageCode: String): String {
    val pt = listOf(
        "jan", "fev", "mar", "abr", "mai", "jun",
        "jul", "ago", "set", "out", "nov", "dez"
    )
    val en = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
    val safeIndex = (month - 1).coerceIn(0, 11)
    return if (languageCode == "pt") pt[safeIndex] else en[safeIndex]
}

private fun capitalizeFirst(value: String): String {
    return value.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
