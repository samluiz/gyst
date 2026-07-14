package com.samluiz.gyst.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.NotificationIngestion
import com.samluiz.gyst.domain.model.NotificationProcessingStatus
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.TransactionImportSession
import com.samluiz.gyst.domain.model.transactionFingerprint
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.repository.CandidateApprovalFailure
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class SqlCandidatePersistenceTest {
    private lateinit var driver: SqlDriver
    private lateinit var holder: DatabaseHolder
    private lateinit var candidates: SqlTransactionCandidateRepository
    private lateinit var approvals: SqlCandidateApprovalRepository
    private lateinit var imports: SqlTransactionImportRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GystDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        holder = DatabaseHolder(driver, NoReloadDriverFactory)
        candidates = SqlTransactionCandidateRepository(holder)
        approvals = SqlCandidateApprovalRepository(holder)
        imports = SqlTransactionImportRepository(holder)
        runTest {
            SqlCategoryRepository(holder).upsert(Category("food", "Food", CategoryType.ESSENTIAL))
        }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun candidateAndImportIdempotencySurviveRepositoryRecreation() =
        runTest {
            val session = imports.create(importSession("session"))
            assertEquals(session, imports.create(session.copy(id = "other")))
            val original = candidates.insert(candidate("candidate", "session"))
            val reused = candidates.insert(original.copy(id = "other"))
            assertEquals(original.id, reused.id)

            val recreated = SqlTransactionCandidateRepository(holder)
            assertEquals(listOf(original.id), recreated.byImportSession("session").map { it.id })
        }

    @Test
    fun unsupportedTransactionTypeIsRejectedWithoutLedgerMutation() =
        runTest {
            candidates.insert(candidate("income", null).copy(transactionType = CandidateTransactionType.INCOME))
            val result = approvals.approve(command("income"))

            assertEquals(
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.UNSUPPORTED_TYPE),
                result,
            )
            assertNull(SqlExpenseRepository(holder).getById("expense-income"))
        }

    @Test
    fun doubleApprovalCreatesExactlyOneExpense() =
        runTest {
            candidates.insert(candidate("candidate", null))
            val first = approvals.approve(command("candidate"))
            val second = approvals.approve(command("candidate"))

            assertIs<CandidateApprovalResult.Approved>(first)
            assertEquals(CandidateApprovalResult.AlreadyApproved("expense-candidate"), second)
            assertEquals("expense-candidate", SqlExpenseRepository(holder).getById("expense-candidate")?.id)
            assertNull(candidates.get("candidate")?.supportingText)
        }

    @Test
    fun terminalRejectionRedactsSupportingSourceText() =
        runTest {
            candidates.insert(candidate("candidate", null))

            val transitioned =
                candidates.transitionStatus(
                    id = "candidate",
                    expectedStatus = CandidateStatus.NEEDS_REVIEW,
                    status = CandidateStatus.REJECTED,
                    retryCount = 0,
                    errorType = null,
                    safeErrorMessage = null,
                    updatedAt = instant(6),
                )

            assertEquals(true, transitioned)
            assertNull(candidates.get("candidate")?.supportingText)
        }

    @Test
    fun rejectionCannotRaceAndOverwriteAnApprovedCandidate() =
        runTest {
            candidates.insert(candidate("candidate", null))
            assertIs<CandidateApprovalResult.Approved>(approvals.approve(command("candidate")))

            val transitioned =
                candidates.transitionStatus(
                    id = "candidate",
                    expectedStatus = CandidateStatus.NEEDS_REVIEW,
                    status = CandidateStatus.REJECTED,
                    retryCount = 0,
                    errorType = null,
                    safeErrorMessage = null,
                    updatedAt = instant(6),
                )

            assertEquals(false, transitioned)
            assertEquals(CandidateStatus.APPROVED, candidates.get("candidate")?.status)
        }

    @Test
    fun atomicImportRollsBackEveryCandidateWhenOneRowIsInvalid() =
        runTest {
            imports.create(importSession("session").copy(status = ImportSessionStatus.READY))
            candidates.insert(candidate("valid", "session", row = 0))
            candidates.insert(candidate("invalid", "session", row = 1).copy(amountCents = 0))

            val results =
                approvals.approveImportAtomically(
                    importSessionId = "session",
                    commands = listOf(command("valid"), command("invalid")),
                    completedAt = instant(5),
                )

            assertEquals(2, results.size)
            assertNull(SqlExpenseRepository(holder).getById("expense-valid"))
            assertNull(SqlExpenseRepository(holder).getById("expense-invalid"))
            assertEquals(ImportSessionStatus.READY, imports.get("session")?.status)
        }

    @Test
    fun validMultiRowImportCommitsEveryExpenseAndCompletesSession() =
        runTest {
            imports.create(importSession("session").copy(status = ImportSessionStatus.READY))
            candidates.insert(candidate("first", "session", row = 0))
            candidates.insert(
                candidate("second", "session", row = 1).copy(
                    idempotencyKey = "candidate-key-second",
                    description = "Another Store",
                    fingerprint =
                        transactionFingerprint(
                            LocalDate(2026, 7, 15),
                            2_000,
                            "BRL",
                            "Another Store",
                            "DEBIT",
                            CandidateTransactionType.EXPENSE,
                        ),
                    occurredDate = LocalDate(2026, 7, 15),
                    amountCents = 2_000,
                ),
            )

            val results =
                approvals.approveImportAtomically(
                    "session",
                    listOf(command("first"), command("second")),
                    instant(5),
                )

            assertEquals(2, results.filterIsInstance<CandidateApprovalResult.Approved>().size)
            assertEquals(ImportSessionStatus.COMPLETED, imports.get("session")?.status)
            assertEquals(2, imports.get("session")?.importedCount)
            assertEquals("expense-first", SqlExpenseRepository(holder).getById("expense-first")?.id)
            assertEquals("expense-second", SqlExpenseRepository(holder).getById("expense-second")?.id)
        }

    @Test
    fun beginningRetryClearsOldDraftRowsAndTransitionsSessionAtomically() =
        runTest {
            imports.create(importSession("session").copy(status = ImportSessionStatus.READY))
            candidates.insert(candidate("first", "session", row = 0))
            candidates.insert(candidate("second", "session", row = 1).copy(idempotencyKey = "second-key"))

            val started = imports.beginAnalysis("session", instant(5))

            assertEquals(ImportSessionStatus.ANALYZING, started.status)
            assertEquals(emptyList(), candidates.byImportSession("session"))
        }

    @Test
    fun duplicateNotificationCallbackIsIdempotentAndContentCanBeRedacted() =
        runTest {
            val notifications = SqlNotificationIngestionRepository(holder)
            val original = notification("notification")
            val stored = notifications.insertIdempotently(original)
            val duplicate = notifications.insertIdempotently(original.copy(id = "different"))
            assertEquals(stored.id, duplicate.id)

            val suggestion =
                notifications.storeSuggestionAndRedact(
                    ingestionId = stored.id,
                    candidate = candidate("notification-candidate", null),
                    updatedAt = instant(3),
                )
            val redacted = notifications.get(stored.id)
            assertEquals("notification-candidate", suggestion.id)
            assertEquals(suggestion.id, redacted?.candidateId)
            assertNull(redacted?.title)
            assertNull(redacted?.mainText)
            assertNull(redacted?.normalizedText)
            assertEquals(instant(3), redacted?.contentRedactedAt)
        }

    @Test
    fun interruptedNotificationProcessingReturnsToDurableQueueWithoutLosingProtectedText() =
        runTest {
            val notifications = SqlNotificationIngestionRepository(holder)
            notifications.insertIdempotently(
                notification("interrupted").copy(
                    processingStatus = NotificationProcessingStatus.PROCESSING,
                    normalizedText = "encrypted-minimal-text",
                ),
            )

            notifications.recoverInterruptedProcessing(instant(3))

            val recovered = notifications.get("interrupted")
            assertEquals(NotificationProcessingStatus.QUEUED, recovered?.processingStatus)
            assertEquals("PROCESS_INTERRUPTED", recovered?.errorType)
            assertEquals("encrypted-minimal-text", recovered?.normalizedText)
            assertEquals(listOf("interrupted"), notifications.queuedForProcessing().map { it.id })
        }

    @Test
    fun multipleSourceNotificationsConvergeOnOneSuggestion() =
        runTest {
            val notifications = SqlNotificationIngestionRepository(holder)
            val first = notifications.insertIdempotently(notification("first"))
            val second =
                notifications.insertIdempotently(
                    notification("second").copy(
                        notificationKey = "second-key",
                        notificationFingerprint = "second-fingerprint",
                    ),
                )
            val candidate = candidate("notification-candidate", null)

            val firstSuggestion = notifications.storeSuggestionAndRedact(first.id, candidate, instant(3))
            val secondSuggestion = notifications.storeSuggestionAndRedact(second.id, candidate, instant(4))

            assertEquals(firstSuggestion.id, secondSuggestion.id)
            assertEquals(firstSuggestion.id, notifications.get(first.id)?.candidateId)
            assertEquals(firstSuggestion.id, notifications.get(second.id)?.candidateId)
            assertEquals(1, candidates.pendingReview().count { it.id == firstSuggestion.id })
        }

    private fun importSession(id: String) =
        TransactionImportSession(
            id = id,
            idempotencyKey = "import-key",
            status = ImportSessionStatus.CREATED,
            providerProfileId = null,
            providerId = "test",
            modelId = "vision-test",
            localeTag = "pt-BR",
            defaultCurrency = "BRL",
            allowPartial = false,
            selectedCount = 0,
            importedCount = 0,
            errorType = null,
            errorMessage = null,
            createdAt = instant(0),
            updatedAt = instant(0),
            completedAt = null,
        )

    private fun candidate(
        id: String,
        sessionId: String?,
        row: Long = 0,
    ): TransactionCandidate {
        val date = LocalDate(2026, 7, 14)
        val fingerprint =
            transactionFingerprint(
                date = date,
                amountCents = 4_290,
                currency = "BRL",
                description = "Example Store",
                account = "DEBIT",
                type = CandidateTransactionType.EXPENSE,
            )
        return TransactionCandidate(
            id = id,
            importSessionId = sessionId,
            source = if (sessionId == null) CandidateSource.ANDROID_NOTIFICATION else CandidateSource.IMAGE,
            sourceReference = if (sessionId == null) "notification-fingerprint" else "image-1",
            sourcePage = "1",
            rowOrder = row,
            idempotencyKey = "candidate-key-$id",
            fingerprint = fingerprint,
            description = "Example Store",
            amountCents = 4_290,
            currency = "BRL",
            occurredDate = date,
            occurredTime = "12:30",
            timeZoneId = "America/Sao_Paulo",
            transactionType = CandidateTransactionType.EXPENSE,
            suggestedCategoryId = "food",
            accountOrPaymentMethod = "DEBIT",
            installmentIndex = null,
            installmentTotal = null,
            note = null,
            confidence = 0.9,
            sourceImageHash = sessionId?.let { "image-hash" },
            supportingText = "Purchase at Example Store",
            warnings = emptyList(),
            lowConfidenceFields = emptySet(),
            selected = true,
            status = CandidateStatus.NEEDS_REVIEW,
            duplicateCandidateId = null,
            duplicateExpenseId = null,
            linkedExpenseId = null,
            providerId = null,
            modelId = null,
            retryCount = 0,
            errorType = null,
            errorMessage = null,
            createdAt = instant(1),
            updatedAt = instant(1),
        )
    }

    private fun command(candidateId: String) =
        ApproveExpenseCandidateCommand(
            candidateId = candidateId,
            expenseId = "expense-$candidateId",
            originId = "origin-$candidateId",
            createdAt = instant(4),
        )

    private fun notification(id: String) =
        NotificationIngestion(
            id = id,
            sourcePackage = "com.example.bank",
            notificationId = 42,
            notificationKey = "key",
            notificationFingerprint = "fingerprint",
            postedAt = instant(1),
            title = "Purchase approved",
            mainText = "R$ 42,90 at Example Store",
            expandedText = null,
            channelId = "purchases",
            category = "msg",
            normalizedText = "purchase 42.90 example store",
            processingStatus = NotificationProcessingStatus.RECEIVED,
            candidateId = null,
            retryCount = 0,
            errorType = null,
            errorMessage = null,
            contentRedactedAt = null,
            createdAt = instant(1),
            updatedAt = instant(1),
        )

    private fun instant(second: Int): Instant = Instant.parse("2026-07-14T00:00:${second.toString().padStart(2, '0')}Z")

    private object NoReloadDriverFactory : SqlDriverFactory {
        override fun createDriver(): SqlDriver = error("Not used")
    }
}
