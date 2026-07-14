package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.MonitoredApplication
import com.samluiz.gyst.domain.model.NotificationIngestion
import com.samluiz.gyst.domain.model.NotificationProcessingStatus
import com.samluiz.gyst.domain.model.TransactionCandidate
import kotlin.time.Instant

interface NotificationIngestionRepository {
    /** Returns the existing row when a callback fingerprint has already been ingested. */
    suspend fun insertIdempotently(ingestion: NotificationIngestion): NotificationIngestion

    suspend fun get(id: String): NotificationIngestion?

    suspend fun getByFingerprint(fingerprint: String): NotificationIngestion?

    suspend fun queuedForProcessing(): List<NotificationIngestion>

    /**
     * Returns work left in the transient PROCESSING state to the durable queue after process death.
     * The protected normalized text remains available for either local rule replay or BYOK analysis.
     */
    suspend fun recoverInterruptedProcessing(updatedAt: Instant)

    /** Persists the reviewable suggestion, links it to the event, and erases raw content atomically. */
    suspend fun storeSuggestionAndRedact(
        ingestionId: String,
        candidate: TransactionCandidate,
        updatedAt: Instant,
    ): TransactionCandidate

    suspend fun updateStatus(
        id: String,
        status: NotificationProcessingStatus,
        candidateId: String?,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    )

    suspend fun redactContent(
        id: String,
        redactedAt: Instant,
    )

    /** Atomically deletes every ingestion and all notification-derived candidates/delivery metadata. */
    suspend fun deleteAll()
}

interface MonitoredApplicationRepository {
    suspend fun upsert(application: MonitoredApplication)

    suspend fun get(packageName: String): MonitoredApplication?

    suspend fun list(): List<MonitoredApplication>

    suspend fun delete(packageName: String)
}
