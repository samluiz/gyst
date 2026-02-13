package com.samluiz.gyst.desktop

import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.service.SyncPolicy
import com.samluiz.gyst.domain.service.SyncSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.time.Clock
import kotlin.time.Instant

class DesktopGoogleAuthSyncService(
    private val dbPath: Path,
    private val backupPath: Path,
) : GoogleAuthSyncService {
    private val internal = MutableStateFlow(
        GoogleSyncState(
            isAvailable = true,
            isSignedIn = true,
            accountName = "Desktop Local",
            accountEmail = "local@desktop",
        )
    )
    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    override suspend fun initialize() {
        internal.update {
            it.copy(
                isAvailable = true,
                isSignedIn = true,
                accountName = "Desktop Local",
                accountEmail = "local@desktop",
                lastError = null,
            )
        }
    }

    override suspend fun signIn() {
        internal.update {
            it.copy(
                isSignedIn = true,
                accountName = "Desktop Local",
                accountEmail = "local@desktop",
                lastError = null,
            )
        }
    }

    override suspend fun signOut() {
        internal.update {
            it.copy(
                isSignedIn = false,
                accountName = null,
                accountEmail = null,
                lastError = null,
            )
        }
    }

    override suspend fun syncNow() {
        internal.update { it.copy(isSyncing = true, lastError = null, statusMessage = null, requiresAppRestart = false) }
        try {
            Files.createDirectories(backupPath.parent)

            val localExists = dbPath.exists()
            val remoteExists = backupPath.exists()
            if (!localExists && !remoteExists) error("No local data available to sync.")

            val localUpdatedAt = if (localExists) fileUpdatedAt(dbPath) else null
            val remoteUpdatedAt = if (remoteExists) fileUpdatedAt(backupPath) else null

            when {
                !remoteExists && localExists -> {
                    Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = false,
                            statusMessage = "Synced local data to backup.",
                            lastError = null,
                        )
                    }
                }
                !localExists && remoteExists -> {
                    Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = false,
                            statusMessage = "Recovered local data from backup.",
                            requiresAppRestart = true,
                            lastError = null,
                        )
                    }
                }
                remoteUpdatedAt != null && localUpdatedAt != null && remoteUpdatedAt > localUpdatedAt -> {
                    Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = true,
                            statusMessage = "Conflict resolved by timestamp: backup was newer.",
                            requiresAppRestart = true,
                            lastError = null,
                        )
                    }
                }
                else -> {
                    Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = false,
                            statusMessage = "Synced local data to backup.",
                            lastError = null,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            internal.update {
                it.copy(isSyncing = false, statusMessage = null, lastError = t.message ?: "Desktop backup failed")
            }
        }
    }

    override suspend fun restoreFromCloud(overwriteLocal: Boolean) {
        if (!overwriteLocal) {
            internal.update { it.copy(lastError = "Restore canceled.") }
            return
        }
        internal.update { it.copy(isSyncing = true, lastError = null, statusMessage = null) }
        try {
            if (!backupPath.exists()) error("No local backup found.")
            verifySqliteFile(backupPath)
            val backupLocal = dbPath.resolveSibling("${dbPath.fileName}.bak")
            Files.createDirectories(dbPath.parent)
            if (Files.exists(dbPath)) {
                Files.copy(dbPath, backupLocal, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching {
                Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING)
            }.onFailure {
                if (Files.exists(backupLocal)) {
                    Files.copy(backupLocal, dbPath, StandardCopyOption.REPLACE_EXISTING)
                }
                throw it
            }
            internal.update {
                it.copy(
                    isSyncing = false,
                    lastSyncAtIso = Clock.System.now().toString(),
                    lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                    lastSyncPolicy = SyncPolicy.OVERWRITE_LOCAL,
                    hadSyncConflict = false,
                    statusMessage = "Backup restored. Restart app to apply data.",
                    requiresAppRestart = true,
                    lastError = null,
                )
            }
        } catch (t: Throwable) {
            internal.update {
                it.copy(isSyncing = false, statusMessage = null, lastError = t.message ?: "Desktop restore failed")
            }
        }
    }

    private fun verifySqliteFile(path: Path) {
        val bytes = Files.readAllBytes(path)
        val signature = "SQLite format 3".encodeToByteArray()
        if (bytes.size < signature.size) error("Invalid backup format.")
        for (index in signature.indices) {
            if (bytes[index] != signature[index]) error("Backup is not a SQLite database.")
        }
    }

    private fun fileUpdatedAt(path: Path): Instant {
        val millis = Files.getLastModifiedTime(path).toMillis()
        return Instant.fromEpochMilliseconds(millis)
    }
}
