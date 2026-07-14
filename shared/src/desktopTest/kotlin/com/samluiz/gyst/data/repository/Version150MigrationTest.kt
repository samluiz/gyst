package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Version150MigrationTest {
    @Test
    fun migrationFromProductionV7PreservesAllLegacyDataAndAddsDurableWorkflows() {
        val directory = Files.createTempDirectory("gyst-v7-migration-")
        val databasePath = directory.resolve("finance.db")
        val databaseUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

        try {
            createFaithfulProductionV7Database(databaseUrl)
            verifyDatabaseBeforeUpgrade(databaseUrl)
            migrateDatabase(databaseUrl)
            verifyMigratedDatabase(databaseUrl)
        } finally {
            deleteDatabaseFiles(databasePath)
            Files.deleteIfExists(directory)
        }
    }

    /** Creates v7 from a schema frozen from the actual v1.4.4 release tag. */
    private fun createFaithfulProductionV7Database(databaseUrl: String) {
        val schema =
            checkNotNull(javaClass.getResourceAsStream(V1_4_4_SCHEMA_RESOURCE)) {
                "Missing frozen v1.4.4 schema fixture"
            }.bufferedReader().use { reader ->
                reader.lineSequence()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
            }
        DriverManager.getConnection("$databaseUrl?foreign_keys=on").use { connection ->
            schema
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEachIndexed { index, statement ->
                    try {
                        connection.createStatement().use { it.execute(statement) }
                    } catch (error: Exception) {
                        throw IllegalStateException(
                            "Failed frozen v1.4.4 schema statement #$index: ${statement.take(120)}",
                            error,
                        )
                    }
                }
        }

        openDriver(databaseUrl).use { driver ->
            seedLegacyUserData(driver)

            assertEquals(7L, scalar(driver, "PRAGMA user_version"))
            assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    private fun verifyDatabaseBeforeUpgrade(databaseUrl: String) {
        openDriver(databaseUrl).use { driver ->
            assertEquals(7L, scalar(driver, "PRAGMA user_version"))
            assertEquals(2L, scalar(driver, "SELECT COUNT(*) FROM category"))
            assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM expense"))
            assertEquals(2L, scalar(driver, "SELECT COUNT(*) FROM payment_schedule_item"))
            assertTrue(installedObjects(driver, "table").intersect(V8_TABLES).isEmpty())
        }
    }

    private fun migrateDatabase(databaseUrl: String) {
        openDriver(databaseUrl).use { driver ->
            assertEquals(7L, scalar(driver, "PRAGMA user_version"))

            GystDatabase.Schema.migrate(
                driver = driver,
                oldVersion = 7,
                newVersion = GystDatabase.Schema.version,
            )
            driver.execute(null, "PRAGMA user_version=${GystDatabase.Schema.version}", 0)
        }
    }

    private fun verifyMigratedDatabase(databaseUrl: String) {
        openDriver(databaseUrl).use { driver ->
            assertEquals(8L, GystDatabase.Schema.version)
            assertEquals(GystDatabase.Schema.version, scalar(driver, "PRAGMA user_version"))

            val database = GystDatabase(driver)
            assertLegacyUserDataSurvived(database)
            assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM pragma_foreign_key_check"))

            val tables = installedObjects(driver, "table")
            assertTrue(tables.containsAll(V8_TABLES), "Missing v1.5.0 tables: ${V8_TABLES - tables}")

            val indexes = installedObjects(driver, "index")
            assertTrue(indexes.containsAll(V8_INDEXES), "Missing v1.5.0 indexes: ${V8_INDEXES - indexes}")

            assertNewForeignKeysCascadesAndUniqueness(driver)
            assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    private fun seedLegacyUserData(driver: JdbcSqliteDriver) {
        val statements =
            listOf(
                """
                INSERT INTO category(id, name, type, color, icon)
                VALUES ('category-housing', 'Moradia', 'ESSENTIAL', '#6750A4', 'home')
                """.trimIndent(),
                """
                INSERT INTO category(id, name, type, color, icon)
                VALUES ('category-leisure', 'Lazer', 'VARIABLE', '#386A20', 'celebration')
                """.trimIndent(),
                """
                INSERT INTO budget_month(id, year_month, total_income_cents, created_at)
                VALUES ('budget-2026-07', '2026-07', 1200000, '2026-07-01T00:00:00Z')
                """.trimIndent(),
                """
                INSERT INTO budget_allocation(id, budget_month_id, category_id, planned_cents)
                VALUES ('allocation-housing', 'budget-2026-07', 'category-housing', 325000)
                """.trimIndent(),
                """
                INSERT INTO recurring_expense_series(
                    id, start_year_month, end_year_month, day_of_month, amount_cents,
                    category_id, note, merchant, payment_method, active
                ) VALUES (
                    'series-rent', '2026-01', NULL, 5, 250000,
                    'category-housing', 'Aluguel mensal', 'Imobiliaria Central', 'PIX', 1
                )
                """.trimIndent(),
                """
                INSERT INTO expense(
                    id, occurred_at, amount_cents, category_id, note, merchant,
                    payment_method, recurrence_type, created_at, schedule_item_id,
                    recurrence_series_id
                ) VALUES (
                    'expense-rent-july', '2026-07-05', 250000, 'category-housing',
                    'Aluguel mensal', 'Imobiliaria Central', 'PIX', 'MONTHLY',
                    '2026-07-05T12:34:56Z', NULL, 'series-rent'
                )
                """.trimIndent(),
                """
                INSERT INTO subscription(
                    id, name, amount_cents, billing_day, category_id, active, start_year_month
                ) VALUES (
                    'subscription-music', 'Musica Premium', 1990, 14,
                    'category-leisure', 1, '2025-02'
                )
                """.trimIndent(),
                """
                INSERT INTO installment_plan(
                    id, name, total_installments, total_amount_cents, monthly_amount_cents,
                    start_year_month, end_year_month, category_id, active
                ) VALUES (
                    'installment-notebook', 'Notebook', 12, 720000, 60000,
                    '2026-01', '2026-12', 'category-leisure', 1
                )
                """.trimIndent(),
                """
                INSERT INTO payment_schedule_item(
                    id, kind, ref_id, category_id, subscription_id, installment_plan_id,
                    due_date, amount_cents, status, paid_at
                ) VALUES (
                    'schedule-music-july', 'SUBSCRIPTION', 'subscription-music',
                    'category-leisure', 'subscription-music', NULL,
                    '2026-07-14', 1990, 'PAID', '2026-07-14T08:00:00Z'
                )
                """.trimIndent(),
                """
                INSERT INTO payment_schedule_item(
                    id, kind, ref_id, category_id, subscription_id, installment_plan_id,
                    due_date, amount_cents, status, paid_at
                ) VALUES (
                    'schedule-notebook-july', 'INSTALLMENT', 'installment-notebook',
                    'category-leisure', NULL, 'installment-notebook',
                    '2026-07-20', 60000, 'DUE', NULL
                )
                """.trimIndent(),
                "INSERT INTO app_setting(key, value) VALUES ('app.language', 'pt')",
                "INSERT INTO app_setting(key, value) VALUES ('app.theme', 'dark')",
            )
        statements.forEach { statement -> driver.execute(null, statement, 0) }
    }

    private fun assertLegacyUserDataSurvived(database: GystDatabase) {
        val queries = database.financeQueries

        val categories = queries.selectAllCategories().executeAsList()
        assertEquals(2, categories.size)
        val housing = categories.single { it.id == "category-housing" }
        assertEquals("Moradia", housing.name)
        assertEquals("ESSENTIAL", housing.type)
        assertEquals("#6750A4", housing.color)
        assertEquals("home", housing.icon)

        val budget = queries.findBudgetMonthByYearMonth("2026-07").executeAsOne()
        assertEquals("budget-2026-07", budget.id)
        assertEquals(1_200_000L, budget.total_income_cents)
        assertEquals("2026-07-01T00:00:00Z", budget.created_at)

        val allocation = queries.selectBudgetAllocationsByMonth(budget.id).executeAsOne()
        assertEquals("allocation-housing", allocation.id)
        assertEquals("category-housing", allocation.category_id)
        assertEquals(325_000L, allocation.planned_cents)
        assertEquals("Moradia", allocation.category_name)

        val series = queries.selectRecurringExpenseSeriesById("series-rent").executeAsOne()
        assertEquals("2026-01", series.start_year_month)
        assertEquals(null, series.end_year_month)
        assertEquals(5L, series.day_of_month)
        assertEquals(250_000L, series.amount_cents)
        assertEquals("Imobiliaria Central", series.merchant)
        assertEquals(1L, series.active)

        val expense = queries.selectExpenseById("expense-rent-july").executeAsOne()
        assertEquals("2026-07-05", expense.occurred_at)
        assertEquals(250_000L, expense.amount_cents)
        assertEquals("category-housing", expense.category_id)
        assertEquals("Aluguel mensal", expense.note)
        assertEquals("Imobiliaria Central", expense.merchant)
        assertEquals("PIX", expense.payment_method)
        assertEquals("MONTHLY", expense.recurrence_type)
        assertEquals("series-rent", expense.recurrence_series_id)

        val subscription = queries.selectSubscriptions().executeAsList().single()
        assertEquals("subscription-music", subscription.id)
        assertEquals("Musica Premium", subscription.name)
        assertEquals(1_990L, subscription.amount_cents)
        assertEquals(14L, subscription.billing_day)
        assertEquals("2025-02", subscription.start_year_month)
        assertEquals(1L, subscription.active)

        val installment = queries.selectInstallmentPlans().executeAsList().single()
        assertEquals("installment-notebook", installment.id)
        assertEquals(12L, installment.total_installments)
        assertEquals(720_000L, installment.total_amount_cents)
        assertEquals(60_000L, installment.monthly_amount_cents)
        assertEquals("2026-01", installment.start_year_month)
        assertEquals("2026-12", installment.end_year_month)
        assertEquals(1L, installment.active)

        val schedules =
            queries.selectScheduleByDateRange("2026-07-01", "2026-07-31").executeAsList()
        assertEquals(2, schedules.size)
        val subscriptionSchedule = schedules.single { it.id == "schedule-music-july" }
        assertEquals("SUBSCRIPTION", subscriptionSchedule.kind)
        assertEquals("subscription-music", subscriptionSchedule.subscription_id)
        assertEquals(null, subscriptionSchedule.installment_plan_id)
        assertEquals(1_990L, subscriptionSchedule.amount_cents)
        assertEquals("PAID", subscriptionSchedule.status)
        assertEquals("2026-07-14T08:00:00Z", subscriptionSchedule.paid_at)
        val installmentSchedule = schedules.single { it.id == "schedule-notebook-july" }
        assertEquals("INSTALLMENT", installmentSchedule.kind)
        assertEquals(null, installmentSchedule.subscription_id)
        assertEquals("installment-notebook", installmentSchedule.installment_plan_id)
        assertEquals(60_000L, installmentSchedule.amount_cents)
        assertEquals("DUE", installmentSchedule.status)

        assertEquals("pt", queries.getAppSetting("app.language").executeAsOne())
        assertEquals("dark", queries.getAppSetting("app.theme").executeAsOne())
    }

    private fun assertNewForeignKeysCascadesAndUniqueness(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            INSERT INTO advisor_conversation(id, title, title_source, created_at, updated_at)
            VALUES ('conversation-cascade', 'Migration test', 'MANUAL', '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z')
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO advisor_message(
                id, conversation_id, sequence_number, exchange_id, role, content,
                status, created_at, updated_at
            ) VALUES (
                'message-cascade', 'conversation-cascade', 0, 'exchange-cascade',
                'USER', 'Teste', 'COMPLETED', '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "DELETE FROM advisor_conversation WHERE id='conversation-cascade'", 0)
        assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM advisor_message WHERE id='message-cascade'"))

        assertFailsWith<Exception> {
            driver.execute(
                null,
                """
                INSERT INTO advisor_message(
                    id, conversation_id, sequence_number, role, content, status, created_at, updated_at
                ) VALUES (
                    'message-orphan', 'missing-conversation', 0, 'USER', 'Orphan',
                    'COMPLETED', '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
                )
                """.trimIndent(),
                0,
            )
        }

        driver.execute(
            null,
            """
            INSERT INTO transaction_import_session(
                id, idempotency_key, status, locale_tag, default_currency, created_at, updated_at
            ) VALUES (
                'import-cascade', 'import-idempotency', 'READY', 'pt-BR', 'BRL',
                '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO transaction_import_source(
                id, import_session_id, source_hash, source_order, media_type,
                byte_size, temporary_reference, created_at
            ) VALUES (
                'source-cascade', 'import-cascade', 'sha256:test', 0, 'image/png',
                128, 'owned-image.png', '2026-07-14T10:00:00Z'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO transaction_candidate(
                id, import_session_id, source_type, idempotency_key, transaction_type,
                status, created_at, updated_at
            ) VALUES (
                'candidate-cascade', 'import-cascade', 'IMAGE', 'candidate-idempotency',
                'EXPENSE', 'NEEDS_REVIEW', '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
            )
            """.trimIndent(),
            0,
        )
        assertFailsWith<Exception> {
            driver.execute(
                null,
                """
                INSERT INTO transaction_candidate(
                    id, import_session_id, source_type, idempotency_key, transaction_type,
                    status, created_at, updated_at
                ) VALUES (
                    'candidate-duplicate', 'import-cascade', 'IMAGE', 'candidate-idempotency',
                    'EXPENSE', 'NEEDS_REVIEW', '2026-07-14T10:00:00Z', '2026-07-14T10:00:00Z'
                )
                """.trimIndent(),
                0,
            )
        }

        driver.execute(null, "DELETE FROM transaction_import_session WHERE id='import-cascade'", 0)
        assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM transaction_import_source WHERE id='source-cascade'"))
        assertEquals(0L, scalar(driver, "SELECT COUNT(*) FROM transaction_candidate WHERE id='candidate-cascade'"))
    }

    private fun scalar(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value

    private fun openDriver(databaseUrl: String): JdbcSqliteDriver = JdbcSqliteDriver("$databaseUrl?foreign_keys=on")

    private fun installedObjects(
        driver: JdbcSqliteDriver,
        type: String,
    ): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = '$type'",
            mapper = { cursor ->
                QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(cursor.getString(0).orEmpty())
                    },
                )
            },
            parameters = 0,
        ).value

    private fun deleteDatabaseFiles(databasePath: Path) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
        }
    }

    private companion object {
        const val V1_4_4_SCHEMA_RESOURCE = "/database/v1_4_4_schema.sql"

        val V8_TABLES =
            setOf(
                "advisor_provider_profile",
                "advisor_conversation",
                "advisor_message",
                "transaction_import_session",
                "transaction_import_source",
                "transaction_candidate",
                "notification_ingestion",
                "monitored_application",
                "expense_origin",
            )

        val V8_INDEXES =
            setOf(
                "idx_advisor_provider_active",
                "idx_advisor_conversation_activity",
                "idx_advisor_message_order",
                "idx_advisor_message_exchange_role",
                "idx_advisor_message_incomplete",
                "idx_import_session_activity",
                "idx_import_source_hash",
                "idx_candidate_import_order",
                "idx_candidate_review_queue",
                "idx_candidate_fingerprint",
                "idx_notification_source_key",
                "idx_notification_processing",
                "idx_notification_candidate",
                "idx_expense_origin_fingerprint",
            )
    }
}
