package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.Transaction_candidate
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.normalizeTransactionText
import com.samluiz.gyst.domain.repository.TransactionCandidateRepository
import kotlinx.datetime.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class SqlTransactionCandidateRepository(
    private val holder: DatabaseHolder,
    private val json: Json = Json,
) : TransactionCandidateRepository {
    override suspend fun insert(candidate: TransactionCandidate): TransactionCandidate =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                queries.selectTransactionCandidateByIdempotencyKey(candidate.idempotencyKey)
                    .executeAsOneOrNull()
                    ?.let { return@transactionWithResult it.toDomain(json) }
                queries.insertTransactionCandidateRecord(candidate, json)
                checkNotNull(queries.selectTransactionCandidateById(candidate.id).executeAsOneOrNull()).toDomain(json)
            }
        }

    override suspend fun insertAllAtomically(candidates: List<TransactionCandidate>): List<TransactionCandidate> {
        require(candidates.map { it.idempotencyKey }.distinct().size == candidates.size) {
            "Candidate batch idempotency keys must be unique"
        }
        return holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                candidates.map { candidate ->
                    queries.selectTransactionCandidateByIdempotencyKey(candidate.idempotencyKey)
                        .executeAsOneOrNull()
                        ?.toDomain(json)
                        ?: run {
                            queries.insertTransactionCandidateRecord(candidate, json)
                            checkNotNull(queries.selectTransactionCandidateById(candidate.id).executeAsOneOrNull())
                                .toDomain(json)
                        }
                }
            }
        }
    }

    override suspend fun get(id: String): TransactionCandidate? =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionCandidateById(id).executeAsOneOrNull()?.toDomain(json)
        }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): TransactionCandidate? =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionCandidateByIdempotencyKey(idempotencyKey)
                .executeAsOneOrNull()
                ?.toDomain(json)
        }

    override suspend fun byImportSession(sessionId: String): List<TransactionCandidate> =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionCandidatesByImportSession(sessionId).executeAsList().map {
                it.toDomain(json)
            }
        }

    override suspend fun pendingReview(): List<TransactionCandidate> =
        holder.withDatabase { database ->
            database.financeQueries.selectPendingTransactionCandidates().executeAsList().map { it.toDomain(json) }
        }

    override suspend fun duplicatesByFingerprint(
        fingerprint: String,
        excludingCandidateId: String,
    ): List<TransactionCandidate> =
        holder.withDatabase { database ->
            database.financeQueries.selectTransactionCandidatesByFingerprint(fingerprint, excludingCandidateId)
                .executeAsList()
                .map { it.toDomain(json) }
        }

    override suspend fun potentialExistingExpenseIds(candidate: TransactionCandidate): List<String> {
        val date = candidate.occurredDate ?: return emptyList()
        val amount = candidate.amountCents ?: return emptyList()
        val description = candidate.description?.takeIf(String::isNotBlank) ?: return emptyList()
        val normalizedDescription = normalizeTransactionText(description)
        return holder.withDatabase { database ->
            database.financeQueries.selectPotentialExpenseDuplicates(
                occurredDate = date.toString(),
                amountCents = amount,
            ).executeAsList()
                .filter { expense ->
                    normalizeTransactionText(expense.merchant.orEmpty()) == normalizedDescription ||
                        normalizeTransactionText(expense.note.orEmpty()) == normalizedDescription
                }.map { it.id }
        }
    }

    override suspend fun update(candidate: TransactionCandidate) {
        require(candidate.status == CandidateStatus.NEEDS_REVIEW) { "Only reviewable candidates can be edited" }
        holder.withDatabase { database ->
            database.financeQueries.updateTransactionCandidate(
                fingerprint = candidate.fingerprint,
                description = candidate.description,
                amountCents = candidate.amountCents,
                currency = candidate.currency,
                occurredDate = candidate.occurredDate?.toString(),
                occurredTime = candidate.occurredTime,
                timeZoneId = candidate.timeZoneId,
                transactionType = candidate.transactionType.name,
                suggestedCategoryId = candidate.suggestedCategoryId,
                suggestedCategoryLabel = candidate.suggestedCategoryLabel,
                accountOrPaymentMethod = candidate.accountOrPaymentMethod,
                installmentIndex = candidate.installmentIndex?.toLong(),
                installmentTotal = candidate.installmentTotal?.toLong(),
                note = candidate.note,
                confidence = candidate.confidence,
                supportingText = candidate.supportingText,
                warnings = json.encodeToString(candidate.warnings),
                lowConfidenceFields = json.encodeToString(candidate.lowConfidenceFields),
                selected = candidate.selected.asLong(),
                duplicateCandidateId = candidate.duplicateCandidateId,
                duplicateExpenseId = candidate.duplicateExpenseId,
                updatedAt = candidate.updatedAt.toString(),
                id = candidate.id,
            )
        }
    }

    override suspend fun updateStatus(
        id: String,
        status: CandidateStatus,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ) {
        require(status != CandidateStatus.APPROVED) {
            "Candidates are approved only through CandidateApprovalRepository"
        }
        holder.withDatabase { database ->
            database.financeQueries.updateTransactionCandidateStatus(
                status.name,
                errorType,
                safeErrorMessage,
                retryCount,
                updatedAt.toString(),
                id,
            )
        }
    }

    override suspend fun transitionStatus(
        id: String,
        expectedStatus: CandidateStatus,
        status: CandidateStatus,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ): Boolean {
        require(status != CandidateStatus.APPROVED) {
            "Candidates are approved only through CandidateApprovalRepository"
        }
        return holder.withDatabase { database ->
            database.transactionWithResult {
                database.financeQueries.transitionTransactionCandidateStatus(
                    status = status.name,
                    errorType = errorType,
                    errorMessage = safeErrorMessage,
                    retryCount = retryCount,
                    updatedAt = updatedAt.toString(),
                    id = id,
                    expectedStatus = expectedStatus.name,
                )
                database.financeQueries.selectTransactionCandidateById(id)
                    .executeAsOneOrNull()
                    ?.status == status.name
            }
        }
    }

    override suspend fun deleteUnapproved(id: String) {
        holder.withDatabase { database -> database.financeQueries.deleteUnapprovedTransactionCandidate(id) }
    }
}

internal fun com.samluiz.gyst.db.FinanceQueries.insertTransactionCandidateRecord(
    candidate: TransactionCandidate,
    json: Json = Json,
) {
    insertTransactionCandidate(
        id = candidate.id,
        import_session_id = candidate.importSessionId,
        source_type = candidate.source.name,
        source_reference = candidate.sourceReference,
        source_page = candidate.sourcePage,
        row_order = candidate.rowOrder,
        idempotency_key = candidate.idempotencyKey,
        transaction_fingerprint = candidate.fingerprint,
        description = candidate.description,
        amount_cents = candidate.amountCents,
        currency = candidate.currency,
        occurred_date = candidate.occurredDate?.toString(),
        occurred_time = candidate.occurredTime,
        time_zone_id = candidate.timeZoneId,
        transaction_type = candidate.transactionType.name,
        suggested_category_id = candidate.suggestedCategoryId,
        suggested_category_label = candidate.suggestedCategoryLabel,
        account_or_payment_method = candidate.accountOrPaymentMethod,
        installment_index = candidate.installmentIndex?.toLong(),
        installment_total = candidate.installmentTotal?.toLong(),
        note = candidate.note,
        confidence = candidate.confidence,
        source_image_hash = candidate.sourceImageHash,
        supporting_text = candidate.supportingText,
        warnings = json.encodeToString(candidate.warnings),
        low_confidence_fields = json.encodeToString(candidate.lowConfidenceFields),
        selected = candidate.selected.asLong(),
        status = candidate.status.name,
        duplicate_candidate_id = candidate.duplicateCandidateId,
        duplicate_expense_id = candidate.duplicateExpenseId,
        linked_expense_id = candidate.linkedExpenseId,
        provider_id = candidate.providerId,
        model_id = candidate.modelId,
        retry_count = candidate.retryCount,
        error_type = candidate.errorType,
        error_message = candidate.errorMessage,
        created_at = candidate.createdAt.toString(),
        updated_at = candidate.updatedAt.toString(),
    )
}

internal fun Transaction_candidate.toDomain(json: Json = Json): TransactionCandidate =
    TransactionCandidate(
        id = id,
        importSessionId = import_session_id,
        source = CandidateSource.valueOf(source_type),
        sourceReference = source_reference,
        sourcePage = source_page,
        rowOrder = row_order,
        idempotencyKey = idempotency_key,
        fingerprint = transaction_fingerprint,
        description = description,
        amountCents = amount_cents,
        currency = currency,
        occurredDate = occurred_date?.let(LocalDate::parse),
        occurredTime = occurred_time,
        timeZoneId = time_zone_id,
        transactionType = CandidateTransactionType.valueOf(transaction_type),
        suggestedCategoryId = suggested_category_id,
        suggestedCategoryLabel = suggested_category_label,
        accountOrPaymentMethod = account_or_payment_method,
        installmentIndex = installment_index?.toInt(),
        installmentTotal = installment_total?.toInt(),
        note = note,
        confidence = confidence,
        sourceImageHash = source_image_hash,
        supportingText = supporting_text,
        warnings = warnings.decodeList(json),
        lowConfidenceFields = low_confidence_fields.decodeSet(json),
        selected = selected == 1L,
        status = CandidateStatus.valueOf(status),
        duplicateCandidateId = duplicate_candidate_id,
        duplicateExpenseId = duplicate_expense_id,
        linkedExpenseId = linked_expense_id,
        providerId = provider_id,
        modelId = model_id,
        retryCount = retry_count,
        errorType = error_type,
        errorMessage = error_message,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

private fun String?.decodeList(json: Json): List<String> =
    this?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

private fun String?.decodeSet(json: Json): Set<String> =
    this?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull() }.orEmpty()
