package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.Monitored_application
import com.samluiz.gyst.db.Notification_ingestion
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.MonitoredApplication
import com.samluiz.gyst.domain.model.MonitoredApplicationPolicy
import com.samluiz.gyst.domain.model.NotificationIngestion
import com.samluiz.gyst.domain.model.NotificationProcessingStatus
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.repository.MonitoredApplicationRepository
import com.samluiz.gyst.domain.repository.NotificationIngestionRepository
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class SqlNotificationIngestionRepository(
    private val holder: DatabaseHolder,
    private val json: Json = Json,
) : NotificationIngestionRepository {
    override suspend fun insertIdempotently(ingestion: NotificationIngestion): NotificationIngestion =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                queries.selectNotificationIngestionByFingerprint(ingestion.notificationFingerprint)
                    .executeAsOneOrNull()
                    ?.let { return@transactionWithResult it.toDomain() }
                queries.insertNotificationIngestion(
                    id = ingestion.id,
                    source_package = ingestion.sourcePackage,
                    notification_id = ingestion.notificationId,
                    notification_key = ingestion.notificationKey,
                    notification_fingerprint = ingestion.notificationFingerprint,
                    posted_at = ingestion.postedAt.toString(),
                    title = ingestion.title,
                    main_text = ingestion.mainText,
                    expanded_text = ingestion.expandedText,
                    channel_id = ingestion.channelId,
                    category = ingestion.category,
                    normalized_text = ingestion.normalizedText,
                    processing_status = ingestion.processingStatus.name,
                    candidate_id = ingestion.candidateId,
                    retry_count = ingestion.retryCount,
                    error_type = ingestion.errorType,
                    error_message = ingestion.errorMessage,
                    content_redacted_at = ingestion.contentRedactedAt?.toString(),
                    created_at = ingestion.createdAt.toString(),
                    updated_at = ingestion.updatedAt.toString(),
                )
                checkNotNull(
                    queries.selectNotificationIngestionByFingerprint(ingestion.notificationFingerprint)
                        .executeAsOneOrNull(),
                ).toDomain()
            }
        }

    override suspend fun get(id: String): NotificationIngestion? =
        holder.withDatabase { database ->
            database.financeQueries.selectNotificationIngestionById(id).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getByFingerprint(fingerprint: String): NotificationIngestion? =
        holder.withDatabase { database ->
            database.financeQueries.selectNotificationIngestionByFingerprint(fingerprint).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun queuedForProcessing(): List<NotificationIngestion> =
        holder.withDatabase { database ->
            database.financeQueries.selectNotificationIngestionsForProcessing().executeAsList().map { it.toDomain() }
        }

    override suspend fun recoverInterruptedProcessing(updatedAt: Instant) {
        holder.withDatabase { database ->
            database.financeQueries.recoverInterruptedNotificationProcessing(updatedAt.toString())
        }
    }

    override suspend fun storeSuggestionAndRedact(
        ingestionId: String,
        candidate: TransactionCandidate,
        updatedAt: Instant,
    ): TransactionCandidate {
        require(candidate.source == CandidateSource.ANDROID_NOTIFICATION)
        return holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val ingestion =
                    requireNotNull(queries.selectNotificationIngestionById(ingestionId).executeAsOneOrNull()) {
                        "Notification ingestion does not exist"
                    }
                ingestion.candidate_id?.let { existingId ->
                    queries.selectTransactionCandidateById(existingId).executeAsOneOrNull()?.let {
                        return@transactionWithResult it.toDomain(json)
                    }
                }
                val stored =
                    queries.selectTransactionCandidateByIdempotencyKey(candidate.idempotencyKey)
                        .executeAsOneOrNull()
                        ?.toDomain(json)
                        ?: run {
                            queries.insertTransactionCandidateRecord(candidate, json)
                            checkNotNull(queries.selectTransactionCandidateById(candidate.id).executeAsOneOrNull())
                                .toDomain(json)
                        }
                queries.updateNotificationIngestionStatus(
                    status = NotificationProcessingStatus.COMPLETED.name,
                    candidateId = stored.id,
                    retryCount = ingestion.retry_count,
                    errorType = null,
                    errorMessage = null,
                    updatedAt = updatedAt.toString(),
                    id = ingestionId,
                )
                queries.redactNotificationIngestionContent(updatedAt.toString(), ingestionId)
                stored
            }
        }
    }

    override suspend fun updateStatus(
        id: String,
        status: NotificationProcessingStatus,
        candidateId: String?,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.updateNotificationIngestionStatus(
                status.name,
                candidateId,
                retryCount,
                errorType,
                safeErrorMessage,
                updatedAt.toString(),
                id,
            )
        }
    }

    override suspend fun redactContent(
        id: String,
        redactedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.redactNotificationIngestionContent(redactedAt.toString(), id)
        }
    }

    override suspend fun deleteAll() {
        holder.withDatabase { database -> database.financeQueries.deleteNotificationDerivedData() }
    }
}

class SqlMonitoredApplicationRepository(
    private val holder: DatabaseHolder,
) : MonitoredApplicationRepository {
    override suspend fun upsert(application: MonitoredApplication) {
        holder.withDatabase { database ->
            database.financeQueries.upsertMonitoredApplication(
                display_name = application.displayName,
                policy = application.policy.name,
                enabled = application.enabled.asLong(),
                updated_at = application.updatedAt.toString(),
                package_name = application.packageName,
                package_name_ = application.packageName,
                display_name_ = application.displayName,
                policy_ = application.policy.name,
                enabled_ = application.enabled.asLong(),
                created_at = application.createdAt.toString(),
                updated_at_ = application.updatedAt.toString(),
            )
        }
    }

    override suspend fun get(packageName: String): MonitoredApplication? =
        holder.withDatabase { database ->
            database.financeQueries.selectMonitoredApplicationByPackage(packageName).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun list(): List<MonitoredApplication> =
        holder.withDatabase { database ->
            database.financeQueries.selectMonitoredApplications().executeAsList().map { it.toDomain() }
        }

    override suspend fun delete(packageName: String) {
        holder.withDatabase { database -> database.financeQueries.deleteMonitoredApplication(packageName) }
    }
}

private fun Notification_ingestion.toDomain(): NotificationIngestion =
    NotificationIngestion(
        id = id,
        sourcePackage = source_package,
        notificationId = notification_id,
        notificationKey = notification_key,
        notificationFingerprint = notification_fingerprint,
        postedAt = Instant.parse(posted_at),
        title = title,
        mainText = main_text,
        expandedText = expanded_text,
        channelId = channel_id,
        category = category,
        normalizedText = normalized_text,
        processingStatus = NotificationProcessingStatus.valueOf(processing_status),
        candidateId = candidate_id,
        retryCount = retry_count,
        errorType = error_type,
        errorMessage = error_message,
        contentRedactedAt = content_redacted_at?.let(Instant::parse),
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

private fun Monitored_application.toDomain(): MonitoredApplication =
    MonitoredApplication(
        packageName = package_name,
        displayName = display_name,
        policy = MonitoredApplicationPolicy.valueOf(policy),
        enabled = enabled == 1L,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )
