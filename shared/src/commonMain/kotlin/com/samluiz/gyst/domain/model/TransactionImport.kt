package com.samluiz.gyst.domain.model

import kotlin.time.Instant

enum class ImportSessionStatus {
    CREATED,
    ANALYZING,
    READY,
    IMPORTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TransactionImportSession(
    val id: String,
    val idempotencyKey: String,
    val status: ImportSessionStatus,
    val providerProfileId: String?,
    val providerId: String?,
    val modelId: String?,
    val localeTag: String,
    val defaultCurrency: String,
    val allowPartial: Boolean,
    val selectedCount: Long,
    val importedCount: Long,
    val errorType: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class TransactionImportSource(
    val id: String,
    val importSessionId: String,
    val sourceHash: String,
    val sourceOrder: Long,
    val mediaType: String,
    val displayName: String?,
    val byteSize: Long?,
    val temporaryReference: String?,
    val createdAt: Instant,
)
