package com.samluiz.gyst.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.presentation.CloudSyncStatus
import com.samluiz.gyst.presentation.cloudSyncStatus

internal data class OpenSourceLibrary(val name: String, val license: String, val url: String)

internal object OpenSourceLibraries {
    val entries =
        listOf(
            OpenSourceLibrary("Kotlin", "Apache-2.0", "https://kotlinlang.org"),
            OpenSourceLibrary("Compose Multiplatform", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
            OpenSourceLibrary("Koin", "Apache-2.0", "https://github.com/InsertKoinIO/koin"),
            OpenSourceLibrary("SQLDelight", "Apache-2.0", "https://github.com/cashapp/sqldelight"),
            OpenSourceLibrary("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
            OpenSourceLibrary("kotlinx.datetime", "Apache-2.0", "https://github.com/Kotlin/kotlinx-datetime"),
            OpenSourceLibrary("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
            OpenSourceLibrary(
                "Multiplatform Markdown Renderer",
                "Apache-2.0",
                "https://github.com/mikepenz/multiplatform-markdown-renderer",
            ),
        )
}

@Composable
internal fun ProfileIdentitySection(
    s: AppStrings,
    name: String?,
    email: String?,
    photoUrl: String?,
    google: GoogleSyncState,
    onSignInGoogle: () -> Unit,
) {
    val displayName = name?.takeIf { it.isNotBlank() } ?: s.guestUser
    val displayEmail = email?.takeIf { it.isNotBlank() } ?: s.noGoogleConnected
    val initial = displayName.firstOrNull()?.uppercase() ?: "G"
    val remotePhoto = rememberRemoteProfileImage(photoUrl)
    var showSyncTooltip by remember { mutableStateOf(false) }
    val syncBadge =
        if (google.isAvailable && google.isSignedIn) {
            when (cloudSyncStatus(google)) {
                CloudSyncStatus.UPDATED ->
                    SyncBadgeVisual(
                        label = s.cloudSyncUpdated,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        dotColor = MaterialTheme.colorScheme.primary,
                    )
                CloudSyncStatus.OUTDATED ->
                    SyncBadgeVisual(
                        label = s.cloudSyncOutdated,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        dotColor = MaterialTheme.colorScheme.error,
                    )
                CloudSyncStatus.NO_BACKUP ->
                    SyncBadgeVisual(
                        label = s.cloudSyncNoBackup,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                null -> null
            }
        } else {
            null
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                )
            } else {
                Text(initial, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (syncBadge != null) {
                    Box {
                        Row(
                            modifier =
                                Modifier
                                    .background(syncBadge.containerColor, RoundedCornerShape(999.dp))
                                    .border(1.dp, syncBadge.borderColor, RoundedCornerShape(999.dp))
                                    .clickable { showSyncTooltip = true }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = s.cloudSyncStatus,
                                tint = syncBadge.dotColor,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showSyncTooltip,
                            onDismissRequest = { showSyncTooltip = false },
                            modifier =
                                Modifier
                                    .widthIn(min = 220.dp, max = 300.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(syncBadge.label, style = MaterialTheme.typography.labelMedium)
                                        google.lastCloudBackupAtIso?.let {
                                            Text(
                                                "${s.cloudBackupAt}: ${formatInstantHuman(it, s.languageCode)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        google.lastSyncAtIso?.let {
                                            Text(
                                                "${s.syncedAt}: ${formatInstantHuman(it, s.languageCode)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = { showSyncTooltip = false },
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    displayEmail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                if (google.isSignedIn) {
                    GoogleMark(modifier = Modifier.size(14.dp))
                }
            }
            if (!google.isAvailable && google.lastError == null) {
                Text(
                    s.syncUnavailable,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val feedback = googleFeedbackLabel(google, s)
                if (feedback != null && google.isSyncing) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (google.isSyncing) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    )
                }
                if (google.hadSyncConflict) {
                    Text(
                        s.conflictResolvedByPolicy,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (google.requiresAppRestart) {
                    Text(
                        s.restartAppToApply,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val localizedError = googleErrorLabel(google, s)
                if (localizedError != null) {
                    Text(
                        localizedError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (google.isAvailable && !google.isSignedIn) {
        CompactPrimaryButton(
            s.loginGoogle,
            loading = google.isAuthInProgress,
            compact = true,
            squared = true,
            leadingContent = { GoogleMark() },
            modifier = Modifier.fillMaxWidth(),
            onClick = onSignInGoogle,
        )
    }
}
