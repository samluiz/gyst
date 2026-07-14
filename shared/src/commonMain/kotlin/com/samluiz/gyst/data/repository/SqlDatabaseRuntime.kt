package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SqlDriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseHolder(
    initialDriver: SqlDriver,
    private val driverFactory: SqlDriverFactory,
) {
    private val reloadMutex = Mutex()
    private var activeDriver: SqlDriver = initialDriver
    private var activeDb: GystDatabase = GystDatabase(initialDriver)

    val db: GystDatabase
        get() = activeDb

    suspend fun reloadDatabase() {
        reloadMutex.withLock {
            val newDriver = driverFactory.createDriver()
            val oldDriver = activeDriver
            activeDriver = newDriver
            activeDb = GystDatabase(newDriver)
            runCatching { oldDriver.close() }
        }
    }
}
