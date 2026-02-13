package com.samluiz.gyst.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import org.koin.dsl.module

fun androidPlatformModule(context: Context): org.koin.core.module.Module = module {
    single<SqlDriver> {
        hardenLegacySchema(context)
        AndroidSqliteDriver(GystDatabase.Schema, context, "gyst.db")
    }
    single<GoogleAuthSyncService> {
        AndroidGoogleAuthSyncService(context.applicationContext)
    }
}

private fun hardenLegacySchema(context: Context) {
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

        db.setTransactionSuccessful()
    } catch (t: Throwable) {
        Log.e("GystDb", "Legacy schema hardening failed", t)
    } finally {
        db.endTransaction()
        db.close()
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
