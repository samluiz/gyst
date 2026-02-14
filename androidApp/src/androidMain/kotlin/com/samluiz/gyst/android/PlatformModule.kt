package com.samluiz.gyst.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import org.koin.dsl.module
import java.io.File

fun androidPlatformModule(context: Context): org.koin.core.module.Module = module {
    single<SqlDriverFactory> {
        object : SqlDriverFactory {
            override fun createDriver(): SqlDriver = createAndroidDriver(context)
        }
    }
    single<SqlDriver> {
        get<SqlDriverFactory>().createDriver()
    }
    single<GoogleAuthSyncService> {
        AndroidGoogleAuthSyncService(context.applicationContext)
    }
}

private fun createAndroidDriver(context: Context): SqlDriver {
    hardenLegacySchema(context)
    return AndroidSqliteDriver(
        schema = GystDatabase.Schema,
        context = context,
        name = "gyst.db",
        callback = object : AndroidSqliteDriver.Callback(GystDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                runCatching {
                    db.execSQL("PRAGMA foreign_keys=ON")
                    db.execSQL("PRAGMA journal_mode=WAL")
                    db.execSQL("PRAGMA synchronous=NORMAL")
                }.onFailure { Log.e("GystDb", "Failed to apply SQLite PRAGMA on open", it) }
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                backupAndResetCorruptDatabase(context, "callback_corruption")
                super.onCorruption(db)
            }
        },
    )
}

private fun hardenLegacySchema(context: Context) {
    if (!isDatabaseHealthy(context)) {
        backupAndResetCorruptDatabase(context, "quick_check_failed")
    }
    val db = context.openOrCreateDatabase("gyst.db", Context.MODE_PRIVATE, null)
    db.beginTransaction()
    try {
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
            db.execSQL("PRAGMA user_version = 2")
        }
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA synchronous=NORMAL")

        db.setTransactionSuccessful()
    } catch (t: Throwable) {
        Log.e("GystDb", "Legacy schema hardening failed", t)
    } finally {
        db.endTransaction()
        db.close()
    }
}

private fun isDatabaseHealthy(context: Context): Boolean {
    val dbFile = context.getDatabasePath("gyst.db")
    if (!dbFile.exists()) return true
    val db = runCatching {
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

private fun backupAndResetCorruptDatabase(context: Context, reason: String) {
    val dbFile = context.getDatabasePath("gyst.db")
    if (!dbFile.exists()) return
    val backupDir = File(context.filesDir, "backup")
    runCatching { backupDir.mkdirs() }
    val stamp = System.currentTimeMillis()
    val backups = listOf(
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
