package com.samluiz.gyst.domain.usecase

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class SeedDataInitializer(
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val budgetRepository: BudgetRepository,
) {
    suspend fun ensureSeedData() {
        val seeded = settingsRepository.getString("seeded") == "true"
        if (seeded) return

        val categories = listOf(
            Category(id("cat"), "Moradia", CategoryType.ESSENTIAL),
            Category(id("cat"), "Mercado", CategoryType.ESSENTIAL),
            Category(id("cat"), "Transporte", CategoryType.VARIABLE),
            Category(id("cat"), "Assinaturas", CategoryType.FIXED),
            Category(id("cat"), "Reserva", CategoryType.RESERVE),
            Category(id("cat"), "Meta", CategoryType.GOAL),
        )
        categories.forEach { categoryRepository.upsert(it) }

        settingsRepository.upsertSafetyGuard(
            SafetyGuard(
                id = id("guard"),
                discretionaryCapCents = 100_000,
            )
        )

        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val ym = YearMonth.fromDate(now)
        budgetRepository.createOrUpdateMonth(ym, 0)

        settingsRepository.setString("seeded", "true")
    }
}
