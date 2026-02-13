package com.samluiz.gyst.ios

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun iosPlatformModule(): Module = module {
    single<SqlDriver> {
        NativeSqliteDriver(GystDatabase.Schema, iosDbPath())
    }
    single<GoogleAuthSyncService> {
        IosLocalSyncService(
            dbPath = iosDbPath(),
            backupPath = iosBackupPath(),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosDocumentsDir(): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return (dirs.firstOrNull() as? String) ?: "."
}

private fun iosDbPath(): String = "${iosDocumentsDir()}/gyst.db"

@OptIn(ExperimentalForeignApi::class)
private fun iosBackupPath(): String {
    val backupDir = "${iosDocumentsDir()}/backup"
    NSFileManager.defaultManager.createDirectoryAtPath(backupDir, true, null, null)
    return "$backupDir/gyst-backup.db"
}
