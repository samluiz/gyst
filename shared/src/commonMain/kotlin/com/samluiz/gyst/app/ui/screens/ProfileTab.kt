package com.samluiz.gyst.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionState
import com.samluiz.gyst.presentation.MainState

@Composable
internal fun ProfileTab(
    s: AppStrings,
    state: MainState,
    automaticDetectionState: AutomaticTransactionDetectionState,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onStartUpdate: () -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onSyncGoogleDrive: () -> Unit,
    onRestoreGoogleDrive: (Boolean) -> Unit,
    onResetLocalData: () -> Unit,
    onOpenAutomaticDetectionSettings: () -> Unit,
    onOpenPendingSuggestions: () -> Unit,
) {
    val google = state.googleSync
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showGoogleActions by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            PanelCard(
                title = s.profile,
                icon = Icons.Default.Person,
                headerTrailing = {
                    if (google.isAvailable && google.isSignedIn) {
                        Box {
                            IconCompactButton(
                                onClick = { showGoogleActions = true },
                                icon = Icons.Default.MoreVert,
                                contentDescription = s.settings,
                                compact = true,
                                subtle = true,
                            )
                            DropdownMenu(
                                expanded = showGoogleActions,
                                onDismissRequest = { showGoogleActions = false },
                                modifier =
                                    Modifier
                                        .widthIn(min = 190.dp, max = 260.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                            ) {
                                DropdownMenuItem(
                                    text = { Text(s.syncDrive) },
                                    enabled = !google.isSyncing,
                                    onClick = {
                                        showGoogleActions = false
                                        onSyncGoogleDrive()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.restoreDrive) },
                                    enabled = !google.isSyncing,
                                    onClick = {
                                        showGoogleActions = false
                                        showRestoreConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.logoutGoogle) },
                                    onClick = {
                                        showGoogleActions = false
                                        onSignOutGoogle()
                                    },
                                )
                            }
                        }
                    }
                },
            ) {
                ProfileIdentitySection(
                    s = s,
                    name = google.accountName,
                    email = google.accountEmail,
                    photoUrl = google.accountPhotoUrl,
                    google = google,
                    onSignInGoogle = onSignInGoogle,
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
        if (automaticDetectionState.isSupported) {
            item {
                PanelCard(
                    title = s.automaticDetection.entryTitle,
                    icon = Icons.Default.Notifications,
                    headerTrailing = {
                        if (automaticDetectionState.pendingSuggestions.isNotEmpty()) {
                            Text(
                                s.automaticDetection.pendingCount.replace(
                                    "{count}",
                                    automaticDetectionState.pendingSuggestions.size.toString(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                ) {
                    Text(
                        s.automaticDetection.entryHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactPrimaryButton(
                            text = s.automaticDetection.settingsTitle,
                            compact = true,
                            subtle = true,
                            onClick = onOpenAutomaticDetectionSettings,
                        )
                        if (automaticDetectionState.pendingSuggestions.isNotEmpty()) {
                            CompactPrimaryButton(
                                text = s.automaticDetection.reviewSuggestion,
                                compact = true,
                                onClick = onOpenPendingSuggestions,
                            )
                        }
                    }
                }
            }
        }
        item {
            PanelCard(title = s.theme, icon = Icons.Default.Settings) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppToggleChip(selected = state.themeMode == "light", onClick = { onSetTheme("light") }, text = s.light)
                    AppToggleChip(selected = state.themeMode == "dark", onClick = { onSetTheme("dark") }, text = s.dark)
                    AppToggleChip(selected = state.themeMode == "amoled", onClick = { onSetTheme("amoled") }, text = s.amoled)
                }
            }
        }
        item {
            PanelCard(title = s.appVersion, icon = Icons.Default.Info) {
                val update = state.appUpdate
                val updateStatus = appUpdateStatusVisual(update, s)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "v${BuildInfo.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val secondaryLine =
                                when {
                                    update.isChecking -> s.updateStatusChecking
                                    update.isDownloading ->
                                        update.downloadProgressPercent?.let { "${s.updateDownloading}: $it%" }
                                            ?: s.updateDownloading
                                    update.isUpdateAvailable && update.latestVersion != null ->
                                        "${s.latestVersion}: v${update.latestVersion}"
                                    else -> updateStatus.label
                                }
                            Text(
                                secondaryLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (update.isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconCompactButton(
                                    onClick = onCheckForUpdates,
                                    icon = Icons.Default.Refresh,
                                    contentDescription = s.checkUpdates,
                                    enabled = update.isAvailable,
                                    compact = true,
                                    subtle = true,
                                )
                            }
                            IconCompactButton(
                                onClick = { showLicenses = true },
                                icon = Icons.Default.Info,
                                contentDescription = s.viewLicenses,
                                compact = true,
                                subtle = true,
                            )
                        }
                    }
                    if (update.lastError != null) {
                        Text(
                            update.lastError.ifBlank { s.updateCheckFailed },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (update.requiresInstallPermission) {
                        Text(
                            s.updateInstallPermission,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (update.isUpdateAvailable && update.downloadUrl != null) {
                        CompactPrimaryButton(
                            text =
                                when {
                                    update.isDownloading -> s.updateDownloading
                                    update.isUpdateDownloaded -> s.updateInstall
                                    else -> s.updateNow
                                },
                            enabled = !update.isDownloading,
                            loading = update.isDownloading,
                            compact = true,
                            squared = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onStartUpdate,
                        )
                    }
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
                s.restoreDrive,
                compact = true,
                squared = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showRestoreConfirm = false
                    onRestoreGoogleDrive(true)
                },
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
