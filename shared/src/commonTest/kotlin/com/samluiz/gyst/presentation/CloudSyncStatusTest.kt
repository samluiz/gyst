package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.service.GoogleSyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudSyncStatusTest {
    @Test
    fun returnsNullWhenSyncUnavailableOrSignedOut() {
        val unavailable = GoogleSyncState(isAvailable = false, isSignedIn = true)
        val signedOut = GoogleSyncState(isAvailable = true, isSignedIn = false)

        assertNull(cloudSyncStatus(unavailable))
        assertNull(cloudSyncStatus(signedOut))
    }

    @Test
    fun returnsNoBackupWhenCloudTimestampMissing() {
        val state =
            GoogleSyncState(
                isAvailable = true,
                isSignedIn = true,
                lastSyncAtIso = "2026-05-31T02:00:00Z",
                lastCloudBackupAtIso = null,
            )

        assertEquals(CloudSyncStatus.NO_BACKUP, cloudSyncStatus(state))
    }

    @Test
    fun returnsOutdatedWhenLocalTimestampOlderThanCloud() {
        val state =
            GoogleSyncState(
                isAvailable = true,
                isSignedIn = true,
                lastSyncAtIso = "2026-05-31T01:00:00Z",
                lastCloudBackupAtIso = "2026-05-31T02:00:00Z",
            )

        assertEquals(CloudSyncStatus.OUTDATED, cloudSyncStatus(state))
    }

    @Test
    fun returnsUpdatedWhenLocalTimestampEqualsCloud() {
        val state =
            GoogleSyncState(
                isAvailable = true,
                isSignedIn = true,
                lastSyncAtIso = "2026-05-31T02:00:00Z",
                lastCloudBackupAtIso = "2026-05-31T02:00:00Z",
            )

        assertEquals(CloudSyncStatus.UPDATED, cloudSyncStatus(state))
    }

    @Test
    fun returnsNoBackupWhenCloudTimestampInvalid() {
        val state =
            GoogleSyncState(
                isAvailable = true,
                isSignedIn = true,
                lastSyncAtIso = "2026-05-31T02:00:00Z",
                lastCloudBackupAtIso = "invalid",
            )

        assertEquals(CloudSyncStatus.NO_BACKUP, cloudSyncStatus(state))
    }
}
