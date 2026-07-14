package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.TransactionCandidate
import kotlin.time.Instant

interface TransactionCandidateRepository {
    suspend fun insert(candidate: TransactionCandidate): TransactionCandidate

    suspend fun insertAllAtomically(candidates: List<TransactionCandidate>): List<TransactionCandidate>

    suspend fun get(id: String): TransactionCandidate?

    suspend fun getByIdempotencyKey(idempotencyKey: String): TransactionCandidate?

    suspend fun byImportSession(sessionId: String): List<TransactionCandidate>

    suspend fun pendingReview(): List<TransactionCandidate>

    suspend fun duplicatesByFingerprint(
        fingerprint: String,
        excludingCandidateId: String,
    ): List<TransactionCandidate>

    suspend fun potentialExistingExpenseIds(candidate: TransactionCandidate): List<String>

    suspend fun update(candidate: TransactionCandidate)

    suspend fun updateStatus(
        id: String,
        status: CandidateStatus,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    )

    suspend fun transitionStatus(
        id: String,
        expectedStatus: CandidateStatus,
        status: CandidateStatus,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ): Boolean

    suspend fun deleteUnapproved(id: String)
}

data class ApproveExpenseCandidateCommand(
    val candidateId: String,
    val expenseId: String,
    val originId: String,
    val createdAt: Instant,
)

enum class CandidateApprovalFailure {
    CANDIDATE_NOT_FOUND,
    IMPORT_SESSION_MISMATCH,
    INVALID_STATE,
    UNSUPPORTED_TYPE,
    INVALID_CANDIDATE,
    DUPLICATE_OPERATION,
    ATOMIC_BATCH_ABORTED,
}

sealed interface CandidateApprovalResult {
    data class Approved(
        val expenseId: String,
    ) : CandidateApprovalResult

    data class AlreadyApproved(
        val expenseId: String,
    ) : CandidateApprovalResult

    data class Rejected(
        val failure: CandidateApprovalFailure,
    ) : CandidateApprovalResult
}

interface CandidateApprovalRepository {
    suspend fun approve(command: ApproveExpenseCandidateCommand): CandidateApprovalResult

    /** The default image-import path: every command succeeds in one transaction or none do. */
    suspend fun approveImportAtomically(
        importSessionId: String,
        commands: List<ApproveExpenseCandidateCommand>,
        completedAt: Instant,
    ): List<CandidateApprovalResult>
}
