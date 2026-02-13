package com.samluiz.gyst.domain.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncPolicy {
    NEWEST_WINS,
    OVERWRITE_LOCAL,
}

enum class SyncSource {
    LOCAL_TO_CLOUD,
    CLOUD_TO_LOCAL,
}

data class GoogleSyncState(
    val isAvailable: Boolean,
    val isSignedIn: Boolean,
    val accountName: String? = null,
    val accountEmail: String? = null,
    val accountPhotoUrl: String? = null,
    val isAuthInProgress: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncAtIso: String? = null,
    val lastSyncSource: SyncSource? = null,
    val lastSyncPolicy: SyncPolicy = SyncPolicy.NEWEST_WINS,
    val hadSyncConflict: Boolean = false,
    val statusMessage: String? = null,
    val requiresAppRestart: Boolean = false,
    val lastError: String? = null,
)

interface GoogleAuthSyncService {
    val state: StateFlow<GoogleSyncState>

    suspend fun initialize()
    suspend fun signIn()
    suspend fun signOut()
    suspend fun syncNow()
    suspend fun restoreFromCloud(overwriteLocal: Boolean = true)
}

class NoOpGoogleAuthSyncService : GoogleAuthSyncService {
    private val internal = MutableStateFlow(
        GoogleSyncState(
            isAvailable = false,
            isSignedIn = false,
        )
    )

    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    override suspend fun initialize() = Unit
    override suspend fun signIn() = Unit
    override suspend fun signOut() = Unit
    override suspend fun syncNow() = Unit
    override suspend fun restoreFromCloud(overwriteLocal: Boolean) = Unit
}
