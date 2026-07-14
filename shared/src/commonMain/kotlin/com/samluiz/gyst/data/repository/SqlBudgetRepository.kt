package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.id
import com.samluiz.gyst.domain.usecase.nowInstantUtc
import kotlin.time.Instant

class SqlBudgetRepository(private val holder: DatabaseHolder) : BudgetRepository {
    override suspend fun createOrUpdateMonth(
        yearMonth: YearMonth,
        incomeCents: Long,
    ): BudgetMonth =
        holder.withDatabase { db ->
            val q = db.financeQueries
            val existing =
                q.findBudgetMonthByYearMonth(yearMonth.toString()).executeAsOneOrNull()?.let {
                    BudgetMonth(
                        id = it.id,
                        yearMonth = YearMonth.parse(it.year_month),
                        totalIncomeCents = it.total_income_cents,
                        createdAt = Instant.parse(it.created_at),
                    )
                }
            val budget =
                existing?.copy(totalIncomeCents = incomeCents)
                    ?: BudgetMonth(
                        id = id("bud"),
                        yearMonth = yearMonth,
                        totalIncomeCents = incomeCents,
                        createdAt = nowInstantUtc(),
                    )
            q.upsertBudgetMonth(
                year_month = budget.yearMonth.toString(),
                total_income_cents = budget.totalIncomeCents,
                created_at = budget.createdAt.toString(),
                id = budget.id,
                id_ = budget.id,
                year_month_ = budget.yearMonth.toString(),
                total_income_cents_ = budget.totalIncomeCents,
                created_at_ = budget.createdAt.toString(),
            )
            budget
        }

    override suspend fun findByYearMonth(yearMonth: YearMonth): BudgetMonth? =
        holder.withDatabase { db ->
            db.financeQueries.findBudgetMonthByYearMonth(yearMonth.toString()).executeAsOneOrNull()?.let {
                BudgetMonth(
                    id = it.id,
                    yearMonth = YearMonth.parse(it.year_month),
                    totalIncomeCents = it.total_income_cents,
                    createdAt = Instant.parse(it.created_at),
                )
            }
        }

    override suspend fun setAllocations(
        budgetMonthId: String,
        allocations: List<BudgetAllocation>,
    ) {
        holder.withDatabase { db ->
            val q = db.financeQueries
            db.transaction {
                q.deleteBudgetAllocationsByMonth(budgetMonthId)
                allocations.forEach {
                    q.upsertBudgetAllocation(
                        budget_month_id = it.budgetMonthId,
                        category_id = it.categoryId,
                        planned_cents = it.plannedCents,
                        id = it.id,
                        id_ = it.id,
                        budget_month_id_ = it.budgetMonthId,
                        category_id_ = it.categoryId,
                        planned_cents_ = it.plannedCents,
                    )
                }
            }
        }
    }

    override suspend fun allocationsByBudgetMonth(budgetMonthId: String): List<BudgetAllocation> =
        holder.withDatabase { db ->
            db.financeQueries.selectBudgetAllocationsByMonth(budgetMonthId).executeAsList().map {
                BudgetAllocation(
                    id = it.id,
                    budgetMonthId = it.budget_month_id,
                    categoryId = it.category_id,
                    plannedCents = it.planned_cents,
                )
            }
        }

    override suspend fun listMonths(): List<YearMonth> =
        holder.withDatabase { db ->
            db.financeQueries.selectBudgetMonths().executeAsList().map { YearMonth.parse(it) }
        }
}
