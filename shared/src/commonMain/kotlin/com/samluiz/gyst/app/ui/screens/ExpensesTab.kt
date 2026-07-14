package com.samluiz.gyst.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.presentation.MainState
import kotlinx.coroutines.launch

@Composable
internal fun DespesasTab(
    s: AppStrings,
    state: MainState,
    selectedSectionIndex: Int,
    onSelectedSectionChange: (Int) -> Unit,
    onLoadMoreExpenses: () -> Unit,
    onAddExpense: (Long, String, String?, Boolean) -> Unit,
    onAddCategory: (String, ((String) -> Unit)?) -> Unit,
    onUpdateCategory: (String, String, ((Boolean) -> Unit)?) -> Unit,
    onDeleteCategory: (String, ((Boolean, Long) -> Unit)?) -> Unit,
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
    val dialogs = remember { ExpenseDialogState() }
    val categoryById = remember(state.categories) { state.categories.associateBy { it.id } }
    val visibleSubscriptions = remember(state.subscriptions) { state.subscriptions.filter { it.active } }
    val visibleInstallments =
        remember(state.currentMonth, state.installments) {
            state.installments.filter {
                it.active && it.startYearMonth <= state.currentMonth && it.endYearMonth >= state.currentMonth
            }
        }
    val sections = remember { ExpensesSection.entries.toList() }
    val initialPage = selectedSectionIndex.coerceIn(0, sections.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sections.size })
    val pagerScope = rememberCoroutineScope()
    val section = sections[pagerState.currentPage]
    var rowMenuId by remember { mutableStateOf<String?>(null) }
    var subscriptionsVisibleCount by remember { mutableStateOf(30) }
    var installmentsVisibleCount by remember { mutableStateOf(30) }
    val expensesListState = rememberLazyListState()
    val subscriptionsListState = rememberLazyListState()
    val installmentsListState = rememberLazyListState()
    val expensesTotal = state.summary?.spentTotalCents ?: state.expenses.sumOf { it.amountCents }
    val subscriptionsTotal = visibleSubscriptions.sumOf { it.amountCents }
    val installmentsTotal = visibleInstallments.sumOf { it.monthlyAmountCents }
    val activeSectionTotal =
        when (section) {
            ExpensesSection.DESPESAS -> expensesTotal
            ExpensesSection.ASSINATURAS -> subscriptionsTotal
            ExpensesSection.PARCELAMENTOS -> installmentsTotal
        }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedSectionIndex) {
            onSelectedSectionChange(pagerState.currentPage)
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(
                title = "",
                icon = null,
                truncateTitle = false,
                autoShrinkTitle = true,
                headerCenter = {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        sections.forEachIndexed { index, current ->
                            val label =
                                when (current) {
                                    ExpensesSection.DESPESAS -> s.expenses
                                    ExpensesSection.ASSINATURAS -> s.subscriptions
                                    ExpensesSection.PARCELAMENTOS -> s.installments
                                }
                            AppToggleChip(
                                selected = index == pagerState.currentPage,
                                onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                                text = label,
                            )
                        }
                    }
                },
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 300.dp, max = 420.dp),
                ) { page ->
                    when (sections[page]) {
                        ExpensesSection.DESPESAS -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), state = expensesListState) {
                                if (state.expenses.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                s.noRecords,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                } else {
                                    itemsIndexed(state.expenses, key = { _, item -> item.id }) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    item.note ?: s.noDescription,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    "${formatLocalDateHuman(
                                                        item.occurredAt,
                                                        s.languageCode,
                                                    )} • ${categoryById[item.categoryId]?.name ?: s.category}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(
                                                formatBrl(item.amountCents),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
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
                                                            dialogs.editingExpenseId = item.id
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateExpense(item.id)
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteExpense(item.id)
                                                        },
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
                                            Text(
                                                s.noRecords,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                } else {
                                    itemsIndexed(subscriptionItems, key = { _, item -> item.id }) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    item.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    "Dia ${item.billingDay}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                formatBrl(item.amountCents),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
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
                                                            dialogs.editingSubscriptionId = item.id
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateSubscription(item.id)
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteSubscription(item.id)
                                                        },
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
                                            Text(
                                                s.noRecords,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                } else {
                                    itemsIndexed(installmentItems, key = { _, item -> item.id }) { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    item.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    "${formatYearMonthHuman(
                                                        item.startYearMonth,
                                                        s.languageCode,
                                                    )} -> ${formatYearMonthHuman(item.endYearMonth, s.languageCode)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(
                                                formatBrl(item.monthlyAmountCents),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
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
                                                            dialogs.editingInstallmentId = item.id
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.duplicate) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDuplicateInstallment(item.id)
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(s.delete) },
                                                        onClick = {
                                                            rowMenuId = null
                                                            onDeleteInstallment(item.id)
                                                        },
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
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${s.total}: ${formatBrl(activeSectionTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    IconCompactButton(
                        icon = Icons.Default.Add,
                        contentDescription = s.add,
                        compact = true,
                        subtle = true,
                        onClick = {
                            when (section) {
                                ExpensesSection.DESPESAS -> dialogs.showAddExpenseDialog = true
                                ExpensesSection.ASSINATURAS -> dialogs.showAddSubscriptionDialog = true
                                ExpensesSection.PARCELAMENTOS -> dialogs.showAddInstallmentDialog = true
                            }
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }

    ExpenseDialogs(
        s = s,
        state = state,
        dialogs = dialogs,
        onAddExpense = onAddExpense,
        onAddCategory = onAddCategory,
        onUpdateCategory = onUpdateCategory,
        onDeleteCategory = onDeleteCategory,
        onAddSubscription = onAddSubscription,
        onAddInstallment = onAddInstallment,
        onUpdateExpense = onUpdateExpense,
        onUpdateSubscription = onUpdateSubscription,
        onUpdateInstallment = onUpdateInstallment,
    )
}
