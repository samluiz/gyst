package com.samluiz.gyst.presentation

import com.samluiz.gyst.data.repository.DatabaseRuntimeController
import com.samluiz.gyst.domain.service.AppUpdateService
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.usecase.SeedDataInitializer

internal class StoreSyncActions(
    private val googleAuthSyncService: GoogleAuthSyncService,
    private val appUpdateService: AppUpdateService,
    private val databaseRuntimeController: DatabaseRuntimeController,
    private val seedDataInitializer: SeedDataInitializer,
    private val persistentRuntimeCoordinator: PersistentRuntimeCoordinator,
    private val setState: (MainState) -> Unit,
    private val getState: () -> MainState,
    private val refresh: suspend (Boolean) -> Unit,
) {
    suspend fun signInGoogle() = googleAuthSyncService.signIn()

    suspend fun signOutGoogle() = googleAuthSyncService.signOut()

    suspend fun checkForUpdates() = appUpdateService.checkForUpdates(silent = false)

    suspend fun startUpdate() = appUpdateService.startUpdate()

    suspend fun syncGoogleDrive(applyHotReloadIfNeeded: suspend (String, Boolean) -> Boolean) {
        persistentRuntimeCoordinator.whileDatabaseIsQuiesced {
            googleAuthSyncService.syncNow()
            applyHotReloadIfNeeded("sync", false)
        }
        seedDataInitializer.ensureSeedData()
        refresh(false)
    }

    suspend fun restoreFromGoogleDrive(
        overwriteLocal: Boolean,
        blockingRestoreToken: String,
        applyHotReloadIfNeeded: suspend (String, Boolean) -> Boolean,
    ) {
        setState(getState().copy(isLoading = true, blockingMessage = blockingRestoreToken))
        try {
            persistentRuntimeCoordinator.whileDatabaseIsQuiesced {
                googleAuthSyncService.restoreFromCloud(overwriteLocal)
                applyHotReloadIfNeeded("restore", false)
            }
            seedDataInitializer.ensureSeedData()
            refresh(false)
        } finally {
            setState(getState().copy(blockingMessage = null))
        }
    }

    suspend fun applyHotReload(reason: String): Boolean {
        if (!googleAuthSyncService.state.value.requiresAppRestart) return false
        databaseRuntimeController.reloadDatabase()
        googleAuthSyncService.initialize()
        return true
    }
}
