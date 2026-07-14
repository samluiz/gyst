package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SqlProviderProfileRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SqlProviderProfileRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        repository = SqlProviderProfileRepository(DatabaseHolder(driver, NoReloadDriverFactory))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun visionQueryUsesExplicitCapabilitiesAndNeverStoresASecret() =
        runTest {
            repository.upsert(profile("text", "custom", vision = false))
            repository.upsert(profile("vision", "gemini", vision = true))

            assertEquals(listOf("vision"), repository.listVisionCapable().map { it.id })
            assertEquals(
                listOf("vision"),
                repository.listSupporting(
                    ProviderCapabilities(visionInput = true, structuredOutput = true),
                ).map { it.id },
            )
            assertEquals("gemini", repository.get("vision")?.providerId)
        }

    private fun profile(
        id: String,
        providerId: String,
        vision: Boolean,
    ) = ProviderProfile(
        id = id,
        providerId = providerId,
        displayName = id,
        baseUrl = "https://example.com/v1",
        model = "model",
        apiFormat = "CHAT_COMPLETIONS",
        capabilities =
            ProviderCapabilities(
                textGeneration = true,
                visionInput = vision,
                structuredOutput = vision,
            ),
        active = true,
        createdAt = Instant.parse("2026-07-14T00:00:00Z"),
        updatedAt = Instant.parse("2026-07-14T00:00:00Z"),
    )

    private object NoReloadDriverFactory : SqlDriverFactory {
        override fun createDriver(): SqlDriver = error("Not used")
    }
}
