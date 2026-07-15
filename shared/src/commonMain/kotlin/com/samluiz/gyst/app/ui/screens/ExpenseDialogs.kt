package com.samluiz.gyst.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.RecurrenceType
import com.samluiz.gyst.domain.model.displayDescription
import com.samluiz.gyst.presentation.MainState

internal class ExpenseDialogState {
    var showAddExpenseDialog by mutableStateOf(false)
    var showAddSubscriptionDialog by mutableStateOf(false)
    var showAddInstallmentDialog by mutableStateOf(false)
    var editingExpenseId by mutableStateOf<String?>(null)
    var editingSubscriptionId by mutableStateOf<String?>(null)
    var editingInstallmentId by mutableStateOf<String?>(null)
}

@Composable
internal fun ExpenseDialogs(
    s: AppStrings,
    state: MainState,
    dialogs: ExpenseDialogState,
    onAddExpense: (Long, String, String?, Boolean) -> Unit,
    onAddCategory: (String, ((String) -> Unit)?) -> Unit,
    onUpdateCategory: (String, String, ((Boolean) -> Unit)?) -> Unit,
    onDeleteCategory: (String, ((Boolean, Long) -> Unit)?) -> Unit,
    onAddSubscription: (String, Long, Int, String) -> Unit,
    onAddInstallment: (String, Long, Int, String) -> Unit,
    onUpdateExpense: (String, Long, String, String?, Boolean) -> Unit,
    onUpdateSubscription: (String, String, Long, Int, String) -> Unit,
    onUpdateInstallment: (String, String, Long, Int, String) -> Unit,
) {
    if (dialogs.showAddExpenseDialog) {
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
            onClose = { dialogs.showAddExpenseDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { dialogs.showAddExpenseDialog = false },
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
                    dialogs.showAddExpenseDialog = false
                },
            )
        }
    }

    if (dialogs.showAddSubscriptionDialog) {
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
            onClose = { dialogs.showAddSubscriptionDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { dialogs.showAddSubscriptionDialog = false },
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
                    keyboardType = KeyboardType.Number,
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
                    dialogs.showAddSubscriptionDialog = false
                },
            )
        }
    }

    if (dialogs.showAddInstallmentDialog) {
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
            onClose = { dialogs.showAddInstallmentDialog = false },
            closeLabel = s.close,
            maxWidth = 420.dp,
            onDismissRequest = { dialogs.showAddInstallmentDialog = false },
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
                    keyboardType = KeyboardType.Number,
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
                    dialogs.showAddInstallmentDialog = false
                },
            )
        }
    }

    dialogs.editingExpenseId?.let { expenseId ->
        val current = state.expenses.firstOrNull { it.id == expenseId }
        if (current != null) {
            var description by remember(current.id) { mutableStateOf(current.displayDescription().orEmpty()) }
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
                onClose = { dialogs.editingExpenseId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { dialogs.editingExpenseId = null },
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
                        dialogs.editingExpenseId = null
                    },
                )
            }
        }
    }

    dialogs.editingSubscriptionId?.let { subscriptionId ->
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
                onClose = { dialogs.editingSubscriptionId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { dialogs.editingSubscriptionId = null },
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
                        keyboardType = KeyboardType.Number,
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
                        dialogs.editingSubscriptionId = null
                    },
                )
            }
        }
    }

    dialogs.editingInstallmentId?.let { installmentId ->
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
                onClose = { dialogs.editingInstallmentId = null },
                closeLabel = s.close,
                maxWidth = 420.dp,
                onDismissRequest = { dialogs.editingInstallmentId = null },
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
                        keyboardType = KeyboardType.Number,
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
                        dialogs.editingInstallmentId = null
                    },
                )
            }
        }
    }
}
