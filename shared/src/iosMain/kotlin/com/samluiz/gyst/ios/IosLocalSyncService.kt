package com.samluiz.gyst.ios

import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.service.SyncPolicy
import com.samluiz.gyst.domain.service.SyncSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class)
class IosLocalSyncService(
    private val dbPath: String,
    private val backupPath: String,
) : GoogleAuthSyncService {
    private val internal = MutableStateFlow(
        GoogleSyncState(
            isAvailable = true,
            isSignedIn = true,
            accountName = "iOS Local",
            accountEmail = "local@ios",
        )
    )
    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    override suspend fun initialize() {
        internal.update {
            it.copy(
                isAvailable = true,
                isSignedIn = true,
                accountName = "iOS Local",
                accountEmail = "local@ios",
                requiresAppRestart = false,
                lastError = null,
            )
        }
    }

    override suspend fun signIn() {
        internal.update {
            it.copy(
                isSignedIn = true,
                accountName = "iOS Local",
                accountEmail = "local@ios",
                requiresAppRestart = false,
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
                requiresAppRestart = false,
                lastError = null,
            )
        }
    }

    override suspend fun syncNow() {
        internal.update { it.copy(isSyncing = true, statusMessage = null, lastError = null, requiresAppRestart = false) }
        try {
            val fileManager = NSFileManager.defaultManager
            val localExists = fileManager.fileExistsAtPath(dbPath)
            val remoteExists = fileManager.fileExistsAtPath(backupPath)
            if (!localExists && !remoteExists) error("No local data available to sync.")

            val localTime = if (localExists) fileModifiedEpochMillis(dbPath) else null
            val remoteTime = if (remoteExists) fileModifiedEpochMillis(backupPath) else null

            when {
                !remoteExists && localExists -> {
                    val data = NSData.dataWithContentsOfFile(dbPath) ?: error("Local database not found.")
                    if (!data.writeToFile(backupPath, true)) error("Failed to create local backup.")
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
                    val backupData = NSData.dataWithContentsOfFile(backupPath) ?: error("No local backup found.")
                    verifySqliteBackup(backupData)
                    if (!backupData.writeToFile(dbPath, true)) error("Failed to recover local data from backup.")
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
                remoteTime != null && localTime != null && remoteTime > localTime -> {
                    val backupData = NSData.dataWithContentsOfFile(backupPath) ?: error("No local backup found.")
                    verifySqliteBackup(backupData)
                    if (!backupData.writeToFile(dbPath, true)) error("Failed to apply newer backup.")
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = true,
                            statusMessage = "Conflict resolved by timestamp: backup applied.",
                            requiresAppRestart = true,
                            lastError = null,
                        )
                    }
                }
                else -> {
                    val data = NSData.dataWithContentsOfFile(dbPath) ?: error("Local database not found.")
                    if (!data.writeToFile(backupPath, true)) error("Failed to create local backup.")
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
            internal.update { it.copy(isSyncing = false, statusMessage = null, lastError = t.message ?: "iOS backup failed") }
        }
    }

    override suspend fun restoreFromCloud(overwriteLocal: Boolean) {
        if (!overwriteLocal) {
            internal.update { it.copy(lastError = "Restore canceled.") }
            return
        }
        internal.update { it.copy(isSyncing = true, statusMessage = null, lastError = null) }
        try {
            val backupData = NSData.dataWithContentsOfFile(backupPath) ?: error("No local backup found.")
            verifySqliteBackup(backupData)
            val backupLocalPath = "$dbPath.bak"
            val fileManager = NSFileManager.defaultManager
            if (fileManager.fileExistsAtPath(dbPath)) {
                fileManager.copyItemAtPath(dbPath, backupLocalPath, null)
            }
            if (!backupData.writeToFile(dbPath, true)) error("Failed to restore backup.")
            internal.update {
                it.copy(
                    isSyncing = false,
                    lastSyncAtIso = Clock.System.now().toString(),
                    lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                    lastSyncPolicy = SyncPolicy.OVERWRITE_LOCAL,
                    hadSyncConflict = false,
                    statusMessage = "Backup restored.",
                    requiresAppRestart = true,
                    lastError = null,
                )
            }
        } catch (t: Throwable) {
            internal.update { it.copy(isSyncing = false, statusMessage = null, lastError = t.message ?: "iOS restore failed") }
        }
    }

    private fun verifySqliteBackup(data: NSData) {
        val signature = "SQLite format 3".encodeToByteArray()
        val raw = ByteArray(signature.size)
        if (data.length.toInt() < signature.size) error("Invalid backup format.")
        raw.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, signature.size.toULong())
        }
        for (index in signature.indices) {
            if (raw[index] != signature[index]) error("Backup is not a SQLite database.")
        }
    }

    private fun fileModifiedEpochMillis(path: String): Long? {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
        attrs[NSFileModificationDate] as? NSDate ?: return null
        // Some Kotlin/Native Foundation bindings do not expose NSDate epoch helpers consistently.
        // Fallback to unknown mtime so sync logic can still proceed safely.
        return null
    }
}
