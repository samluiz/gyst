package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.FinanceQueries
import com.samluiz.gyst.domain.model.CandidateIssueSeverity
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.validateCandidateForExpenseApproval
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.repository.CandidateApprovalFailure
import com.samluiz.gyst.domain.repository.CandidateApprovalRepository
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import kotlin.time.Instant

class SqlCandidateApprovalRepository(
    private val holder: DatabaseHolder,
) : CandidateApprovalRepository {
    override suspend fun approve(command: ApproveExpenseCandidateCommand): CandidateApprovalResult =
        holder.withDatabase { database ->
            database.transactionWithResult {
                val prepared = prepare(database.financeQueries, command, expectedImportSessionId = null)
                when (prepared) {
                    is ApprovalPreparation.Ready -> apply(database.financeQueries, prepared)
                    is ApprovalPreparation.Result -> prepared.value
                }
            }
        }

    override suspend fun approveImportAtomically(
        importSessionId: String,
        commands: List<ApproveExpenseCandidateCommand>,
        completedAt: Instant,
    ): List<CandidateApprovalResult> {
        if (commands.isEmpty()) return emptyList()
        require(commands.map { it.candidateId }.distinct().size == commands.size) {
            "Import approval commands must have unique candidate IDs"
        }
        return holder.withDatabase { database ->
            database.transactionWithResult {
                val queries = database.financeQueries
                val session = queries.selectTransactionImportSessionById(importSessionId).executeAsOneOrNull()
                if (session == null) {
                    return@transactionWithResult commands.map {
                        CandidateApprovalResult.Rejected(CandidateApprovalFailure.IMPORT_SESSION_MISMATCH)
                    }
                }
                val sessionStatus = ImportSessionStatus.valueOf(session.status)
                if (sessionStatus !in setOf(ImportSessionStatus.READY, ImportSessionStatus.COMPLETED)) {
                    return@transactionWithResult commands.map {
                        CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_STATE)
                    }
                }
                val expectedCandidateIds =
                    queries.selectTransactionCandidatesByImportSession(importSessionId)
                        .executeAsList()
                        .filter { it.selected == 1L && it.status in setOf("NEEDS_REVIEW", "APPROVED") }
                        .map { it.id }
                        .toSet()
                if (expectedCandidateIds != commands.map { it.candidateId }.toSet()) {
                    return@transactionWithResult commands.map {
                        CandidateApprovalResult.Rejected(CandidateApprovalFailure.IMPORT_SESSION_MISMATCH)
                    }
                }

                val preparations = commands.map { prepare(queries, it, importSessionId) }
                val rejected = preparations.filterIsInstance<ApprovalPreparation.Result>().map { it.value }
                if (rejected.any { it is CandidateApprovalResult.Rejected }) {
                    return@transactionWithResult preparations.map { preparation ->
                        when (preparation) {
                            is ApprovalPreparation.Result -> preparation.value
                            is ApprovalPreparation.Ready ->
                                CandidateApprovalResult.Rejected(CandidateApprovalFailure.ATOMIC_BATCH_ABORTED)
                        }
                    }
                }

                queries.updateTransactionImportSessionStatus(
                    status = ImportSessionStatus.IMPORTING.name,
                    selectedCount = commands.size.toLong(),
                    importedCount = session.imported_count,
                    errorType = null,
                    errorMessage = null,
                    updatedAt = completedAt.toString(),
                    completedAt = null,
                    id = importSessionId,
                )
                val results =
                    preparations.map { preparation ->
                        when (preparation) {
                            is ApprovalPreparation.Ready -> apply(queries, preparation)
                            is ApprovalPreparation.Result -> preparation.value
                        }
                    }
                queries.updateTransactionImportSessionStatus(
                    status = ImportSessionStatus.COMPLETED.name,
                    selectedCount = commands.size.toLong(),
                    importedCount = results.size.toLong(),
                    errorType = null,
                    errorMessage = null,
                    updatedAt = completedAt.toString(),
                    completedAt = completedAt.toString(),
                    id = importSessionId,
                )
                queries.redactTransactionImportSourceCustody(importSessionId)
                results
            }
        }
    }

    private fun prepare(
        queries: FinanceQueries,
        command: ApproveExpenseCandidateCommand,
        expectedImportSessionId: String?,
    ): ApprovalPreparation {
        val candidateRow =
            queries.selectTransactionCandidateById(command.candidateId).executeAsOneOrNull()
                ?: return ApprovalPreparation.Result(
                    CandidateApprovalResult.Rejected(CandidateApprovalFailure.CANDIDATE_NOT_FOUND),
                )
        val candidate = candidateRow.toDomain()
        if (expectedImportSessionId != null && candidate.importSessionId != expectedImportSessionId) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.IMPORT_SESSION_MISMATCH),
            )
        }
        queries.selectExpenseOriginByIdempotencyKey(candidate.idempotencyKey).executeAsOneOrNull()?.let {
            return ApprovalPreparation.Result(CandidateApprovalResult.AlreadyApproved(it.expense_id))
        }
        if (queries.selectExpenseById(command.expenseId).executeAsOneOrNull() != null) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.DUPLICATE_OPERATION),
            )
        }
        if (candidate.status == CandidateStatus.APPROVED && candidate.linkedExpenseId != null) {
            return ApprovalPreparation.Result(CandidateApprovalResult.AlreadyApproved(candidate.linkedExpenseId))
        }
        if (candidate.status != CandidateStatus.NEEDS_REVIEW || !candidate.selected) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_STATE),
            )
        }
        if (candidate.transactionType != CandidateTransactionType.EXPENSE) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.UNSUPPORTED_TYPE),
            )
        }
        val hasErrors =
            validateCandidateForExpenseApproval(candidate).any { it.severity == CandidateIssueSeverity.ERROR }
        if (hasErrors || candidate.fingerprint == null) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_CANDIDATE),
            )
        }
        if (queries.selectAllCategories().executeAsList().none { it.id == candidate.suggestedCategoryId }) {
            return ApprovalPreparation.Result(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_CANDIDATE),
            )
        }
        return ApprovalPreparation.Ready(
            command = command,
            candidate = candidate,
            paymentMethod = PaymentMethod.valueOf(checkNotNull(candidate.accountOrPaymentMethod).uppercase()),
        )
    }

    private fun apply(
        queries: FinanceQueries,
        preparation: ApprovalPreparation.Ready,
    ): CandidateApprovalResult {
        val command = preparation.command
        val candidate = preparation.candidate
        queries.insertApprovedExpense(
            id = command.expenseId,
            occurred_at = checkNotNull(candidate.occurredDate).toString(),
            amount_cents = checkNotNull(candidate.amountCents),
            category_id = checkNotNull(candidate.suggestedCategoryId),
            note = candidate.note,
            merchant = candidate.description,
            payment_method = preparation.paymentMethod.name,
            created_at = command.createdAt.toString(),
        )
        queries.insertExpenseOrigin(
            id = command.originId,
            expense_id = command.expenseId,
            candidate_id = candidate.id,
            import_session_id = candidate.importSessionId,
            source_type = candidate.source.name,
            source_image_hash = candidate.sourceImageHash,
            notification_fingerprint =
                candidate.sourceReference.takeIf { candidate.source == CandidateSource.ANDROID_NOTIFICATION },
            transaction_fingerprint = checkNotNull(candidate.fingerprint),
            idempotency_key = candidate.idempotencyKey,
            confidence = candidate.confidence,
            provider_id = candidate.providerId,
            model_id = candidate.modelId,
            created_at = command.createdAt.toString(),
        )
        queries.markTransactionCandidateApproved(
            expenseId = command.expenseId,
            updatedAt = command.createdAt.toString(),
            id = candidate.id,
        )
        return CandidateApprovalResult.Approved(command.expenseId)
    }
}

private sealed interface ApprovalPreparation {
    data class Ready(
        val command: ApproveExpenseCandidateCommand,
        val candidate: TransactionCandidate,
        val paymentMethod: PaymentMethod,
    ) : ApprovalPreparation

    data class Result(
        val value: CandidateApprovalResult,
    ) : ApprovalPreparation
}
