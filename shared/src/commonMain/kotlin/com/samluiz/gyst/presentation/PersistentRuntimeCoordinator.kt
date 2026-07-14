package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.repository.LocalDataMaintenanceRepository
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.DEFAULT_ADVISOR_PROFILE_ID
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.domain.usecase.SeedDataInitializer

/**
 * Coordinates durable services around database replacement and reset operations. No service may
 * retain an in-flight database write or provider callback while the backing file is being swapped.
 */
class PersistentRuntimeCoordinator(
    private val advisorService: AdvisorService,
    private val imageImportService: ImageImportService,
    private val automaticDetectionService: AutomaticTransactionDetectionService,
    private val providerProfileRepository: ProviderProfileRepository,
    private val advisorSecretStore: AdvisorSecretStore,
    private val localDataMaintenanceRepository: LocalDataMaintenanceRepository,
    private val seedDataInitializer: SeedDataInitializer,
) {
    suspend fun initializeAll() {
        runAll(
            advisorService::initialize,
            imageImportService::initialize,
            automaticDetectionService::initialize,
        )
    }

    suspend fun <T> whileDatabaseIsQuiesced(operation: suspend () -> T): T {
        var operationFailure: Throwable? = null
        try {
            quiesceAll()
            return operation()
        } catch (error: Throwable) {
            operationFailure = error
            throw error
        } finally {
            try {
                initializeAll()
            } catch (resumeFailure: Throwable) {
                val originalFailure = operationFailure
                if (originalFailure == null) {
                    throw resumeFailure
                }
                originalFailure.addSuppressed(resumeFailure)
            }
        }
    }

    suspend fun resetLocalData() {
        val credentialSlots =
            (providerProfileRepository.list().map { it.id } + DEFAULT_ADVISOR_PROFILE_ID).distinct()
        whileDatabaseIsQuiesced {
            automaticDetectionService.deleteNotificationDerivedData()
            credentialSlots.forEach { advisorSecretStore.clearApiKey(it) }
            advisorSecretStore.clearAllApiKeys()
            localDataMaintenanceRepository.resetLocalData()
            seedDataInitializer.ensureSeedData()
        }
    }

    private suspend fun quiesceAll() {
        runAll(
            advisorService::suspendForDatabaseReplacement,
            imageImportService::suspendForDatabaseReplacement,
            automaticDetectionService::suspendProcessingForDatabaseReplacement,
        )
    }

    private suspend fun runAll(vararg operations: suspend () -> Unit) {
        var firstFailure: Throwable? = null
        operations.forEach { operation ->
            try {
                operation()
            } catch (error: Throwable) {
                val previous = firstFailure
                if (previous == null) {
                    firstFailure = error
                } else {
                    previous.addSuppressed(error)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}
