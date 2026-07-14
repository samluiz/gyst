package com.samluiz.gyst.di

import app.cash.sqldelight.db.SqlDriver
import com.samluiz.gyst.data.advisor.OpenAiCompatibleAdvisorService
import com.samluiz.gyst.data.advisor.OpenAiCompatibleProviderClient
import com.samluiz.gyst.data.importer.DefaultImageImportService
import com.samluiz.gyst.data.repository.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.domain.usecase.*
import com.samluiz.gyst.presentation.AppNavigator
import com.samluiz.gyst.presentation.MainStore
import com.samluiz.gyst.presentation.PersistentRuntimeCoordinator
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

private val sharedModule =
    module {
        single { DatabaseHolder(get<SqlDriver>(), get()) }
        single { DatabaseRuntimeController(get()) }
        single { AppNavigator() }

        single<CategoryRepository> { SqlCategoryRepository(get()) }
        single<BudgetRepository> { SqlBudgetRepository(get()) }
        single<ExpenseRepository> { SqlExpenseRepository(get()) }
        single<RecurringExpenseRepository> { SqlRecurringExpenseRepository(get()) }
        single<SubscriptionRepository> { SqlSubscriptionRepository(get()) }
        single<InstallmentRepository> { SqlInstallmentRepository(get()) }
        single<ScheduleRepository> { SqlScheduleRepository(get()) }
        single<SettingsRepository> { SqlSettingsRepository(get()) }
        single<CommitmentPaymentRepository> { SqlCommitmentPaymentRepository(get()) }
        single<LocalDataMaintenanceRepository> { SqlLocalDataMaintenanceRepository(get()) }
        single<ProviderProfileRepository> { SqlProviderProfileRepository(get()) }
        single<ConversationRepository> { SqlConversationRepository(get()) }
        single<TransactionImportRepository> { SqlTransactionImportRepository(get()) }
        single<TransactionCandidateRepository> { SqlTransactionCandidateRepository(get()) }
        single<CandidateApprovalRepository> { SqlCandidateApprovalRepository(get()) }
        single<NotificationIngestionRepository> { SqlNotificationIngestionRepository(get()) }
        single<MonitoredApplicationRepository> { SqlMonitoredApplicationRepository(get()) }
        single<AiProviderClient> { OpenAiCompatibleProviderClient() }
        single<ImageImportService> {
            DefaultImageImportService(
                imageSourceService = get(),
                providerProfileRepository = get(),
                secretStore = get(),
                aiProviderClient = get(),
                importRepository = get(),
                candidateRepository = get(),
                approvalRepository = get(),
                categoryRepository = get(),
            )
        }
        single<AdvisorService> {
            OpenAiCompatibleAdvisorService(
                settingsRepository = get(),
                secretStore = get(),
                conversationRepository = get(),
                providerProfileRepository = get(),
                providerClient = get(),
            )
        }
        single {
            PersistentRuntimeCoordinator(
                advisorService = get(),
                imageImportService = get(),
                automaticDetectionService = get(),
                providerProfileRepository = get(),
                advisorSecretStore = get(),
                localDataMaintenanceRepository = get(),
                seedDataInitializer = get(),
            )
        }

        single { CreateBudgetMonthUseCase(get()) }
        single { SetBudgetAllocationsUseCase(get()) }
        single { AddOrUpdateExpenseUseCase(get()) }
        single { DeleteExpenseUseCase(get()) }
        single { ComputeMonthlySummaryUseCase(get(), get(), get()) }
        single { ComputeCashFlowForecastUseCase(get(), get(), get()) }
        single { HandleMonthRolloverUseCase(get(), get(), get()) }
        single { UpsertSubscriptionUseCase(get(), get()) }
        single { CreateInstallmentPlanUseCase(get(), get()) }
        single { MarkSchedulePaidUseCase(get()) }
        single { SeedDataInitializer(get(), get(), get()) }
        single {
            MainStore(
                seedDataInitializer = get(),
                categoryRepository = get(),
                budgetRepository = get(),
                expenseRepository = get(),
                recurringExpenseRepository = get(),
                subscriptionRepository = get(),
                installmentRepository = get(),
                scheduleRepository = get(),
                settingsRepository = get(),
                googleAuthSyncService = get(),
                appUpdateService = get(),
                advisorService = get(),
                databaseRuntimeController = get(),
                persistentRuntimeCoordinator = get(),
                createBudgetMonthUseCase = get(),
                setBudgetAllocationsUseCase = get(),
                addOrUpdateExpenseUseCase = get(),
                deleteExpenseUseCase = get(),
                upsertSubscriptionUseCase = get(),
                createInstallmentPlanUseCase = get(),
                computeMonthlySummaryUseCase = get(),
                computeCashFlowForecastUseCase = get(),
                handleMonthRolloverUseCase = get(),
            )
        }
    }

private var koinStarted = false

fun initKoin(platformModule: Module) {
    if (koinStarted) return
    startKoin {
        modules(sharedModule, platformModule)
    }
    koinStarted = true
}
