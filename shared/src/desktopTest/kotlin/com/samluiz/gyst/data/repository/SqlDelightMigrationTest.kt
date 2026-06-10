package com.samluiz.gyst.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import com.samluiz.gyst.db.GystDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightMigrationTest {
    @Test
    fun migrateFromV1ToLatestAppliesHardeningAndDedup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createLegacyV1Schema(driver)

        GystDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = GystDatabase.Schema.version)

        val categoryColumns = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(expense)",
            mapper = { cursor ->
                QueryResult.Value(buildList {
                    while (cursor.next().value) {
                        add(cursor.getString(1) ?: "")
                    }
                })
            },
            parameters = 0,
        ).value
        assertTrue(categoryColumns.contains("recurrence_type"))

        val budgetMonthRows = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM budget_month WHERE year_month = '2026-01'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
        assertEquals(1L, budgetMonthRows)

        val scheduleRows = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM payment_schedule_item WHERE ref_id='sub-1' AND kind='SUBSCRIPTION' AND due_date='2026-01-10'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
        assertEquals(1L, scheduleRows)
    }

    private fun createLegacyV1Schema(driver: JdbcSqliteDriver) {
        val statements = listOf(
            """
            CREATE TABLE category (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                color TEXT,
                icon TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE budget_month (
                id TEXT NOT NULL PRIMARY KEY,
                year_month TEXT NOT NULL,
                total_income_cents INTEGER NOT NULL CHECK(total_income_cents >= 0),
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE budget_allocation (
                id TEXT NOT NULL PRIMARY KEY,
                budget_month_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                planned_cents INTEGER NOT NULL CHECK(planned_cents >= 0),
                UNIQUE(budget_month_id, category_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE expense (
                id TEXT NOT NULL PRIMARY KEY,
                occurred_at TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
                category_id TEXT NOT NULL,
                note TEXT,
                merchant TEXT,
                payment_method TEXT NOT NULL,
                created_at TEXT NOT NULL,
                schedule_item_id TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE subscription (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
                billing_day INTEGER NOT NULL CHECK(billing_day >= 1 AND billing_day <= 31),
                category_id TEXT NOT NULL,
                active INTEGER NOT NULL,
                renewal_policy TEXT NOT NULL,
                next_due_date TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE installment_plan (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                total_installments INTEGER NOT NULL CHECK(total_installments >= 1),
                monthly_amount_cents INTEGER NOT NULL CHECK(monthly_amount_cents >= 0),
                start_year_month TEXT NOT NULL,
                end_year_month TEXT NOT NULL,
                category_id TEXT NOT NULL,
                active INTEGER NOT NULL,
                CHECK(end_year_month >= start_year_month)
            )
            """.trimIndent(),
            """
            CREATE TABLE payment_schedule_item (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                ref_id TEXT NOT NULL,
                due_date TEXT NOT NULL,
                amount_cents INTEGER NOT NULL,
                status TEXT NOT NULL,
                paid_at TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE safety_guard (
                id TEXT NOT NULL PRIMARY KEY,
                no_new_installments INTEGER NOT NULL,
                discretionary_cap_cents INTEGER,
                alert70_enabled INTEGER NOT NULL,
                alert90_enabled INTEGER NOT NULL,
                alert100_enabled INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE app_setting (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            "INSERT INTO category(id, name, type, color, icon) VALUES ('cat-1', 'Assinaturas', 'FIXED', NULL, NULL)",
            "INSERT INTO subscription(id, name, amount_cents, billing_day, category_id, active, renewal_policy, next_due_date) VALUES ('sub-1', 'Netflix', 1000, 10, 'cat-1', 1, 'MONTHLY', '2026-01-10')",
            "INSERT INTO budget_month(id, year_month, total_income_cents, created_at) VALUES ('b1', '2026-01', 100000, '2026-01-01T00:00:00Z')",
            "INSERT INTO budget_month(id, year_month, total_income_cents, created_at) VALUES ('b2', '2026-01', 120000, '2026-01-02T00:00:00Z')",
            "INSERT INTO payment_schedule_item(id, kind, ref_id, due_date, amount_cents, status, paid_at) VALUES ('s1', 'SUBSCRIPTION', 'sub-1', '2026-01-10', 1000, 'DUE', NULL)",
            "INSERT INTO payment_schedule_item(id, kind, ref_id, due_date, amount_cents, status, paid_at) VALUES ('s2', 'SUBSCRIPTION', 'sub-1', '2026-01-10', 1000, 'DUE', NULL)",
        )
        statements.forEach { sql ->
            driver.execute(null, sql, 0)
        }
    }
}
