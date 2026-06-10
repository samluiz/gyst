package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.service.GoogleSyncState
import kotlin.time.Instant

enum class CloudSyncStatus {
    UPDATED,
    OUTDATED,
    NO_BACKUP,
}

fun cloudSyncStatus(google: GoogleSyncState): CloudSyncStatus? {
    if (!google.isAvailable || !google.isSignedIn) return null

    val cloud = parseInstantOrNull(google.lastCloudBackupAtIso)
    val local = parseInstantOrNull(google.lastSyncAtIso)
    return when {
        cloud == null -> CloudSyncStatus.NO_BACKUP
        local == null || local < cloud -> CloudSyncStatus.OUTDATED
        else -> CloudSyncStatus.UPDATED
    }
}

private fun parseInstantOrNull(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso) }.getOrNull()
}
