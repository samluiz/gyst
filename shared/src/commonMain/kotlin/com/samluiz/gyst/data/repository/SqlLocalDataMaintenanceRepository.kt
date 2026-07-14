package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlLocalDataMaintenanceRepository(private val holder: DatabaseHolder) : LocalDataMaintenanceRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun resetLocalData() {
        holder.db.transaction {
            q.deleteAllScheduleItems()
            q.deleteAllExpenses()
            q.deleteAllSubscriptions()
            q.deleteAllInstallments()
            q.deleteAllBudgetAllocations()
            q.deleteAllBudgetMonths()
            q.deleteAllCategories()
            q.deleteAllSettings()
        }
    }
}
