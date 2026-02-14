package com.samluiz.gyst.di

import app.cash.sqldelight.db.SqlDriver
import com.samluiz.gyst.data.repository.*
import com.samluiz.gyst.domain.repository.*
import com.samluiz.gyst.domain.usecase.*
import com.samluiz.gyst.presentation.MainStore
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

private val sharedModule = module {
    single { DatabaseHolder(get<SqlDriver>(), get()) }
    single { DatabaseRuntimeController(get()) }

    single<CategoryRepository> { SqlCategoryRepository(get()) }
    single<BudgetRepository> { SqlBudgetRepository(get()) }
    single<ExpenseRepository> { SqlExpenseRepository(get()) }
    single<SubscriptionRepository> { SqlSubscriptionRepository(get()) }
    single<InstallmentRepository> { SqlInstallmentRepository(get()) }
    single<ScheduleRepository> { SqlScheduleRepository(get()) }
    single<SettingsRepository> { SqlSettingsRepository(get()) }
    single<CommitmentPaymentRepository> { SqlCommitmentPaymentRepository(get()) }
    single<LocalDataMaintenanceRepository> { SqlLocalDataMaintenanceRepository(get()) }

    single { CreateBudgetMonthUseCase(get()) }
    single { SetBudgetAllocationsUseCase(get()) }
    single { AddOrUpdateExpenseUseCase(get()) }
    single { DeleteExpenseUseCase(get()) }
    single { ComputeMonthlySummaryUseCase(get(), get(), get(), get(), get()) }
    single { ComputeCashFlowForecastUseCase(get(), get(), get()) }
    single { HandleMonthRolloverUseCase(get(), get(), get()) }
    single { UpsertSubscriptionUseCase(get(), get()) }
    single { CreateInstallmentPlanUseCase(get(), get()) }
    single { MarkSchedulePaidUseCase(get()) }

    single { SeedDataInitializer(get(), get(), get()) }
    single { MainStore(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

private var koinStarted = false

fun initKoin(platformModule: Module) {
    if (koinStarted) return
    startKoin {
        modules(sharedModule, platformModule)
    }
    koinStarted = true
}
