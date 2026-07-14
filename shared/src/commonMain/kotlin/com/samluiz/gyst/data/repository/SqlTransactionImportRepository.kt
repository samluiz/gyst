package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.Transaction_import_session
import com.samluiz.gyst.db.Transaction_import_source
import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.TransactionImportSession
import com.samluiz.gyst.domain.model.TransactionImportSource
import com.samluiz.gyst.domain.repository.TransactionImportRepository
import kotlin.time.Instant

class SqlTransactionImportRepository(
    private val holder: DatabaseHolder,
) : TransactionImportRepository {
    override suspend fun create(session: TransactionImportSession): TransactionImportSession =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                queries.selectTransactionImportSessionByIdempotencyKey(session.idempotencyKey)
                    .executeAsOneOrNull()
                    ?.let { return@transactionWithResult it.toDomain() }
                queries.insert(session)
                checkNotNull(queries.selectTransactionImportSessionById(session.id).executeAsOneOrNull()).toDomain()
            }
        }

    override suspend fun replaceSourceDraft(
        previousSessionId: String?,
        session: TransactionImportSession,
        sources: List<TransactionImportSource>,
    ): TransactionImportSession =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val previous =
                    previousSessionId?.let(queries::selectTransactionImportSessionById)
                        ?.executeAsOneOrNull()
                if (previous != null) {
                    val previousStatus = ImportSessionStatus.valueOf(previous.status)
                    require(previousStatus in replaceableDraftStatuses) {
                        "Cannot replace import in status $previousStatus"
                    }
                }
                val alreadyPersisted =
                    queries.selectTransactionImportSessionByIdempotencyKey(session.idempotencyKey)
                        .executeAsOneOrNull()
                if (alreadyPersisted != null) {
                    if (previous != null && previous.id != alreadyPersisted.id) {
                        queries.deleteTransactionImportSession(previous.id)
                    }
                    return@transactionWithResult alreadyPersisted.toDomain()
                }
                require(sources.all { it.importSessionId == session.id }) {
                    "Import sources must belong to their session"
                }
                queries.insert(session)
                sources.forEach(queries::insert)
                if (previous != null && previous.id != session.id) {
                    queries.deleteTransactionImportSession(previous.id)
                }
                checkNotNull(queries.selectTransactionImportSessionById(session.id).executeAsOneOrNull()).toDomain()
            }
        }

    override suspend fun get(id: String): TransactionImportSession? =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionImportSessionById(id).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): TransactionImportSession? =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionImportSessionByIdempotencyKey(idempotencyKey)
                .executeAsOneOrNull()
                ?.toDomain()
        }

    override suspend fun list(): List<TransactionImportSession> =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionImportSessions().executeAsList().map { it.toDomain() }
        }

    override suspend fun sources(sessionId: String): List<TransactionImportSource> =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionImportSources(sessionId).executeAsList().map { it.toDomain() }
        }

    override suspend fun priorSources(sourceHash: String): List<TransactionImportSource> =
        holder.withDatabase { database ->
            database.financeQueries.selectPreviousTransactionImportSourcesByHash(sourceHash).executeAsList().map {
                it.toDomain()
            }
        }

    override suspend fun delete(id: String) {
        holder.withDatabase { database ->
            database.financeQueries.deleteTransactionImportSession(id)
        }
    }

    override suspend fun configureAnalysis(
        id: String,
        providerProfileId: String,
        providerId: String,
        modelId: String,
        localeTag: String,
        defaultCurrency: String,
        updatedAt: Instant,
    ): TransactionImportSession =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val current =
                    requireNotNull(queries.selectTransactionImportSessionById(id).executeAsOneOrNull()) {
                        "Import session does not exist"
                    }
                val currentStatus = ImportSessionStatus.valueOf(current.status)
                require(currentStatus in configurableAnalysisStatuses) {
                    "Cannot configure analysis for import in status $currentStatus"
                }
                queries.configureTransactionImportSessionAnalysis(
                    providerProfileId = providerProfileId,
                    providerId = providerId,
                    modelId = modelId,
                    localeTag = localeTag,
                    defaultCurrency = defaultCurrency,
                    updatedAt = updatedAt.toString(),
                    id = id,
                )
                checkNotNull(queries.selectTransactionImportSessionById(id).executeAsOneOrNull()).toDomain()
            }
        }

    override suspend fun beginAnalysis(
        id: String,
        updatedAt: Instant,
    ): TransactionImportSession =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val current =
                    requireNotNull(queries.selectTransactionImportSessionById(id).executeAsOneOrNull()) {
                        "Import session does not exist"
                    }
                val currentStatus = ImportSessionStatus.valueOf(current.status)
                require(currentStatus in configurableAnalysisStatuses) {
                    "Cannot start analysis for import in status $currentStatus"
                }
                queries.deleteUnapprovedTransactionCandidatesByImportSession(id)
                queries.updateTransactionImportSessionStatus(
                    status = ImportSessionStatus.ANALYZING.name,
                    selectedCount = 0,
                    importedCount = current.imported_count,
                    errorType = null,
                    errorMessage = null,
                    updatedAt = updatedAt.toString(),
                    completedAt = null,
                    id = id,
                )
                checkNotNull(queries.selectTransactionImportSessionById(id).executeAsOneOrNull()).toDomain()
            }
        }

    override suspend fun updateStatus(
        id: String,
        status: ImportSessionStatus,
        selectedCount: Long,
        importedCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
    ) {
        holder.withDatabase { database ->
            val queries = database.financeQueries
            val current =
                requireNotNull(queries.selectTransactionImportSessionById(id).executeAsOneOrNull()) {
                    "Import session does not exist"
                }
            val currentStatus = ImportSessionStatus.valueOf(current.status)
            require(currentStatus == status || status in validTransitions.getValue(currentStatus)) {
                "Invalid import status transition: $currentStatus -> $status"
            }
            queries.updateTransactionImportSessionStatus(
                status = status.name,
                selectedCount = selectedCount,
                importedCount = importedCount,
                errorType = errorType,
                errorMessage = safeErrorMessage,
                updatedAt = updatedAt.toString(),
                completedAt = completedAt?.toString(),
                id = id,
            )
        }
    }
}

private fun com.samluiz.gyst.db.FinanceQueries.insert(session: TransactionImportSession) {
    insertTransactionImportSession(
        id = session.id,
        idempotency_key = session.idempotencyKey,
        status = session.status.name,
        provider_profile_id = session.providerProfileId,
        provider_id = session.providerId,
        model_id = session.modelId,
        locale_tag = session.localeTag,
        default_currency = session.defaultCurrency,
        allow_partial = session.allowPartial.asLong(),
        selected_count = session.selectedCount,
        imported_count = session.importedCount,
        error_type = session.errorType,
        error_message = session.errorMessage,
        created_at = session.createdAt.toString(),
        updated_at = session.updatedAt.toString(),
        completed_at = session.completedAt?.toString(),
    )
}

private fun com.samluiz.gyst.db.FinanceQueries.insert(source: TransactionImportSource) {
    insertTransactionImportSource(
        id = source.id,
        import_session_id = source.importSessionId,
        source_hash = source.sourceHash,
        source_order = source.sourceOrder,
        media_type = source.mediaType,
        display_name = source.displayName,
        byte_size = source.byteSize,
        temporary_reference = source.temporaryReference,
        created_at = source.createdAt.toString(),
    )
}

private val configurableAnalysisStatuses =
    setOf(
        ImportSessionStatus.CREATED,
        ImportSessionStatus.READY,
        ImportSessionStatus.FAILED,
        ImportSessionStatus.CANCELLED,
    )

private val replaceableDraftStatuses = configurableAnalysisStatuses

private val validTransitions =
    mapOf(
        ImportSessionStatus.CREATED to setOf(ImportSessionStatus.ANALYZING, ImportSessionStatus.CANCELLED),
        ImportSessionStatus.ANALYZING to
            setOf(ImportSessionStatus.READY, ImportSessionStatus.FAILED, ImportSessionStatus.CANCELLED),
        ImportSessionStatus.READY to
            setOf(ImportSessionStatus.IMPORTING, ImportSessionStatus.ANALYZING, ImportSessionStatus.CANCELLED),
        ImportSessionStatus.IMPORTING to
            setOf(ImportSessionStatus.COMPLETED, ImportSessionStatus.FAILED, ImportSessionStatus.CANCELLED),
        ImportSessionStatus.FAILED to setOf(ImportSessionStatus.ANALYZING, ImportSessionStatus.CANCELLED),
        ImportSessionStatus.COMPLETED to emptySet(),
        // Cancellation stops the current provider call without abandoning the durable import.
        // The same idempotent session may be analyzed again while its source images still exist.
        ImportSessionStatus.CANCELLED to setOf(ImportSessionStatus.ANALYZING),
    )

private fun Transaction_import_session.toDomain(): TransactionImportSession =
    TransactionImportSession(
        id = id,
        idempotencyKey = idempotency_key,
        status = ImportSessionStatus.valueOf(status),
        providerProfileId = provider_profile_id,
        providerId = provider_id,
        modelId = model_id,
        localeTag = locale_tag,
        defaultCurrency = default_currency,
        allowPartial = allow_partial == 1L,
        selectedCount = selected_count,
        importedCount = imported_count,
        errorType = error_type,
        errorMessage = error_message,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
        completedAt = completed_at?.let(Instant::parse),
    )

private fun Transaction_import_source.toDomain(): TransactionImportSource =
    TransactionImportSource(
        id = id,
        importSessionId = import_session_id,
        sourceHash = source_hash,
        sourceOrder = source_order,
        mediaType = media_type,
        displayName = display_name,
        byteSize = byte_size,
        temporaryReference = temporary_reference,
        createdAt = Instant.parse(created_at),
    )
