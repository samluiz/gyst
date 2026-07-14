package com.samluiz.gyst.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CategoryPicker(
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
