package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlLocalDataMaintenanceRepository(private val holder: DatabaseHolder) : LocalDataMaintenanceRepository {
    override suspend fun resetLocalData() {
        holder.withDatabase { db ->
            val q = db.financeQueries
            db.transaction {
                q.deleteAllNotificationIngestions()
                q.deleteAllExpenseOrigins()
                q.deleteAllTransactionCandidates()
                q.deleteAllTransactionImportSessions()
                q.deleteAllAdvisorConversations()
                q.deleteAllAdvisorProviderProfiles()
                q.deleteAllMonitoredApplications()
                q.deleteAllScheduleItems()
                q.deleteAllExpenses()
                q.deleteAllRecurringExpenseSeries()
                q.deleteAllSubscriptions()
                q.deleteAllInstallments()
                q.deleteAllBudgetAllocations()
                q.deleteAllBudgetMonths()
                q.deleteAllCategories()
                q.deleteAllSettings()
            }
        }
    }
}
