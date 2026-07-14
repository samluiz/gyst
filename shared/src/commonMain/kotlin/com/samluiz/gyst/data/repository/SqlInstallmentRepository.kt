package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlInstallmentRepository(private val holder: DatabaseHolder) : InstallmentRepository {
    override suspend fun upsert(plan: InstallmentPlan) {
        holder.withDatabase { db ->
            db.financeQueries.upsertInstallmentPlan(
                name = plan.name,
                total_installments = plan.totalInstallments.toLong(),
                monthly_amount_cents = plan.monthlyAmountCents,
                total_amount_cents = plan.totalAmountCents,
                start_year_month = plan.startYearMonth.toString(),
                end_year_month = plan.endYearMonth.toString(),
                category_id = plan.categoryId,
                active = if (plan.active) 1 else 0,
                id = plan.id,
                id_ = plan.id,
                name_ = plan.name,
                total_installments_ = plan.totalInstallments.toLong(),
                monthly_amount_cents_ = plan.monthlyAmountCents,
                total_amount_cents_ = plan.totalAmountCents,
                start_year_month_ = plan.startYearMonth.toString(),
                end_year_month_ = plan.endYearMonth.toString(),
                category_id_ = plan.categoryId,
                active_ = if (plan.active) 1 else 0,
            )
        }
    }

    override suspend fun list(): List<InstallmentPlan> =
        holder.withDatabase { db ->
            db.financeQueries.selectInstallmentPlans().executeAsList().map {
                InstallmentPlan(
                    id = it.id,
                    name = it.name,
                    totalInstallments = it.total_installments.toInt(),
                    monthlyAmountCents = it.monthly_amount_cents,
                    totalAmountCents = it.total_amount_cents,
                    startYearMonth = YearMonth.parse(it.start_year_month),
                    endYearMonth = YearMonth.parse(it.end_year_month),
                    categoryId = it.category_id,
                    active = it.active == 1L,
                )
            }
        }

    override suspend fun listActive(): List<InstallmentPlan> =
        holder.withDatabase { db ->
            db.financeQueries.selectActiveInstallments().executeAsList().map {
                InstallmentPlan(
                    id = it.id,
                    name = it.name,
                    totalInstallments = it.total_installments.toInt(),
                    monthlyAmountCents = it.monthly_amount_cents,
                    totalAmountCents = it.total_amount_cents,
                    startYearMonth = YearMonth.parse(it.start_year_month),
                    endYearMonth = YearMonth.parse(it.end_year_month),
                    categoryId = it.category_id,
                    active = it.active == 1L,
                )
            }
        }

    override suspend fun deactivate(id: String) {
        holder.withDatabase { db ->
            db.financeQueries.deactivateInstallmentPlan(id)
        }
    }
}
