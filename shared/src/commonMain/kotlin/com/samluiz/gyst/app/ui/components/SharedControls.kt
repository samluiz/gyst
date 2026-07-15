package com.samluiz.gyst.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PanelCard(
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
    val resolvedTitleStyle =
        when {
            !autoShrinkTitle -> MaterialTheme.typography.titleSmall
            title.length > 18 -> MaterialTheme.typography.labelMedium
            title.length > 12 -> MaterialTheme.typography.labelLarge
            else -> MaterialTheme.typography.titleSmall
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
internal fun CompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    isWarning: Boolean = false,
    supportingText: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var fieldValue by remember(value) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        val shouldMoveCursorToEnd =
                            it.isFocused &&
                                !focusHandled &&
                                fieldValue.text.isNotEmpty() &&
                                fieldValue.selection.start == 0 &&
                                fieldValue.selection.end == 0
                        if (shouldMoveCursorToEnd) {
                            fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
                        }
                        focusHandled = it.isFocused
                    }
                    .heightIn(min = 36.dp)
                    .border(
                        1.dp,
                        when {
                            isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                            isWarning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                            focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .background(
                        if (focused) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        },
                        RoundedCornerShape(8.dp),
                    ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color =
                    when {
                        isError -> MaterialTheme.colorScheme.error
                        isWarning -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
internal fun CompactMoneyInput(
    centsDigits: String,
    onCentsDigitsChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isWarning: Boolean = false,
    supportingText: String? = null,
) {
    var fieldValue by remember(centsDigits) {
        val formatted = formatBrlFromCentsDigits(centsDigits)
        mutableStateOf(TextFieldValue(text = formatted, selection = TextRange(formatted.length)))
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
                fieldValue = TextFieldValue(text = formatted, selection = TextRange(formatted.length))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .heightIn(min = 36.dp)
                    .shadow(
                        elevation = if (focused) 1.dp else 5.dp,
                        shape = RoundedCornerShape(8.dp),
                        ambientColor = Color.Black.copy(alpha = 0.15f),
                        spotColor = Color.Black.copy(alpha = 0.12f),
                    )
                    .border(
                        1.dp,
                        when {
                            isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            isWarning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                            focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .background(
                        if (focused) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                        },
                        RoundedCornerShape(8.dp),
                    ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color =
                    when {
                        isError -> MaterialTheme.colorScheme.error
                        isWarning -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
internal fun CompactPrimaryButton(
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
        colors =
            ButtonDefaults.textButtonColors(
                containerColor = Color.Transparent,
                contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = if (compact || subtle) 8.dp else 10.dp,
                vertical = if (compact || subtle) 4.dp else 6.dp,
            ),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                leadingContent?.invoke()
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun IconCompactButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    subtle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (subtle || compact) 2.dp else 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors =
            ButtonDefaults.textButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = Color.Transparent,
            ),
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(if (subtle || compact) 14.dp else 16.dp))
    }
}

@Composable
private fun DialogHeaderTitle(
    title: String,
    onClose: () -> Unit,
    closeLabel: String,
) {
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
internal fun AppDialog(
    title: String,
    onClose: () -> Unit,
    closeLabel: String,
    maxWidth: Dp = 420.dp,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth).padding(horizontal = 8.dp),
            shape = RoundedCornerShape(8.dp),
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
internal fun AppToggleChip(
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
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
                iconColor = MaterialTheme.colorScheme.primary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
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
internal fun appSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    )
