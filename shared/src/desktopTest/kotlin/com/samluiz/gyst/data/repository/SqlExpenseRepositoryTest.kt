package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.Expense
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.RecurrenceType
import com.samluiz.gyst.domain.model.RecurringExpenseSeries
import com.samluiz.gyst.domain.model.YearMonth
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SqlExpenseRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var holder: DatabaseHolder
    private lateinit var expenses: SqlExpenseRepository
    private lateinit var recurring: SqlRecurringExpenseRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        holder =
            DatabaseHolder(
                initialDriver = driver,
                driverFactory =
                    object : SqlDriverFactory {
                        override fun createDriver(): SqlDriver = error("Test does not reload the database")
                    },
            )
        expenses = SqlExpenseRepository(holder)
        recurring = SqlRecurringExpenseRepository(holder)
        runTest {
            SqlCategoryRepository(holder).upsert(Category("home", "Moradia", CategoryType.ESSENTIAL))
        }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun ordinaryExpenseSupportsCreateReadUpdateAndDelete() =
        runTest {
            val original = expense("expense-1", LocalDate(2026, 7, 5), 100_00)
            expenses.upsert(original)
            assertEquals(original, expenses.getById(original.id))

            val updated = original.copy(amountCents = 125_50, note = "Mercado")
            expenses.upsert(updated)
            assertEquals(updated, expenses.getById(original.id))

            expenses.delete(original.id)
            assertNull(expenses.getById(original.id))
        }

    @Test
    fun deletingFirstRecurringOccurrenceRemovesSeriesWithoutInvalidEndMonth() =
        runTest {
            recurring.upsert(
                RecurringExpenseSeries(
                    id = "series-1",
                    startYearMonth = YearMonth(2026, 7),
                    endYearMonth = null,
                    dayOfMonth = 5,
                    amountCents = 100_00,
                    categoryId = "home",
                    note = "Internet",
                    merchant = null,
                    paymentMethod = PaymentMethod.DEBIT,
                    active = true,
                ),
            )
            expenses.upsert(expense("july", LocalDate(2026, 7, 5), 100_00, "series-1"))
            expenses.upsert(expense("august", LocalDate(2026, 8, 5), 100_00, "series-1"))

            expenses.deleteRecurringFromOccurrence(
                expenseId = "july",
                occurrenceDate = LocalDate(2026, 7, 5),
                seriesId = "series-1",
                lastActiveMonth = YearMonth(2026, 6),
            )

            assertNull(expenses.getById("july"))
            assertNull(expenses.getById("august"))
            assertNull(recurring.findById("series-1"))
        }

    private fun expense(
        id: String,
        occurredAt: LocalDate,
        amountCents: Long,
        seriesId: String? = null,
    ) = Expense(
        id = id,
        occurredAt = occurredAt,
        amountCents = amountCents,
        categoryId = "home",
        note = "Expense",
        paymentMethod = PaymentMethod.DEBIT,
        recurrenceType = if (seriesId == null) RecurrenceType.ONE_TIME else RecurrenceType.MONTHLY,
        createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        recurrenceSeriesId = seriesId,
    )
}
