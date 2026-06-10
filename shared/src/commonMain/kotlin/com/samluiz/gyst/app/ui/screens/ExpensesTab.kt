package com.samluiz.gyst.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.RecurrenceType
import com.samluiz.gyst.domain.model.YearMonth
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
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var showAddSubscriptionDialog by remember { mutableStateOf(false) }
    var showAddInstallmentDialog by remember { mutableStateOf(false) }
    var editingExpenseId by remember { mutableStateOf<String?>(null) }
    var editingSubscriptionId by remember { mutableStateOf<String?>(null) }
    var editingInstallmentId by remember { mutableStateOf<String?>(null) }
    val categoryById = remember(state.categories) { state.categories.associateBy { it.id } }
    val visibleSubscriptions = remember(state.subscriptions) { state.subscriptions.filter { it.active } }
    val visibleInstallments =
        remember(state.currentMonth, state.installments) {
            state.installments.filter { it.startYearMonth <= state.currentMonth && it.endYearMonth >= state.currentMonth }
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
                                    itemsIndexed(state.expenses) { index, item ->
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
                                                            editingExpenseId = item.id
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
                                    itemsIndexed(subscriptionItems) { index, item ->
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
                                                            editingSubscriptionId = item.id
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
                                    itemsIndexed(installmentItems) { index, item ->
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
                                                            editingInstallmentId = item.id
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
                                ExpensesSection.DESPESAS -> showAddExpenseDialog = true
                                ExpensesSection.ASSINATURAS -> showAddSubscriptionDialog = true
                                ExpensesSection.PARCELAMENTOS -> showAddInstallmentDialog = true
                            }
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
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
                onUpdateCategory = onUpdateCategory,
                onDeleteCategory = onDeleteCategory,
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
                s.add,
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
                },
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
                onUpdateCategory = onUpdateCategory,
                onDeleteCategory = onDeleteCategory,
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
                },
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
                onUpdateCategory = onUpdateCategory,
                onDeleteCategory = onDeleteCategory,
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
                },
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
                    onUpdateCategory = onUpdateCategory,
                    onDeleteCategory = onDeleteCategory,
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
                    s.edit,
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
                    },
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
                    onUpdateCategory = onUpdateCategory,
                    onDeleteCategory = onDeleteCategory,
                    isError = attemptedSave && categoryInvalid,
                )
                CompactPrimaryButton(
                    s.edit,
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
                    },
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
                    onUpdateCategory = onUpdateCategory,
                    onDeleteCategory = onDeleteCategory,
                    isError = attemptedSave && categoryInvalid,
                )
                CompactPrimaryButton(
                    s.edit,
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
                    },
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
    onUpdateCategory: (String, String, ((Boolean) -> Unit)?) -> Unit,
    onDeleteCategory: (String, ((Boolean, Long) -> Unit)?) -> Unit,
    isError: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var editingCategoryId by remember { mutableStateOf<String?>(null) }
    var editingCategoryName by remember { mutableStateOf("") }
    var manageError by remember { mutableStateOf<String?>(null) }
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
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ ${s.newCategory}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menuExpanded = false
                        showNewCategoryDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(s.manageCategories, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menuExpanded = false
                        showManageCategoriesDialog = true
                    },
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
                },
            )
        }
    }

    if (showManageCategoriesDialog) {
        val categoryListScroll = rememberScrollState()
        val categoryListViewportHeight = 280.dp
        AppDialog(
            title = s.manageCategories,
            onClose = {
                showManageCategoriesDialog = false
                editingCategoryId = null
                editingCategoryName = ""
                manageError = null
            },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = {
                showManageCategoriesDialog = false
                editingCategoryId = null
                editingCategoryName = ""
                manageError = null
            },
        ) {
            manageError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (categories.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(categoryListViewportHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.noRecords,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(categoryListViewportHeight)
                            .verticalScroll(categoryListScroll)
                            .padding(end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editingCategoryId == category.id) {
                                CompactInput(
                                    value = editingCategoryName,
                                    onValueChange = { editingCategoryName = it },
                                    label = s.description,
                                    isError = false,
                                    modifier = Modifier.weight(1f),
                                )
                                CompactPrimaryButton(
                                    text = s.save,
                                    enabled = editingCategoryName.isNotBlank(),
                                    compact = true,
                                    subtle = true,
                                    squared = true,
                                    onClick = {
                                        onUpdateCategory(category.id, editingCategoryName) { ok ->
                                            if (ok) {
                                                editingCategoryId = null
                                                editingCategoryName = ""
                                                manageError = null
                                            } else {
                                                manageError = s.categoryNameTaken
                                            }
                                        }
                                    },
                                )
                            } else {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconCompactButton(
                                    onClick = {
                                        editingCategoryId = category.id
                                        editingCategoryName = category.name
                                        manageError = null
                                    },
                                    icon = Icons.Default.Edit,
                                    contentDescription = s.edit,
                                    compact = true,
                                    subtle = true,
                                )
                                IconCompactButton(
                                    onClick = {
                                        onDeleteCategory(category.id) { ok, refs ->
                                            if (ok) {
                                                if (selectedCategoryId == category.id) {
                                                    onSelectCategory(null)
                                                }
                                                manageError = null
                                            } else {
                                                manageError = "${s.cannotDeleteCategoryInUse} ($refs)"
                                            }
                                        }
                                    },
                                    icon = Icons.Default.Delete,
                                    contentDescription = s.delete,
                                    compact = true,
                                    subtle = true,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    }
                }
            }
        }
    }
}
