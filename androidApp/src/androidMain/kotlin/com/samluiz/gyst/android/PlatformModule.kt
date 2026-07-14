package com.samluiz.gyst.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.samluiz.gyst.android.detection.AndroidDetectionPermissionGateway
import com.samluiz.gyst.android.detection.AndroidDetectionRuntimeInitializer
import com.samluiz.gyst.android.detection.AndroidInstalledApplicationCatalog
import com.samluiz.gyst.android.detection.AndroidKeystoreNotificationContentProtector
import com.samluiz.gyst.android.detection.AndroidNotificationDetectionCoordinator
import com.samluiz.gyst.android.detection.DetectedTransactionNotificationSink
import com.samluiz.gyst.android.detection.DetectedTransactionNotifier
import com.samluiz.gyst.android.detection.DetectionPermissionController
import com.samluiz.gyst.android.detection.InstalledApplicationSource
import com.samluiz.gyst.android.detection.NotificationAnalysisScheduler
import com.samluiz.gyst.android.detection.NotificationAnalysisScheduling
import com.samluiz.gyst.android.detection.NotificationContentProtector
import com.samluiz.gyst.data.repository.DatabaseRuntimeController
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AppUpdateService
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.ImageSourceService
import org.koin.dsl.module
import java.io.File

private const val DATABASE_NAME = "gyst.db"

fun androidPlatformModule(context: Context): org.koin.core.module.Module {
    recoverInterruptedDatabaseReplacement(context)
    return module {
        single<SqlDriverFactory> {
            object : SqlDriverFactory {
                override fun createDriver(): SqlDriver = createAndroidDriver(context)
            }
        }
        single<SqlDriver> {
            get<SqlDriverFactory>().createDriver()
        }
        single<GoogleAuthSyncService> {
            AndroidGoogleAuthSyncService(
                appContext = context.applicationContext,
                databaseRuntimeController = get<DatabaseRuntimeController>(),
            )
        }
        single<AppUpdateService> {
            AndroidAppUpdateService(context.applicationContext)
        }
        single<AdvisorSecretStore> { AndroidAdvisorSecretStore(context.applicationContext) }
        single { AndroidImageSourceService(context.applicationContext) }
        single<ImageSourceService> { get<AndroidImageSourceService>() }
        single { AndroidDetectionPermissionGateway(context.applicationContext) }
        single<DetectionPermissionController> { get<AndroidDetectionPermissionGateway>() }
        single { AndroidInstalledApplicationCatalog(context.applicationContext) }
        single<InstalledApplicationSource> { get<AndroidInstalledApplicationCatalog>() }
        single { NotificationAnalysisScheduler(context.applicationContext) }
        single<NotificationAnalysisScheduling> { get<NotificationAnalysisScheduler>() }
        single { DetectedTransactionNotifier(context.applicationContext) }
        single<DetectedTransactionNotificationSink> { get<DetectedTransactionNotifier>() }
        single<NotificationContentProtector> { AndroidKeystoreNotificationContentProtector() }
        single {
            AndroidNotificationDetectionCoordinator(
                settingsRepository = get(),
                monitoredApplicationRepository = get(),
                ingestionRepository = get(),
                candidateRepository = get(),
                candidateApprovalRepository = get(),
                categoryRepository = get(),
                providerProfileRepository = get(),
                secretStore = get(),
                providerClient = get(),
                permissionGateway = get(),
                applicationCatalog = get(),
                scheduler = get(),
                notifier = get(),
                contentProtector = get(),
            )
        }
        single<AutomaticTransactionDetectionService> { get<AndroidNotificationDetectionCoordinator>() }
        single<AndroidDetectionRuntimeInitializer> {
            val coordinator = get<AndroidNotificationDetectionCoordinator>()
            AndroidDetectionRuntimeInitializer { coordinator.install() }
        }
    }
}

private fun createAndroidDriver(context: Context): SqlDriver {
    hardenLegacySchema(context)
    val callback =
        object : AndroidSqliteDriver.Callback(GystDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                backupAndResetCorruptDatabase(context, "callback_corruption")
                super.onCorruption(db)
            }
        }
    val openHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DATABASE_NAME)
                .callback(callback)
                .build(),
        )
    openHelper.setWriteAheadLoggingEnabled(true)
    return AndroidSqliteDriver(
        openHelper = openHelper,
    )
}

private fun recoverInterruptedDatabaseReplacement(context: Context) {
    val database = context.getDatabasePath(DATABASE_NAME)
    val backup = File(database.parentFile, "${database.name}.bak")
    File(database.parentFile, "${database.name}.tmp").delete()
    if (!backup.exists()) return

    File("${database.path}-wal").delete()
    File("${database.path}-shm").delete()
    if (!database.exists()) {
        if (!backup.renameTo(database)) {
            Log.e("GystDb", "Failed to recover interrupted database replacement")
        }
        return
    }

    if (isCommittedDatabaseFile(database)) {
        backup.delete()
        return
    }

    database.delete()
    if (!backup.renameTo(database)) {
        Log.e("GystDb", "Failed to roll back interrupted database replacement")
    }
}

private fun isCommittedDatabaseFile(file: File): Boolean =
    runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val healthy =
                database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            val version = database.userVersion()
            healthy && version == GystDatabase.Schema.version.toInt()
        }
    }.getOrDefault(false)

private fun hardenLegacySchema(context: Context) {
    if (!isDatabaseHealthy(context)) {
        throw IllegalStateException("Database integrity check failed; the original file was preserved")
    }
    val db = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
    try {
        db.beginTransaction()
        if (!db.tableExists("expense")) {
            db.setTransactionSuccessful()
            return
        }

        val columns = db.tableColumns("expense")
        if (!columns.contains("recurrence_type")) {
            db.execSQL("ALTER TABLE expense ADD COLUMN recurrence_type TEXT NOT NULL DEFAULT 'ONE_TIME'")
        }
        if (!columns.contains("schedule_item_id")) {
            db.execSQL("ALTER TABLE expense ADD COLUMN schedule_item_id TEXT")
        }

        val version = db.userVersion()
        if (version < 2) {
            db.version = 2
        }

        db.setTransactionSuccessful()
    } catch (t: Throwable) {
        Log.e("GystDb", "Legacy schema hardening failed", t)
        throw t
    } finally {
        if (db.inTransaction()) {
            db.endTransaction()
        }
        db.close()
    }
}

private fun isDatabaseHealthy(context: Context): Boolean {
    val dbFile = context.getDatabasePath(DATABASE_NAME)
    if (!dbFile.exists()) return true
    val db =
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse {
            Log.e("GystDb", "Failed opening database for health check", it)
            return false
        }
    return db.use {
        runCatching {
            it.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
        }.getOrElse { error ->
            Log.e("GystDb", "quick_check failed", error)
            false
        }
    }
}

private fun backupAndResetCorruptDatabase(
    context: Context,
    reason: String,
) {
    val dbFile = context.getDatabasePath(DATABASE_NAME)
    if (!dbFile.exists()) return
    val backupDir = File(context.filesDir, "backup")
    runCatching { backupDir.mkdirs() }
    val stamp = System.currentTimeMillis()
    val backups =
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
        )
    backups.forEach { source ->
        if (!source.exists()) return@forEach
        val target = File(backupDir, "${source.name}.$reason.$stamp.bak")
        runCatching { source.copyTo(target, overwrite = true) }
            .onFailure { Log.e("GystDb", "Failed to backup ${source.name}", it) }
    }
    backups.forEach { source ->
        runCatching { source.delete() }
            .onFailure { Log.e("GystDb", "Failed to delete ${source.name} after backup", it) }
    }
}

private fun SQLiteDatabase.userVersion(): Int =
    rawQuery("PRAGMA user_version", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

private fun SQLiteDatabase.tableExists(tableName: String): Boolean =
    rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(tableName),
    ).use { cursor -> cursor.moveToFirst() }

private fun SQLiteDatabase.tableColumns(tableName: String): Set<String> {
    val cols = mutableSetOf<String>()
    rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
        while (cursor.moveToNext()) {
            cols += cursor.getString(1)
        }
    }
    return cols
}
