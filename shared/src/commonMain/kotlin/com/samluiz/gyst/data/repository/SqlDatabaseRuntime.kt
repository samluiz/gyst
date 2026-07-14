package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.samluiz.gyst.db.GystDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SqlDriverFactory {
    fun createDriver(): SqlDriver
}

class DatabaseHolder(
    initialDriver: SqlDriver,
    private val driverFactory: SqlDriverFactory,
) {
    /**
     * Serializes every use of the active database with driver replacement.
     *
     * SQLDelight queries are synchronous, so callers must keep [block] limited to database work.
     * The gate is intentionally not reentrant: a repository operation must acquire it once around
     * its complete read, write, or transaction instead of calling another gated repository method.
     */
    private val accessGate = Mutex()
    private var activeDriver: SqlDriver = initialDriver
    private var activeDb: GystDatabase = GystDatabase(initialDriver)

    internal suspend fun <T> withDatabase(block: (GystDatabase) -> T): T =
        accessGate.withLock {
            block(activeDb)
        }

    suspend fun reloadDatabase() {
        accessGate.withLock {
            val newDriver = driverFactory.createDriver()
            val newDb =
                try {
                    GystDatabase(newDriver)
                } catch (failure: Throwable) {
                    runCatching { newDriver.close() }
                    throw failure
                }
            val oldDriver = activeDriver
            activeDriver = newDriver
            activeDb = newDb
            runCatching { oldDriver.close() }
        }
    }

    /**
     * Replaces the database storage while no repository can access the active driver.
     *
     * [install] must leave either the replacement at the driver's normal location or throw.
     * When opening or migrating the replacement fails, [rollback] restores the previous storage
     * before a recovery driver is opened. [commit] is cleanup-only and runs after the migrated
     * replacement is active.
     */
    suspend fun replaceDatabase(
        install: () -> Unit,
        rollback: () -> Unit,
        commit: () -> Unit,
    ) {
        accessGate.withLock {
            val oldDriver = activeDriver
            oldDriver.close()
            try {
                install()
                val (newDriver, newDb) = openDatabase()
                activeDriver = newDriver
                activeDb = newDb
                runCatching(commit)
            } catch (replacementFailure: Throwable) {
                runCatching(rollback)
                    .onFailure(replacementFailure::addSuppressed)
                try {
                    val (recoveryDriver, recoveryDb) = openDatabase()
                    activeDriver = recoveryDriver
                    activeDb = recoveryDb
                } catch (recoveryFailure: Throwable) {
                    replacementFailure.addSuppressed(recoveryFailure)
                }
                throw replacementFailure
            }
        }
    }

    private fun openDatabase(): Pair<SqlDriver, GystDatabase> {
        val driver = driverFactory.createDriver()
        return try {
            val database = GystDatabase(driver)
            // Force representative legacy and v1.5 tables to be prepared before a restored file
            // is committed. Constructing a generated database alone does not validate its tables.
            database.financeQueries.selectAllCategories().executeAsList()
            database.financeQueries.selectAdvisorProviderProfiles().executeAsList()
            database.financeQueries.selectAdvisorConversations(includeArchived = 1L).executeAsList()
            database.financeQueries.selectAdvisorMessagesByConversation("__schema_probe__").executeAsList()
            database.financeQueries.selectTransactionImportSessions().executeAsList()
            database.financeQueries.selectTransactionImportSources("__schema_probe__").executeAsList()
            database.financeQueries.selectPendingTransactionCandidates().executeAsList()
            database.financeQueries.selectNotificationIngestionsForProcessing().executeAsList()
            database.financeQueries.selectMonitoredApplications().executeAsList()
            database.financeQueries.selectExpenseOriginByIdempotencyKey("__schema_probe__").executeAsOneOrNull()
            driver to database
        } catch (failure: Throwable) {
            runCatching { driver.close() }
            throw failure
        }
    }
}
