package com.samluiz.gyst.data.importer

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.data.repository.DatabaseHolder
import com.samluiz.gyst.data.repository.SqlCandidateApprovalRepository
import com.samluiz.gyst.data.repository.SqlCategoryRepository
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.data.repository.SqlExpenseRepository
import com.samluiz.gyst.data.repository.SqlProviderProfileRepository
import com.samluiz.gyst.data.repository.SqlTransactionCandidateRepository
import com.samluiz.gyst.data.repository.SqlTransactionImportRepository
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.model.CandidateIssueCode
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.CategoryType
import com.samluiz.gyst.domain.model.Expense
import com.samluiz.gyst.domain.model.ImportSessionStatus
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.RecurrenceType
import com.samluiz.gyst.domain.model.YearMonth
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiProviderResponse
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import com.samluiz.gyst.domain.service.ImageImportFailureCode
import com.samluiz.gyst.domain.service.ImageImportStage
import com.samluiz.gyst.domain.service.ImageSourceCapabilities
import com.samluiz.gyst.domain.service.ImageSourceFailure
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.ImageSourceService
import com.samluiz.gyst.domain.service.TEMPORARY_IMAGE_TTL_MILLIS
import com.samluiz.gyst.domain.service.TemporaryImageHandle
import com.samluiz.gyst.domain.service.TransactionCandidateEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultImageImportServiceTest {
    private lateinit var driver: SqlDriver
    private lateinit var holder: DatabaseHolder
    private lateinit var profiles: SqlProviderProfileRepository
    private lateinit var imports: SqlTransactionImportRepository
    private lateinit var candidates: SqlTransactionCandidateRepository
    private lateinit var expenses: SqlExpenseRepository
    private lateinit var categories: SqlCategoryRepository
    private lateinit var imageSource: FakeImageSourceService
    private lateinit var secrets: FakeSecretStore
    private lateinit var ai: FakeAiProviderClient
    private lateinit var service: DefaultImageImportService
    private var nextId = 0

    @BeforeTest
    fun setUp() =
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            GystDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            holder = DatabaseHolder(driver, NoReloadDriverFactory)
            profiles = SqlProviderProfileRepository(holder)
            imports = SqlTransactionImportRepository(holder)
            candidates = SqlTransactionCandidateRepository(holder)
            expenses = SqlExpenseRepository(holder)
            categories = SqlCategoryRepository(holder)
            imageSource = FakeImageSourceService()
            secrets = FakeSecretStore(mutableMapOf("vision" to "secret"))
            ai = FakeAiProviderClient()
            categories.upsert(Category("food", "Food", CategoryType.ESSENTIAL))
            profiles.upsert(profile("vision", vision = true))
            profiles.upsert(profile("text", vision = false))
            service = newService()
            service.initialize()
        }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun imageSelectionIsLocalUntilExplicitAnalysisAndConfirmation() =
        runTest {
            selectOneImage()

            assertEquals(0, ai.calls)
            val selectedDraft = imports.list().single()
            assertEquals(ImportSessionStatus.CREATED, selectedDraft.status)
            assertNull(selectedDraft.providerProfileId)
            assertEquals("hash-1", imports.sources(selectedDraft.id).single().sourceHash)
            service.analyze("vision", "pt-BR", "BRL")
            val candidate = service.state.value.candidates.single().candidate

            assertEquals(1, ai.calls)
            assertNull(expenses.byMonth(YearMonth(2026, 7)).singleOrNull())
            service.confirmImport()
            assertEquals(ImageImportStage.COMPLETED, service.state.value.stage)
            assertEquals(candidate.description, expenses.byMonth(YearMonth(2026, 7)).single().merchant)
            val completedSource = imports.sources(checkNotNull(service.state.value.sessionId)).single()
            assertNull(completedSource.temporaryReference)
            assertNull(completedSource.byteSize)
        }

    @Test
    fun selectedSourcesSurviveProcessRecreationBeforeAnalysisAndReuseTheSameSession() =
        runTest {
            selectOneImage()
            val sessionId = checkNotNull(service.state.value.sessionId)

            val recreatedSource = FakeImageSourceService().also { it.nextSelection = imageSource.nextSelection }
            val recreated = newService(recreatedSource)
            recreated.initialize()

            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(ImageImportStage.SOURCES_SELECTED, recreated.state.value.stage)
            assertNull(recreated.state.value.failure)
            assertTrue(recreated.state.value.canAnalyze)

            recreated.analyze("vision", "pt-BR", "BRL")

            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(ImageImportStage.PREVIEW, recreated.state.value.stage)
            assertEquals(1, imports.list().size)
            val configured = checkNotNull(imports.get(sessionId))
            assertEquals("vision", configured.providerProfileId)
            assertEquals("pt-BR", configured.localeTag)
            assertEquals("BRL", configured.defaultCurrency)
        }

    @Test
    fun platformResultDeliveredAfterRecreationIsPersistedBeforeAcknowledgement() =
        runTest {
            val recoveredSource =
                FakeImageSourceService().also {
                    it.recoveredImages = listOf(image("recovered-image", "recovered-hash"))
                }
            val recreated = newService(recoveredSource)

            recreated.initialize()

            val sessionId = checkNotNull(recreated.state.value.sessionId)
            assertEquals(ImportSessionStatus.CREATED, imports.get(sessionId)?.status)
            assertEquals("recovered-hash", imports.sources(sessionId).single().sourceHash)
            assertEquals(listOf("recovered-image"), recoveredSource.acknowledged)
            assertTrue(recoveredSource.recoveredImages.isEmpty())
        }

    @Test
    fun initializationAndActivityResumeRecoveryCannotOverwriteEachOther() =
        runTest {
            imageSource.blockPendingRecovery = true
            imageSource.pendingRecoveryCalls = 0
            imageSource.maximumConcurrentPendingRecoveryCalls = 0
            val initializing = launch { service.initialize() }
            imageSource.pendingRecoveryEntered.await()

            val activityResume = launch { service.recoverPendingSources() }
            runCurrent()

            assertEquals(1, imageSource.pendingRecoveryCalls)
            assertEquals(1, imageSource.maximumConcurrentPendingRecoveryCalls)

            imageSource.releasePendingRecovery.complete(Unit)
            initializing.join()
            activityResume.join()

            assertEquals(1, imageSource.maximumConcurrentPendingRecoveryCalls)
        }

    @Test
    fun queueReplayAfterDatabaseCommitDoesNotDeleteThePersistedSourceFile() =
        runTest {
            val selected = image("queued-image", "queued-hash")
            imageSource.nextSelection = listOf(selected)
            imageSource.recoveredImages = listOf(selected)
            imageSource.failAcknowledgement = true

            service.selectImages()
            val sessionId = checkNotNull(service.state.value.sessionId)
            assertEquals(listOf("queued-image"), imageSource.recoveredImages.map { it.id })

            imageSource.failAcknowledgement = false
            val recreated = newService(imageSource)
            recreated.initialize()

            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(ImageImportStage.SOURCES_SELECTED, recreated.state.value.stage)
            assertTrue(imageSource.recoveredImages.isEmpty())
            assertFalse(imageSource.cleaned.contains("queued-image"))
        }

    @Test
    fun removingSourceAtomicallyReplacesDraftWithoutLeavingAnOrphanSession() =
        runTest {
            imageSource.nextSelection =
                listOf(
                    image("image-1", "hash-1"),
                    image("image-2", "hash-2"),
                )
            service.selectImages()
            val originalSessionId = checkNotNull(service.state.value.sessionId)

            service.removeImage("image-1")

            val replacementSessionId = checkNotNull(service.state.value.sessionId)
            assertTrue(replacementSessionId != originalSessionId)
            assertNull(imports.get(originalSessionId))
            assertEquals(listOf("hash-2"), imports.sources(replacementSessionId).map { it.sourceHash })
            assertTrue(imageSource.cleaned.contains("image-1"))
            assertEquals(1, imports.list().size)
        }

    @Test
    fun expiredSourceDraftDeletesDatabaseCustodyAndTemporaryImage() =
        runTest {
            selectOneImage()
            val sessionId = checkNotNull(service.state.value.sessionId)
            val persistedSourceId = imports.sources(sessionId).single().id
            val later =
                newService(
                    source = imageSource,
                    clock = {
                        Instant.fromEpochMilliseconds(
                            FIXED_NOW.toEpochMilliseconds() + TEMPORARY_IMAGE_TTL_MILLIS + 1,
                        )
                    },
                )

            later.initialize()

            assertNull(imports.get(sessionId))
            assertTrue(imageSource.cleaned.contains(persistedSourceId))
            assertEquals(ImageImportStage.IDLE, later.state.value.stage)
        }

    @Test
    fun compatibilityAndCredentialsAreExplicitAndNeverSilentlySwitched() =
        runTest {
            assertEquals(listOf("vision"), service.state.value.compatibleProfiles.map { it.id })
            assertTrue(service.state.value.canSelectImages)
            assertTrue(service.state.value.canCaptureImage)
            assertEquals(10, service.state.value.maximumSelection)
            selectOneImage()

            service.analyze("text", "pt-BR", "BRL")
            assertEquals(ImageImportFailureCode.UNSUPPORTED_PROVIDER_CAPABILITY, service.state.value.failure?.code)
            assertEquals(0, ai.calls)

            secrets.keys.remove("vision")
            service.analyze("vision", "pt-BR", "BRL")
            assertEquals(ImageImportFailureCode.PROVIDER_NOT_CONFIGURED, service.state.value.failure?.code)
            assertEquals(0, ai.calls)
            service.initialize()
            assertTrue(service.state.value.compatibleProfiles.isEmpty())
        }

    @Test
    fun imageSourceFailuresRemainActionableInsteadOfLookingPlatformUnsupported() =
        runTest {
            val expected =
                listOf(
                    ImageSourceFailure.UNSUPPORTED to ImageImportFailureCode.IMAGE_SOURCE,
                    ImageSourceFailure.PERMISSION_DENIED to ImageImportFailureCode.IMAGE_SOURCE_PERMISSION_DENIED,
                    ImageSourceFailure.FILE_TOO_LARGE to ImageImportFailureCode.IMAGE_SOURCE_TOO_LARGE,
                    ImageSourceFailure.UNSUPPORTED_FORMAT to ImageImportFailureCode.IMAGE_SOURCE_UNSUPPORTED_FORMAT,
                    ImageSourceFailure.IO_FAILURE to ImageImportFailureCode.IMAGE_SOURCE_READ_FAILURE,
                )

            expected.forEach { (sourceFailure, importFailure) ->
                imageSource.nextResult = ImageSourceResult.Failed(sourceFailure)
                service.selectImages()
                assertEquals(importFailure, service.state.value.failure?.code)
            }
        }

    @Test
    fun multiImageResponseNormalizesLocaleAmountsAndDates() =
        runTest {
            imageSource.nextSelection =
                listOf(image("image-1", "hash-1"), image("image-2", "hash-2"))
            service.selectImages()
            ai.content =
                envelope(
                    transaction(amount = "R$ 1.234,56", date = "14/07/2026", source = "image-1"),
                    transaction(
                        description = "Padaria",
                        amount = "12.50",
                        date = "2026-07-15",
                        source = "image-2",
                    ),
                )

            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(2, ai.receivedImages.size)
            assertEquals(listOf(123_456L, 1_250L), service.state.value.candidates.map { it.candidate.amountCents })
            assertEquals(
                listOf(LocalDate(2026, 7, 14), LocalDate(2026, 7, 15)),
                service.state.value.candidates.map { it.candidate.occurredDate },
            )
        }

    @Test
    fun unknownMultiImageSourceKeepsDeterministicAggregateProvenance() =
        runTest {
            imageSource.nextSelection = listOf(image("image-1", "hash-1"), image("image-2", "hash-2"))
            service.selectImages()
            ai.content = envelope(transaction(source = "provider-invented-source"))

            service.analyze("vision", "pt-BR", "BRL")

            val candidate = service.state.value.candidates.single().candidate
            assertTrue(candidate.sourceReference?.startsWith("image-import-session:") == true)
            assertTrue(candidate.sourceImageHash?.isNotBlank() == true)
            assertTrue("ambiguous-source-image" in candidate.warnings)
        }

    @Test
    fun missingDateDefaultsToAnalysisDayRatherThanSourceSelectionDay() =
        runTest {
            var current = Instant.parse("2026-07-14T23:30:00Z")
            service = newService(clock = { current })
            service.initialize()
            selectOneImage()
            current = Instant.parse("2026-07-15T12:00:00Z")
            ai.content = envelope(transaction(date = null))

            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(LocalDate(2026, 7, 15), service.state.value.candidates.single().candidate.occurredDate)
        }

    @Test
    fun malformedOutputCanRetrySameDurableSessionWithoutDuplicateRowsOrSources() =
        runTest {
            selectOneImage()
            ai.content = "not-json"
            service.analyze("vision", "pt-BR", "BRL")
            val sessionId = service.state.value.sessionId
            assertEquals(ImageImportFailureCode.INVALID_STRUCTURED_RESPONSE, service.state.value.failure?.code)

            ai.content = envelope(transaction())
            service.retryAnalysis()
            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(sessionId, service.state.value.sessionId)
            assertEquals(1, imports.list().size)
            assertEquals(1, imports.sources(checkNotNull(sessionId)).size)
            assertEquals(1, candidates.byImportSession(sessionId).size)
            assertEquals(3, ai.calls)
            assertFalse(
                service.state.value.candidates.single().candidate.warnings.contains("source-image-seen-before"),
            )
        }

    @Test
    fun selectingAnImageHashFromAnOlderSessionSurfacesAReplayWarning() =
        runTest {
            selectOneImage()
            service.analyze("vision", "pt-BR", "BRL")
            service.confirmImport()
            service.clear()

            selectOneImage()
            service.analyze("vision", "pt-BR", "BRL")

            assertTrue(
                service.state.value.candidates.single().candidate.warnings.contains("source-image-seen-before"),
            )
            assertEquals(2, imports.list().size)
        }

    @Test
    fun providerFailuresAreTypedAndRetryPolicyIsActionable() =
        runTest {
            selectOneImage()
            val cases =
                listOf(
                    AiProviderFailureCode.AUTHENTICATION to ImageImportFailureCode.AUTHENTICATION,
                    AiProviderFailureCode.RATE_LIMITED to ImageImportFailureCode.RATE_LIMITED,
                    AiProviderFailureCode.NETWORK to ImageImportFailureCode.NETWORK,
                    AiProviderFailureCode.TIMEOUT to ImageImportFailureCode.TIMEOUT,
                )
            cases.forEachIndexed { index, (providerCode, expectedCode) ->
                ai.failure = AiProviderException(providerCode, retryAfterSeconds = 7)
                if (index == 0) {
                    service.analyze("vision", "pt-BR", "BRL")
                } else {
                    service.retryAnalysis()
                }
                assertEquals(expectedCode, service.state.value.failure?.code)
                assertEquals(providerCode != AiProviderFailureCode.AUTHENTICATION, service.state.value.failure?.retryable)
            }
        }

    @Test
    fun rejectedProviderRequestExposesSafeDiagnosticsAndIsPersisted() =
        runTest {
            selectOneImage()
            ai.failure =
                AiProviderException(
                    code = AiProviderFailureCode.REQUEST_FAILED,
                    httpStatusCode = 400,
                    providerErrorCode = "INVALID_ARGUMENT",
                    message = "Unknown JSON schema field.",
                )

            service.analyze("vision", "pt-BR", "BRL")

            val failure = checkNotNull(service.state.value.failure)
            assertEquals(ImageImportFailureCode.NETWORK, failure.code)
            assertFalse(failure.retryable)
            assertEquals(400, failure.httpStatusCode)
            assertEquals("INVALID_ARGUMENT", failure.providerErrorCode)
            assertEquals("Unknown JSON schema field.", failure.providerMessage)
            val persisted = checkNotNull(imports.get(checkNotNull(service.state.value.sessionId)))
            assertEquals("REQUEST_FAILED_HTTP_400_INVALID_ARGUMENT", persisted.errorType)
            assertEquals("Unknown JSON schema field.", persisted.errorMessage)
        }

    @Test
    fun exhaustedServerFailureIsShownAsTemporaryProviderUnavailability() =
        runTest {
            selectOneImage()
            ai.failure =
                AiProviderException(
                    code = AiProviderFailureCode.REQUEST_FAILED,
                    httpStatusCode = 503,
                    message = "Provider returned HTTP 503.",
                )

            service.analyze("vision", "pt-BR", "BRL")

            val failure = checkNotNull(service.state.value.failure)
            assertEquals(ImageImportFailureCode.PROVIDER_UNAVAILABLE, failure.code)
            assertTrue(failure.retryable)
            assertEquals(503, failure.httpStatusCode)
        }

    @Test
    fun cancellationPersistsTerminalStatusAndDoesNotCreateCandidates() =
        runTest {
            selectOneImage()
            ai.suspendForever = true
            val analysis = launch { service.analyze("vision", "pt-BR", "BRL") }
            runCurrent()
            assertEquals(ImageImportStage.ANALYZING, service.state.value.stage)

            service.cancelAnalysis()
            analysis.join()

            assertEquals(ImageImportStage.CANCELLED, service.state.value.stage)
            assertEquals(ImageImportFailureCode.CANCELLED, service.state.value.failure?.code)
            val sessionId = checkNotNull(service.state.value.sessionId)
            assertTrue(candidates.byImportSession(sessionId).isEmpty())

            ai.suspendForever = false
            ai.content = envelope(transaction())
            service.retryAnalysis()

            assertEquals(sessionId, service.state.value.sessionId)
            assertEquals(ImageImportStage.PREVIEW, service.state.value.stage)
            assertEquals(ImportSessionStatus.READY, imports.get(sessionId)?.status)
            assertEquals(1, candidates.byImportSession(sessionId).size)
        }

    @Test
    fun databaseReplacementQuiescesAndZeroesImagesWithoutWritingOldDatabase() =
        runTest {
            selectOneImage()
            ai.suspendForever = true
            val analysis = launch { service.analyze("vision", "pt-BR", "BRL") }
            runCurrent()
            val sessionId = checkNotNull(service.state.value.sessionId)

            service.suspendForDatabaseReplacement()
            analysis.join()

            assertEquals(ImageImportStage.IDLE, service.state.value.stage)
            assertEquals(ImportSessionStatus.ANALYZING, imports.get(sessionId)?.status)
            assertTrue(imageSource.cleaned.contains("image-1"))
            assertTrue(ai.receivedImages.single().bytes.all { it == 0.toByte() })
        }

    @Test
    fun duplicateRowsAndExistingExpensesAreMarkedForReview() =
        runTest {
            expenses.upsert(
                Expense(
                    id = "existing",
                    occurredAt = LocalDate(2026, 7, 14),
                    amountCents = 4_290,
                    categoryId = "food",
                    merchant = "  Mercado!!! ",
                    paymentMethod = PaymentMethod.DEBIT,
                    recurrenceType = RecurrenceType.ONE_TIME,
                    createdAt = FIXED_NOW,
                ),
            )
            selectOneImage()
            ai.content = envelope(transaction(), transaction())

            service.analyze("vision", "pt-BR", "BRL")

            val review = service.state.value.candidates
            assertEquals("existing", review.first().candidate.duplicateExpenseId)
            assertEquals(review.first().candidate.id, review.last().candidate.duplicateCandidateId)
            assertTrue(review.all { row -> row.issues.any { it.code == CandidateIssueCode.POSSIBLE_DUPLICATE } })

            service.updateCandidate(review.first().candidate.id, completeEdit("Outro mercado", 4_290))

            val edited = service.state.value.candidates
            assertNull(edited.last().candidate.duplicateCandidateId)
            assertEquals("existing", edited.last().candidate.duplicateExpenseId)
        }

    @Test
    fun partialRowsRemainEditableAndSupportAddDeleteSelectionAndBulkEdits() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(description = null, amount = null, date = null, category = null, payment = null))
            service.analyze("vision", "pt-BR", "BRL")
            val first = service.state.value.candidates.single().candidate
            assertTrue(service.state.value.candidates.single().issues.any { it.code == CandidateIssueCode.INVALID_AMOUNT })
            assertEquals("Compra", first.description)
            assertEquals(LocalDate(2026, 7, 14), first.occurredDate)
            assertNull(first.suggestedCategoryId)
            assertEquals("Other", first.suggestedCategoryLabel)
            assertTrue(first.warnings.contains("category-defaulted"))
            assertEquals(PaymentMethod.DEBIT.name, first.accountOrPaymentMethod)

            service.updateCandidate(first.id, completeEdit("Edited", 2_500))
            service.addCandidate(completeEdit("Added", 3_500))
            val added = service.state.value.candidates.last().candidate
            service.setCandidateSelected(first.id, false)
            service.applyCategory(setOf(added.id), "food")
            service.applyPaymentMethod(setOf(added.id), "PIX")

            assertFalse(service.state.value.candidates.first().candidate.selected)
            assertEquals("PIX", service.state.value.candidates.last().candidate.accountOrPaymentMethod)
            service.setAllCandidatesSelected(true)
            assertTrue(service.state.value.candidates.all { it.candidate.selected })
            service.deleteCandidate(added.id)
            assertEquals(1, service.state.value.candidates.size)
        }

    @Test
    fun analysisImportsOnlyExpensesAndConstrainsProviderToUserCategories() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction(description = "Padaria", type = "expense"),
                    transaction(description = "Salário", type = "income"),
                    transaction(description = "Estorno", type = "refund"),
                    transaction(description = "Pix recebido", type = "transfer"),
                )

            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(listOf("Padaria"), service.state.value.candidates.map { it.candidate.description })
            assertTrue(ai.receivedInstructions.contains("Food"))
            assertTrue(ai.receivedInstructions.contains("Omit income"))
            assertTrue(ai.receivedInstructions.contains("Credit-card purchases"))
        }

    @Test
    fun fallbackCategoryIsCreatedOnlyInsideConfirmedImportTransaction() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(description = "Loja Exemplo", category = null, supportingText = null))

            service.analyze("vision", "pt-BR", "BRL", fallbackCategoryName = "Outros")

            val candidate = service.state.value.candidates.single().candidate
            assertNull(candidate.suggestedCategoryId)
            assertEquals("Outros", candidate.suggestedCategoryLabel)
            assertNull(categories.list().firstOrNull { it.id == "category-image-import-other" })

            service.confirmImport()

            assertEquals("Outros", categories.list().single { it.id == "category-image-import-other" }.name)
            assertEquals(
                "category-image-import-other",
                expenses.byMonth(YearMonth(2026, 7)).single().categoryId,
            )
        }

    @Test
    fun rejectedApprovalDoesNotPersistFallbackCategoryOrCandidateMutation() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(description = "Loja Exemplo", category = null, supportingText = null))
            service.analyze("vision", "pt-BR", "BRL", fallbackCategoryName = "Outros")
            val candidate = service.state.value.candidates.single().candidate

            SqlCandidateApprovalRepository(holder).approveImportAtomically(
                importSessionId = "wrong-session",
                commands =
                    listOf(
                        ApproveExpenseCandidateCommand(
                            candidateId = candidate.id,
                            expenseId = "never-created",
                            originId = "never-created-origin",
                            createdAt = FIXED_NOW,
                            categoryToCreate =
                                Category("category-image-import-other", "Outros", CategoryType.VARIABLE),
                        ),
                    ),
                completedAt = FIXED_NOW,
            )

            assertNull(categories.list().firstOrNull { it.id == "category-image-import-other" })
            assertNull(candidates.get(candidate.id)?.suggestedCategoryId)
            assertTrue(expenses.byMonth(YearMonth(2026, 7)).isEmpty())
        }

    @Test
    fun invalidSecondRowRollsBackProspectiveCategoryForWholeBatch() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction(description = "Loja A", category = null, supportingText = null),
                    transaction(description = "Loja B", amount = null, category = null, supportingText = null),
                )
            service.analyze("vision", "pt-BR", "BRL", fallbackCategoryName = "Outros")
            val sessionId = checkNotNull(service.state.value.sessionId)
            val rows = service.state.value.candidates.map { it.candidate }
            val fallback = Category("category-image-import-other", "Outros", CategoryType.VARIABLE)

            SqlCandidateApprovalRepository(holder).approveImportAtomically(
                importSessionId = sessionId,
                commands =
                    rows.mapIndexed { index, candidate ->
                        ApproveExpenseCandidateCommand(
                            candidateId = candidate.id,
                            expenseId = "never-created-$index",
                            originId = "never-created-origin-$index",
                            createdAt = FIXED_NOW,
                            categoryToCreate = fallback,
                        )
                    },
                completedAt = FIXED_NOW,
            )

            assertNull(categories.list().firstOrNull { it.id == fallback.id })
            assertTrue(rows.all { candidates.get(it.id)?.suggestedCategoryId == null })
            assertTrue(expenses.byMonth(YearMonth(2026, 7)).isEmpty())
        }

    @Test
    fun bulkCategoryCorrectionClearsFallbackMetadata() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(description = "Loja Exemplo", category = null, supportingText = null))
            service.analyze("vision", "pt-BR", "BRL")

            val candidateId = service.state.value.candidates.single().candidate.id
            service.applyCategory(setOf(candidateId), "food")

            val candidate = service.state.value.candidates.single().candidate
            assertEquals("food", candidate.suggestedCategoryId)
            assertNull(candidate.suggestedCategoryLabel)
            assertFalse("category-defaulted" in candidate.warnings)
        }

    @Test
    fun explicitExpenseWithCreditCardEvidenceIsKeptAndUsesExtractedMerchantText() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction(
                        description = null,
                        type = "expense",
                        category = "Food",
                        supportingText = "SUPERMERCADO CENTRAL R$ 42,90 compra aprovada no cartão de crédito",
                    ),
                )

            service.analyze("vision", "pt-BR", "BRL")

            val candidate = service.state.value.candidates.single().candidate
            assertEquals("SUPERMERCADO CENTRAL compra aprovada no cartão de crédito", candidate.description)
            assertEquals(CandidateTransactionType.EXPENSE, candidate.transactionType)
            assertEquals("food", candidate.suggestedCategoryId)
        }

    @Test
    fun validRowWithoutTypeRemainsReviewableWhileExplicitNonExpensesAreExcluded() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction(description = "Tarifa bancária", type = null, supportingText = null),
                    transaction(description = "Salário", type = "income"),
                )

            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(listOf("Tarifa bancária"), service.state.value.candidates.map { it.candidate.description })
            assertEquals(CandidateTransactionType.EXPENSE, service.state.value.candidates.single().candidate.transactionType)
        }

    @Test
    fun obviousIncomeAndRefundEvidenceWithoutTypeIsExcluded() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction(description = "Tarifa bancária", type = null, supportingText = null),
                    transaction(description = "Salário", type = null, supportingText = null),
                    transaction(description = "salary", type = null, supportingText = null),
                    transaction(description = "renda mensal", type = null, supportingText = null),
                    transaction(description = "wage", type = null, supportingText = null),
                    transaction(description = "Estorno", type = null, supportingText = null),
                    transaction(description = "Pix recebido", type = null, supportingText = null),
                )

            service.analyze("vision", "pt-BR", "BRL")

            assertEquals(listOf("Tarifa bancária"), service.state.value.candidates.map { it.candidate.description })
        }

    @Test
    fun lowConfidenceExtractionIsNeverCommunicatedOnlyByColor() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(confidence = "0.4"))

            service.analyze("vision", "pt-BR", "BRL")

            assertTrue(
                service.state.value.candidates.single().issues.any { it.code == CandidateIssueCode.LOW_CONFIDENCE },
            )
            assertTrue(service.state.value.candidates.single().candidate.lowConfidenceFields.contains("confidence"))
        }

    @Test
    fun invalidSelectedRowPreventsAnyLedgerInsertionThenValidBatchCommitsAtomically() =
        runTest {
            selectOneImage()
            ai.content = envelope(transaction(), transaction(description = "Invalid", amount = null))
            service.analyze("vision", "pt-BR", "BRL")

            service.confirmImport()
            assertEquals(ImageImportFailureCode.VALIDATION, service.state.value.failure?.code)
            assertTrue(expenses.byMonth(YearMonth(2026, 7)).isEmpty())

            val invalid = service.state.value.candidates.last().candidate
            service.updateCandidate(invalid.id, completeEdit("Fixed", 5_000))
            service.confirmImport()
            assertEquals(2, expenses.byMonth(YearMonth(2026, 7)).size)
            assertEquals(2, service.state.value.summary?.importedCount)
        }

    @Test
    fun readyPreviewSurvivesServiceRecreationWithoutRetainingImageBytes() =
        runTest {
            selectOneImage()
            service.analyze("vision", "pt-BR", "BRL")
            val sessionId = service.state.value.sessionId

            val recreated = newService(FakeImageSourceService())
            recreated.initialize()

            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(ImageImportStage.PREVIEW, recreated.state.value.stage)
            assertEquals(1, recreated.state.value.candidates.size)
            assertFalse(recreated.state.value.images.single().isLocallyAvailable)
        }

    @Test
    fun unavailableRestoredImageCanStillBeRemoved() =
        runTest {
            selectOneImage()
            val recreated = newService(FakeImageSourceService())
            recreated.initialize()
            val unavailableImage = recreated.state.value.images.single()

            assertFalse(unavailableImage.isLocallyAvailable)
            recreated.removeImage(unavailableImage.id)

            assertEquals(ImageImportStage.IDLE, recreated.state.value.stage)
            assertTrue(recreated.state.value.images.isEmpty())
            assertNull(recreated.state.value.failure)
        }

    @Test
    fun interruptedAnalysisRestoresTemporarySourcesAndRetriesAfterProcessRecreation() =
        runTest {
            selectOneImage()
            ai.suspendForever = true
            val analysis = launch { service.analyze("vision", "pt-BR", "BRL") }
            runCurrent()
            val sessionId = checkNotNull(service.state.value.sessionId)
            service.cancelAnalysis()
            analysis.join()
            val cancelled = checkNotNull(imports.get(sessionId))
            imports.updateStatus(
                id = sessionId,
                status = ImportSessionStatus.ANALYZING,
                selectedCount = cancelled.selectedCount,
                importedCount = cancelled.importedCount,
                errorType = null,
                safeErrorMessage = null,
                updatedAt = FIXED_NOW,
                completedAt = null,
            )

            val recreatedSource = FakeImageSourceService().also { it.nextSelection = imageSource.nextSelection }
            val recreated = newService(recreatedSource)
            recreated.initialize()

            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(ImageImportStage.SOURCES_SELECTED, recreated.state.value.stage)
            assertEquals(ImageImportFailureCode.INTERRUPTED, recreated.state.value.failure?.code)
            assertTrue(recreated.state.value.canAnalyze)

            ai.suspendForever = false
            ai.content = envelope(transaction())
            recreated.retryAnalysis()

            assertEquals(ImageImportStage.PREVIEW, recreated.state.value.stage)
            assertEquals(sessionId, recreated.state.value.sessionId)
            assertEquals(1, recreated.state.value.candidates.size)
        }

    @Test
    fun invalidInstallmentValuesStayInEditablePreviewInsteadOfFailingPersistence() =
        runTest {
            selectOneImage()
            ai.content =
                envelope(
                    transaction()
                        .replace("\"installmentIndex\":null", "\"installmentIndex\":0")
                        .replace("\"installmentTotal\":null", "\"installmentTotal\":-1"),
                )

            service.analyze("vision", "pt-BR", "BRL")

            val row = service.state.value.candidates.single()
            assertEquals(ImageImportStage.PREVIEW, service.state.value.stage)
            assertTrue(row.issues.any { it.code == CandidateIssueCode.INVALID_INSTALLMENT })
            service.updateCandidate(row.candidate.id, completeEdit("Mercado", 4_290))
            assertFalse(
                service.state.value.candidates.single().issues.any {
                    it.code == CandidateIssueCode.INVALID_INSTALLMENT
                },
            )
        }

    @Test
    fun cancellingImportDeletesUnconfirmedDraftAndExtractedFinancialText() =
        runTest {
            selectOneImage()
            service.analyze("vision", "pt-BR", "BRL")
            val sessionId = checkNotNull(service.state.value.sessionId)

            service.cancelImport()

            assertNull(imports.get(sessionId))
            assertTrue(candidates.byImportSession(sessionId).isEmpty())
            assertEquals(ImageImportStage.CANCELLED, service.state.value.stage)
        }

    @Test
    fun completedImportIsIdempotentWhenConfirmationIsRepeated() =
        runTest {
            selectOneImage()
            service.analyze("vision", "pt-BR", "BRL")
            service.confirmImport()
            service.confirmImport()

            assertEquals(1, expenses.byMonth(YearMonth(2026, 7)).size)
        }

    private suspend fun selectOneImage() {
        imageSource.nextSelection = listOf(image("image-1", "hash-1"))
        service.selectImages()
    }

    private fun newService(
        source: FakeImageSourceService = imageSource,
        clock: () -> Instant = { FIXED_NOW },
    ): DefaultImageImportService =
        DefaultImageImportService(
            imageSourceService = source,
            providerProfileRepository = profiles,
            secretStore = secrets,
            aiProviderClient = ai,
            importRepository = imports,
            candidateRepository = candidates,
            approvalRepository = SqlCandidateApprovalRepository(holder),
            categoryRepository = SqlCategoryRepository(holder),
            now = clock,
            idFactory = { prefix -> "$prefix-${++nextId}" },
        )

    private fun completeEdit(
        description: String,
        amountCents: Long,
    ) = TransactionCandidateEdit(
        description = description,
        amountCents = amountCents,
        currency = "BRL",
        occurredDate = LocalDate(2026, 7, 14),
        occurredTime = null,
        timeZoneId = null,
        transactionType = CandidateTransactionType.EXPENSE,
        suggestedCategoryId = "food",
        accountOrPaymentMethod = "DEBIT",
        installmentIndex = null,
        installmentTotal = null,
        note = null,
    )

    private fun profile(
        id: String,
        vision: Boolean,
    ) = ProviderProfile(
        id = id,
        providerId = "test-provider",
        displayName = id,
        baseUrl = "https://example.test/v1",
        model = "$id-model",
        apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS.name,
        capabilities =
            ProviderCapabilities(
                textGeneration = true,
                visionInput = vision,
                structuredOutput = vision,
            ),
        active = true,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
    )

    private object NoReloadDriverFactory : SqlDriverFactory {
        override fun createDriver(): SqlDriver = error("Not used")
    }
}

private class FakeImageSourceService : ImageSourceService {
    override val capabilities = ImageSourceCapabilities(true, true, maximumSelection = 10)
    var nextSelection: List<TemporaryImageHandle> = emptyList()
    var nextResult: ImageSourceResult? = null
    var recoveredImages: List<TemporaryImageHandle> = emptyList()
    var failAcknowledgement = false
    var blockPendingRecovery = false
    var pendingRecoveryCalls = 0
    var maximumConcurrentPendingRecoveryCalls = 0
    val pendingRecoveryEntered = CompletableDeferred<Unit>()
    val releasePendingRecovery = CompletableDeferred<Unit>()
    private var activePendingRecoveryCalls = 0
    val cleaned = mutableListOf<String>()
    val acknowledged = mutableListOf<String>()

    override suspend fun selectImages(): ImageSourceResult = nextResult ?: ImageSourceResult.Selected(nextSelection)

    override suspend fun captureImage(): ImageSourceResult = ImageSourceResult.Selected(nextSelection)

    override suspend fun readBytes(handle: TemporaryImageHandle): ByteArray = "bytes:${handle.id}".encodeToByteArray()

    override suspend fun restoreAvailable(handles: Collection<TemporaryImageHandle>): List<TemporaryImageHandle> =
        handles.filter { handle -> nextSelection.any { it.temporaryReference == handle.temporaryReference } }

    override suspend fun pendingRecoveredImages(): List<TemporaryImageHandle> {
        pendingRecoveryCalls++
        activePendingRecoveryCalls++
        maximumConcurrentPendingRecoveryCalls = maxOf(maximumConcurrentPendingRecoveryCalls, activePendingRecoveryCalls)
        return try {
            if (blockPendingRecovery) {
                pendingRecoveryEntered.complete(Unit)
                releasePendingRecovery.await()
            }
            recoveredImages
        } finally {
            activePendingRecoveryCalls--
        }
    }

    override suspend fun acknowledgeRecoveredImages(handles: Collection<TemporaryImageHandle>) {
        if (failAcknowledgement) error("Simulated acknowledgement failure")
        acknowledged += handles.map { it.id }
        val acknowledgedReferences = handles.mapTo(mutableSetOf(), TemporaryImageHandle::temporaryReference)
        recoveredImages = recoveredImages.filterNot { it.temporaryReference in acknowledgedReferences }
    }

    override suspend fun cleanup(handles: Collection<TemporaryImageHandle>) {
        cleaned += handles.map { it.id }
    }

    override suspend fun cleanupExpired() = Unit
}

private class FakeSecretStore(
    val keys: MutableMap<String, String>,
) : AdvisorSecretStore {
    override suspend fun readApiKey(): String? = keys["default"]

    override suspend fun writeApiKey(apiKey: String) {
        keys["default"] = apiKey
    }

    override suspend fun clearApiKey() {
        keys.remove("default")
    }

    override suspend fun readApiKey(profileId: String): String? = keys[profileId]

    override suspend fun writeApiKey(
        profileId: String,
        apiKey: String,
    ) {
        keys[profileId] = apiKey
    }

    override suspend fun clearApiKey(profileId: String) {
        keys.remove(profileId)
    }
}

private class FakeAiProviderClient : AiProviderClient {
    var calls = 0
    var content = envelope(transaction())
    var failure: AiProviderException? = null
    var suspendForever = false
    var receivedImages: List<AiImageInput> = emptyList()
    var receivedInstructions: String = ""

    override suspend fun generateText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): AiProviderResponse = error("Not used")

    override fun streamText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): Flow<AiStreamEvent> = emptyFlow()

    override suspend fun generateStructured(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse {
        calls++
        receivedImages = images
        receivedInstructions = instructions
        if (suspendForever) awaitCancellation()
        failure?.also {
            failure = null
            throw it
        }
        return AiProviderResponse(content)
    }
}

private fun image(
    id: String,
    hash: String,
) = TemporaryImageHandle(
    id = id,
    displayName = "$id.png",
    mimeType = "image/png",
    byteSize = 100,
    sha256 = hash,
    temporaryReference = "/tmp/$id",
)

private val FIXED_NOW = Instant.parse("2026-07-14T12:00:00Z")
