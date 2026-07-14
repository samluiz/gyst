package com.samluiz.gyst.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AndroidDatabaseStartupInstrumentationTest {
    @Test
    fun applicationDatabaseUsesSupportedConnectionConfiguration() {
        val driver = GlobalContext.get().get<SqlDriver>()

        assertEquals("wal", driver.querySingleString("PRAGMA journal_mode")?.lowercase())
        assertEquals("1", driver.querySingleString("PRAGMA foreign_keys"))
    }
}

private fun SqlDriver.querySingleString(sql: String): String? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) cursor.getString(0) else null,
            )
        },
        parameters = 0,
        binders = null,
    ).value
