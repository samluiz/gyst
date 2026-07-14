package com.samluiz.gyst.ios

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AppUpdateService
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.ImageSourceService
import com.samluiz.gyst.domain.service.NoOpAppUpdateService
import com.samluiz.gyst.domain.service.UnsupportedAutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.UnsupportedImageSourceService
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun iosPlatformModule(): Module =
    module {
        single<SqlDriverFactory> {
            object : SqlDriverFactory {
                override fun createDriver(): SqlDriver =
                    NativeSqliteDriver(
                        schema = GystDatabase.Schema,
                        name = IOS_DATABASE_NAME,
                        onConfiguration = { configuration ->
                            configuration.withGystIosIntegrity(iosDocumentsDir())
                        },
                    )
            }
        }
        single<SqlDriver> {
            get<SqlDriverFactory>().createDriver()
        }
        single<GoogleAuthSyncService> {
            IosLocalSyncService(
                dbPath = iosDbPath(),
                backupPath = iosBackupPath(),
            )
        }
        single<AppUpdateService> { NoOpAppUpdateService() }
        single<AdvisorSecretStore> { IosAdvisorSecretStore() }
        single<ImageSourceService> { UnsupportedImageSourceService() }
        single<AutomaticTransactionDetectionService> { UnsupportedAutomaticTransactionDetectionService() }
    }

@OptIn(ExperimentalForeignApi::class)
private fun iosDocumentsDir(): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return (dirs.firstOrNull() as? String) ?: "."
}

private fun iosDbPath(): String = "${iosDocumentsDir()}/$IOS_DATABASE_NAME"

internal fun DatabaseConfiguration.withGystIosIntegrity(basePath: String): DatabaseConfiguration =
    copy(
        extendedConfig =
            extendedConfig.copy(
                foreignKeyConstraints = true,
                basePath = basePath,
            ),
    )

@OptIn(ExperimentalForeignApi::class)
private fun iosBackupPath(): String {
    val backupDir = "${iosDocumentsDir()}/backup"
    NSFileManager.defaultManager.createDirectoryAtPath(backupDir, true, null, null)
    return "$backupDir/gyst-backup.db"
}

private const val IOS_DATABASE_NAME = "gyst.db"
