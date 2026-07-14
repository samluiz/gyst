package com.samluiz.gyst.domain.model

import kotlin.time.Instant

enum class NotificationProcessingStatus {
    RECEIVED,
    FILTERED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class NotificationIngestion(
    val id: String,
    val sourcePackage: String,
    val notificationId: Long?,
    val notificationKey: String,
    val notificationFingerprint: String,
    val postedAt: Instant,
    val title: String?,
    val mainText: String?,
    val expandedText: String?,
    val channelId: String?,
    val category: String?,
    val normalizedText: String?,
    val processingStatus: NotificationProcessingStatus,
    val candidateId: String?,
    val retryCount: Long,
    val errorType: String?,
    val errorMessage: String?,
    val contentRedactedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class MonitoredApplicationPolicy {
    ALLOW,
    BLOCK,
}

data class MonitoredApplication(
    val packageName: String,
    val displayName: String,
    val policy: MonitoredApplicationPolicy,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ExpenseOrigin(
    val id: String,
    val expenseId: String,
    val candidateId: String?,
    val importSessionId: String?,
    val source: CandidateSource,
    val sourceImageHash: String?,
    val notificationFingerprint: String?,
    val transactionFingerprint: String,
    val idempotencyKey: String,
    val confidence: Double?,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Instant,
)
