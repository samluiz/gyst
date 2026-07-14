package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.TransactionImportSession
import com.samluiz.gyst.domain.model.TransactionImportSource
import kotlin.time.Instant

interface TransactionImportRepository {
    suspend fun create(session: TransactionImportSession): TransactionImportSession

    suspend fun replaceSourceDraft(
        previousSessionId: String?,
        session: TransactionImportSession,
        sources: List<TransactionImportSource>,
    ): TransactionImportSession

    suspend fun get(id: String): TransactionImportSession?

    suspend fun getByIdempotencyKey(idempotencyKey: String): TransactionImportSession?

    suspend fun list(): List<TransactionImportSession>

    suspend fun sources(sessionId: String): List<TransactionImportSource>

    suspend fun priorSources(sourceHash: String): List<TransactionImportSource>

    suspend fun delete(id: String)

    suspend fun configureAnalysis(
        id: String,
        providerProfileId: String,
        providerId: String,
        modelId: String,
        localeTag: String,
        defaultCurrency: String,
        updatedAt: Instant,
    ): TransactionImportSession

    /** Atomically clears any previous unapproved extraction and starts a retry-safe analysis. */
    suspend fun beginAnalysis(
        id: String,
        updatedAt: Instant,
    ): TransactionImportSession

    suspend fun updateStatus(
        id: String,
        status: ImportSessionStatus,
        selectedCount: Long,
        importedCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
    )
}
