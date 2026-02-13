package com.samluiz.gyst.domain.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GoogleSyncState(
    val isAvailable: Boolean,
    val isSignedIn: Boolean,
    val accountEmail: String? = null,
    val isSyncing: Boolean = false,
    val lastSyncAtIso: String? = null,
    val lastError: String? = null,
)

interface GoogleAuthSyncService {
    val state: StateFlow<GoogleSyncState>

    suspend fun initialize()
    suspend fun signIn()
    suspend fun signOut()
    suspend fun syncNow()
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
}
