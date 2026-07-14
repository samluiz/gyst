package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseHolderConcurrencyTest {
    @Test
    fun reloadWaitsForAnActiveDatabaseOperation() =
        runBlocking {
            val initialDriver = driverWithMarker("old")
            val replacementDriver = driverWithMarker("new")
            val operationEntered = CountDownLatch(1)
            val releaseOperation = CountDownLatch(1)
            val reloadStarted = CountDownLatch(1)
            val factoryCalled = CountDownLatch(1)
            val holder =
                DatabaseHolder(
                    initialDriver = initialDriver,
                    driverFactory =
                        driverFactory {
                            factoryCalled.countDown()
                            replacementDriver
                        },
                )

            val activeOperation =
                async(Dispatchers.IO) {
                    holder.withDatabase { db ->
                        operationEntered.countDown()
                        check(releaseOperation.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                    }
                }
            assertTrue(operationEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val reload =
                async(Dispatchers.IO) {
                    reloadStarted.countDown()
                    holder.reloadDatabase()
                }
            assertTrue(reloadStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(factoryCalled.await(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))

            releaseOperation.countDown()
            assertEquals("old", activeOperation.await())
            reload.await()
            assertTrue(factoryCalled.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(
                "new",
                holder.withDatabase { db ->
                    db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                },
            )

            replacementDriver.close()
        }

    @Test
    fun databaseOperationWaitsUntilReloadFinishes() =
        runBlocking {
            val initialDriver = driverWithMarker("old")
            val replacementDriver = driverWithMarker("new")
            val factoryEntered = CountDownLatch(1)
            val releaseFactory = CountDownLatch(1)
            val operationStarted = CountDownLatch(1)
            val operationEntered = CountDownLatch(1)
            val holder =
                DatabaseHolder(
                    initialDriver = initialDriver,
                    driverFactory =
                        driverFactory {
                            factoryEntered.countDown()
                            check(releaseFactory.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            replacementDriver
                        },
                )

            val reload = async(Dispatchers.IO) { holder.reloadDatabase() }
            assertTrue(factoryEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val operation =
                async(Dispatchers.IO) {
                    operationStarted.countDown()
                    holder.withDatabase { db ->
                        operationEntered.countDown()
                        db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                    }
                }
            assertTrue(operationStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(operationEntered.await(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))

            releaseFactory.countDown()
            reload.await()
            assertEquals("new", operation.await())
            assertTrue(operationEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            replacementDriver.close()
        }

    @Test
    fun failedReloadKeepsTheExistingDatabaseAvailable() =
        runBlocking {
            val initialDriver = driverWithMarker("old")
            val holder =
                DatabaseHolder(
                    initialDriver = initialDriver,
                    driverFactory = driverFactory { error("replacement failed") },
                )

            val failure = runCatching { holder.reloadDatabase() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(
                "old",
                holder.withDatabase { db ->
                    db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                },
            )
            initialDriver.close()
        }

    @Test
    fun replacementCommitsOnlyAfterTheNewDatabaseIsOpen() =
        runBlocking {
            val initialDriver = driverWithMarker("old")
            val replacementDriver = driverWithMarker("new")
            val events = mutableListOf<String>()
            val holder = DatabaseHolder(initialDriver, driverFactory { replacementDriver })

            holder.replaceDatabase(
                install = { events += "install" },
                rollback = { events += "rollback" },
                commit = { events += "commit" },
            )

            assertEquals(listOf("install", "commit"), events)
            assertEquals(
                "new",
                holder.withDatabase { db ->
                    db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                },
            )
            replacementDriver.close()
        }

    @Test
    fun failedReplacementRollsBackAndReopensThePreviousStorage() =
        runBlocking {
            val initialDriver = driverWithMarker("old")
            val recoveredDriver = driverWithMarker("old")
            val events = mutableListOf<String>()
            val holder = DatabaseHolder(initialDriver, driverFactory { recoveredDriver })

            assertFailsWith<IllegalStateException> {
                holder.replaceDatabase(
                    install = {
                        events += "install"
                        error("install failed")
                    },
                    rollback = { events += "rollback" },
                    commit = { events += "commit" },
                )
            }

            assertEquals(listOf("install", "rollback"), events)
            assertEquals(
                "old",
                holder.withDatabase { db ->
                    db.financeQueries.getAppSetting(MARKER_KEY).executeAsOne()
                },
            )
            recoveredDriver.close()
        }

    private fun driverWithMarker(marker: String): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        GystDatabase(driver).financeQueries.upsertAppSetting(
            value_ = marker,
            key = MARKER_KEY,
            key_ = MARKER_KEY,
            value__ = marker,
        )
        return driver
    }

    private fun driverFactory(create: () -> SqlDriver): SqlDriverFactory =
        object : SqlDriverFactory {
            override fun createDriver(): SqlDriver = create()
        }

    private companion object {
        const val MARKER_KEY = "database-holder-test-marker"
        const val TEST_TIMEOUT_SECONDS = 5L
        const val SHORT_WAIT_MILLIS = 150L
    }
}
