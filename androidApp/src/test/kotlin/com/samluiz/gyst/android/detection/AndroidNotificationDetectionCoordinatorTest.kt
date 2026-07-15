package com.samluiz.gyst.android.detection

import com.samluiz.gyst.domain.model.CandidateSource
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.MonitoredApplication
import com.samluiz.gyst.domain.model.MonitoredApplicationPolicy
import com.samluiz.gyst.domain.model.NotificationIngestion
import com.samluiz.gyst.domain.model.NotificationProcessingStatus
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.repository.ApproveExpenseCandidateCommand
import com.samluiz.gyst.domain.repository.CandidateApprovalFailure
import com.samluiz.gyst.domain.repository.CandidateApprovalRepository
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import com.samluiz.gyst.domain.repository.CategoryRepository
import com.samluiz.gyst.domain.repository.MonitoredApplicationRepository
import com.samluiz.gyst.domain.repository.NotificationIngestionRepository
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.repository.TransactionCandidateRepository
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiProviderResponse
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import com.samluiz.gyst.domain.service.AutomaticDetectionSettingKeys
import com.samluiz.gyst.domain.service.DetectionPermissionState
import com.samluiz.gyst.domain.service.DetectionServiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNotificationDetectionCoordinatorTest {
    @Test
    fun `rule-only ingestion is durable idempotent and notifies once`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            assertTrue(fixture.coordinator.shouldCollect(BANK_PACKAGE))

            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()

            assertEquals(1, fixture.ingestions.values.size)
            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.notifier.sent.size)
            assertEquals(CandidateStatus.NEEDS_REVIEW, fixture.candidates.values.values.single().status)
            assertTrue(fixture.ingestions.values.values.single().normalizedText == null)
        }

    @Test
    fun `rule-only processing interrupted by process death is replayed from protected durable text`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.ingestions.values["interrupted-local"] = interruptedIngestion("interrupted-local")

            fixture.coordinator.initialize()

            val ingestion = fixture.ingestions.values.getValue("interrupted-local")
            assertEquals(NotificationProcessingStatus.COMPLETED, ingestion.processingStatus)
            assertNull(ingestion.normalizedText)
            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.notifier.sent.size)
            assertTrue(fixture.scheduler.scheduled.isEmpty())
        }

    @Test
    fun `feature disabled rejects collection before notification content is processed`() =
        runTest {
            val fixture =
                fixture(
                    CoroutineScope(StandardTestDispatcher(testScheduler)),
                    FixtureOptions(enabled = false),
                )
            fixture.coordinator.initialize()

            assertFalse(fixture.coordinator.shouldCollect(BANK_PACKAGE))
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()

            assertTrue(fixture.ingestions.values.isEmpty())
            assertTrue(fixture.candidates.values.isEmpty())
        }

    @Test
    fun `AI analysis uses selected configured profile and redacts transient content`() =
        runTest {
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            val outcome = fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)

            assertEquals(NotificationAnalysisOutcome.Completed, outcome)
            assertEquals(1, fixture.provider.requests)
            assertEquals("test-provider", fixture.candidates.values.values.single().providerId)
            assertEquals(
                NotificationProcessingStatus.COMPLETED,
                fixture.ingestions.values.values.single().processingStatus,
            )
            assertTrue(fixture.ingestions.values.values.single().normalizedText == null)
            assertEquals(1, fixture.notifier.sent.size)
        }

    @Test
    fun `listener access and application allowlist both gate collection`() =
        runTest {
            val noAccess =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options =
                        FixtureOptions(
                            permission = FakePermissionController(listenerAccessGranted = false),
                        ),
                )
            noAccess.coordinator.initialize()

            assertFalse(noAccess.coordinator.shouldCollect(BANK_PACKAGE))
            assertFalse(noAccess.coordinator.state.value.notificationListenerAccessGranted)

            val blocked =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(sourcePolicy = MonitoredApplicationPolicy.BLOCK),
                )
            blocked.coordinator.initialize()

            assertFalse(blocked.coordinator.shouldCollect(BANK_PACKAGE))
            blocked.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            assertTrue(blocked.ingestions.values.isEmpty())
        }

    @Test
    fun `listener lifecycle and access revocation update durable processing state`() =
        runTest {
            val permission = FakePermissionController()
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, permission = permission),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onListenerConnected()
            advanceUntilIdle()
            assertTrue(fixture.coordinator.state.value.listenerConnected)
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            permission.listenerAccessGranted = false
            fixture.coordinator.refresh()

            assertFalse(fixture.coordinator.shouldCollect(BANK_PACKAGE))
            assertFalse(fixture.coordinator.state.value.notificationListenerAccessGranted)
            assertTrue(fixture.scheduler.scheduled.isEmpty())
            assertEquals(
                NotificationProcessingStatus.CANCELLED,
                fixture.ingestions.values.getValue(ingestionId).processingStatus,
            )
            assertNull(fixture.ingestions.values.getValue(ingestionId).normalizedText)

            fixture.coordinator.onListenerDisconnected()
            advanceUntilIdle()
            assertFalse(fixture.coordinator.state.value.listenerConnected)
        }

    @Test
    fun `listener disconnection requests a system rebind while access remains granted`() =
        runTest {
            val permission = FakePermissionController()
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(permission = permission),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onListenerConnected()
            advanceUntilIdle()
            val requestsBeforeDisconnect = permission.listenerRebindRequests

            fixture.coordinator.onListenerDisconnected()
            advanceUntilIdle()

            assertEquals(requestsBeforeDisconnect + 1, permission.listenerRebindRequests)
            assertFalse(fixture.coordinator.state.value.listenerConnected)
        }

    @Test
    fun `irrelevant promotional personal and authentication notifications are ignored locally`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()

            listOf(
                financialEnvelope(text = "Seu extrato mensal está disponível"),
                financialEnvelope(text = "Oferta: compre agora com R$ 42,90 de desconto"),
                financialEnvelope(text = "Nova mensagem de Ana sobre R$ 42,90", category = "msg"),
                financialEnvelope(text = "Código de verificação 123456 para pagamento de R$ 42,90"),
            ).forEach(fixture.coordinator::onPosted)
            advanceUntilIdle()

            assertTrue(fixture.ingestions.values.isEmpty())
            assertTrue(fixture.candidates.values.isEmpty())
            assertEquals(0, fixture.provider.requests)
        }

    @Test
    fun `meaningful notification update is ingested but converges on the same suggestion`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()

            fixture.coordinator.onPosted(financialEnvelope(text = "Compra de R$ 42,90 aprovada na Padaria Exemplo."))
            advanceUntilIdle()
            fixture.coordinator.onPosted(
                financialEnvelope(
                    text = "Compra de R$ 42,90 aprovada na Padaria Exemplo.",
                    expandedText = "Detalhes atualizados",
                ),
            )
            advanceUntilIdle()

            assertEquals(2, fixture.ingestions.values.size)
            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.notifier.sent.size)
            assertEquals(
                1,
                fixture.ingestions.values.values.mapNotNull(NotificationIngestion::candidateId).distinct().size,
            )
        }

    @Test
    fun `same transaction from separate notification keys is not suggested twice`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()

            fixture.coordinator.onPosted(financialEnvelope(notificationKey = "purchase-first"))
            advanceUntilIdle()
            fixture.coordinator.onPosted(financialEnvelope(notificationKey = "purchase-reposted"))
            advanceUntilIdle()

            assertEquals(2, fixture.ingestions.values.size)
            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.notifier.sent.size)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNotificationAnalysisCoordinatorTest {
    @Test
    fun `retryable provider failure keeps protected content and succeeds without duplicate`() =
        runTest {
            val provider = FakeProviderClient().apply { enqueueFailure(AiProviderFailureCode.NETWORK) }
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, provider = provider),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            val failed = fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)

            assertEquals(NotificationAnalysisOutcome.RetryableFailure, failed)
            assertEquals(
                NotificationProcessingStatus.FAILED,
                fixture.ingestions.values.getValue(ingestionId).processingStatus,
            )
            assertNotNull(fixture.ingestions.values.getValue(ingestionId).normalizedText)

            val completed = fixture.coordinator.analyze(ingestionId, runAttemptCount = 1)

            assertEquals(NotificationAnalysisOutcome.Completed, completed)
            assertEquals(2, provider.requests)
            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.notifier.sent.size)
            assertNull(fixture.ingestions.values.getValue(ingestionId).normalizedText)
        }

    @Test
    fun `authentication failure is permanent and redacts provider content`() =
        runTest {
            val provider = FakeProviderClient().apply { enqueueFailure(AiProviderFailureCode.AUTHENTICATION) }
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, provider = provider),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            val outcome = fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)

            assertEquals(NotificationAnalysisOutcome.PermanentFailure, outcome)
            val ingestion = fixture.ingestions.values.getValue(ingestionId)
            assertEquals(NotificationProcessingStatus.FAILED, ingestion.processingStatus)
            assertEquals(AiProviderFailureCode.AUTHENTICATION.name, ingestion.errorType)
            assertNull(ingestion.normalizedText)
            assertTrue(fixture.candidates.values.isEmpty())
        }

    @Test
    fun `provider rate limit remains retryable within the bounded attempt budget`() =
        runTest {
            val provider = FakeProviderClient().apply { enqueueFailure(AiProviderFailureCode.RATE_LIMITED) }
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, provider = provider),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            val outcome = fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)

            assertEquals(NotificationAnalysisOutcome.RetryableFailure, outcome)
            val ingestion = fixture.ingestions.values.getValue(ingestionId)
            assertEquals(AiProviderFailureCode.RATE_LIMITED.name, ingestion.errorType)
            assertEquals(1L, ingestion.retryCount)
            assertNotNull(ingestion.normalizedText)
        }

    @Test
    fun `malformed structured response fails safely and never creates a suggestion`() =
        runTest {
            val provider = FakeProviderClient().apply { enqueueContent("not-json") }
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, provider = provider),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            val outcome = fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)

            assertEquals(NotificationAnalysisOutcome.PermanentFailure, outcome)
            assertEquals("INVALID_RESPONSE", fixture.ingestions.values.getValue(ingestionId).errorType)
            assertNull(fixture.ingestions.values.getValue(ingestionId).normalizedText)
            assertTrue(fixture.candidates.values.isEmpty())
        }

    @Test
    fun `disabling AI cancels queued analysis and removes transient content`() =
        runTest {
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()

            fixture.coordinator.setAiAnalysisEnabled(false)

            val ingestion = fixture.ingestions.values.getValue(ingestionId)
            assertEquals(NotificationProcessingStatus.CANCELLED, ingestion.processingStatus)
            assertNull(ingestion.normalizedText)
            assertTrue(fixture.scheduler.scheduled.isEmpty())
        }

    @Test
    fun `failed durable analysis is explicitly rescheduled for retry`() =
        runTest {
            val provider = FakeProviderClient().apply { enqueueFailure(AiProviderFailureCode.NETWORK) }
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true, provider = provider),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()
            fixture.coordinator.analyze(ingestionId, runAttemptCount = 0)
            fixture.scheduler.scheduled.clear()

            fixture.coordinator.retryFailedAnalyses()

            assertEquals(listOf(ingestionId), fixture.scheduler.scheduled)
            assertEquals(
                NotificationProcessingStatus.QUEUED,
                fixture.ingestions.values.getValue(ingestionId).processingStatus,
            )
        }

    @Test
    fun `initialization replays durable queued analysis after runtime recreation`() =
        runTest {
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val ingestionId = fixture.scheduler.scheduled.single()
            fixture.scheduler.scheduled.clear()

            fixture.coordinator.initialize()

            assertEquals(listOf(ingestionId), fixture.scheduler.scheduled)
            assertEquals(
                NotificationProcessingStatus.QUEUED,
                fixture.ingestions.values.getValue(ingestionId).processingStatus,
            )
        }

    @Test
    fun `AI processing interrupted by process death returns to unique durable work`() =
        runTest {
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true),
                )
            fixture.ingestions.values["interrupted-ai"] = interruptedIngestion("interrupted-ai")

            fixture.coordinator.initialize()

            assertEquals(listOf("interrupted-ai"), fixture.scheduler.scheduled)
            assertEquals(
                NotificationProcessingStatus.QUEUED,
                fixture.ingestions.values.getValue("interrupted-ai").processingStatus,
            )

            assertEquals(
                NotificationAnalysisOutcome.Completed,
                fixture.coordinator.analyze("interrupted-ai", runAttemptCount = 0),
            )
            assertEquals(1, fixture.provider.requests)
            assertEquals(1, fixture.candidates.values.size)
            assertNull(fixture.ingestions.values.getValue("interrupted-ai").normalizedText)
        }

    @Test
    fun `incompatible or uncredentialed provider cannot enable AI analysis`() =
        runTest {
            val incompatible =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options =
                        FixtureOptions(
                            providerCapabilities = ProviderCapabilities(textGeneration = true),
                        ),
                )
            incompatible.coordinator.initialize()
            incompatible.coordinator.setAiAnalysisEnabled(true)
            assertEquals(
                DetectionServiceError.UNSUPPORTED_PROVIDER_CAPABILITY,
                incompatible.coordinator.state.value.lastError,
            )
            assertFalse(incompatible.coordinator.state.value.aiAnalysisEnabled)

            val missingCredential =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(apiKey = ""),
                )
            missingCredential.coordinator.initialize()
            missingCredential.coordinator.setAiAnalysisEnabled(true)
            assertEquals(
                DetectionServiceError.MISSING_PROVIDER_CREDENTIAL,
                missingCredential.coordinator.state.value.lastError,
            )
            assertFalse(missingCredential.coordinator.state.value.aiAnalysisEnabled)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNotificationDeliveryCoordinatorTest {
    @Test
    fun `notification permission denial does not lose suggestion and later delivery is idempotent`() =
        runTest {
            val notifier = FakeNotifier(delivery = DetectionNotificationDelivery.PERMISSION_DENIED)
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(notifier = notifier),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()

            assertEquals(1, fixture.candidates.values.size)
            assertEquals(1, fixture.coordinator.state.value.pendingSuggestions.size)
            assertEquals(1, notifier.sent.size)

            notifier.delivery = DetectionNotificationDelivery.SENT
            fixture.coordinator.setUserNotificationsEnabled(true)
            fixture.coordinator.setUserNotificationsEnabled(true)

            assertEquals(2, notifier.sent.size)
        }

    @Test
    fun `first contextual Android notification permission launches before recording the request`() =
        runTest {
            val permission =
                FakePermissionController(
                    applicationPermission = ApplicationNotificationPermissionState.DENIED_CAN_REQUEST,
                    applicationNotificationsEnabled = false,
                )
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(permission = permission),
                )
            fixture.coordinator.initialize()
            assertFalse(fixture.coordinator.requestApplicationNotificationPermission())
            var requestedPermission: String? = null
            var requestedMarkerAtLaunch: String? = "not-launched"
            fixture.coordinator.bindApplicationNotificationPermissionRequester(
                requester = {
                    requestedPermission = it
                    requestedMarkerAtLaunch =
                        fixture.settings.value(AutomaticDetectionSettingKeys.POST_NOTIFICATIONS_REQUESTED)
                },
                shouldShowRationale = { false },
            )
            advanceUntilIdle()

            assertTrue(fixture.coordinator.requestApplicationNotificationPermission())
            assertNull(requestedMarkerAtLaunch)
            advanceUntilIdle()

            assertEquals(AndroidDetectionPermissionGateway.POST_NOTIFICATIONS_PERMISSION, requestedPermission)
            assertEquals("true", fixture.settings.value(AutomaticDetectionSettingKeys.POST_NOTIFICATIONS_REQUESTED))
            assertEquals(
                DetectionPermissionState.DENIED_CAN_REQUEST,
                fixture.coordinator.state.value.applicationNotificationPermission,
            )
        }

    @Test
    fun `editing an already delivered suggestion updates the same Android notification`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val candidate = fixture.candidates.values.values.single()

            fixture.coordinator.updateSuggestion(candidate.copy(description = "Padaria Editada"))

            assertEquals(2, fixture.notifier.sent.size)
            assertEquals(candidate.id, fixture.notifier.sent.first().suggestionId)
            assertEquals(candidate.id, fixture.notifier.sent.last().suggestionId)
            assertEquals("Padaria Editada", fixture.notifier.sent.last().merchantOrDescription)
        }

    @Test
    fun `initialization redelivers an interrupted review notification exactly once`() =
        runTest {
            val notifier = FakeNotifier(DetectionNotificationDelivery.DELIVERY_FAILED)
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(notifier = notifier),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            assertEquals(1, notifier.sent.size)

            notifier.delivery = DetectionNotificationDelivery.SENT
            fixture.coordinator.initialize()
            fixture.coordinator.initialize()

            assertEquals(2, notifier.sent.size)
        }

    @Test
    fun `notification preference removes visible reviews and redelivers when enabled again`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val candidateId = fixture.candidates.values.keys.single()

            fixture.coordinator.setUserNotificationsEnabled(false)
            fixture.coordinator.setUserNotificationsEnabled(true)

            assertEquals(listOf(candidateId), fixture.notifier.cancelled)
            assertEquals(2, fixture.notifier.sent.size)
        }

    @Test
    fun `approval is idempotent and closes the detection notification`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val candidateId = fixture.candidates.values.keys.single()

            val first = fixture.coordinator.approveSuggestion(candidateId)
            val second = fixture.coordinator.approveSuggestion(candidateId)

            assertTrue(first is CandidateApprovalResult.Approved)
            assertTrue(second is CandidateApprovalResult.AlreadyApproved)
            assertEquals(1, fixture.approval.insertedExpenseCount)
            assertEquals(CandidateStatus.APPROVED, fixture.candidates.values.getValue(candidateId).status)
            assertEquals(listOf(candidateId, candidateId), fixture.notifier.cancelled)
        }

    @Test
    fun `rejection is atomic and repeated action does not change state twice`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val candidateId = fixture.candidates.values.keys.single()

            assertTrue(fixture.coordinator.rejectSuggestion(candidateId))
            assertFalse(fixture.coordinator.rejectSuggestion(candidateId))

            assertEquals(CandidateStatus.REJECTED, fixture.candidates.values.getValue(candidateId).status)
            assertEquals(listOf(candidateId), fixture.notifier.cancelled)
        }

    @Test
    fun `suggestion lookup handles exact missing and non-notification records`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val detected = fixture.candidates.values.values.single()
            fixture.candidates.values["image-row"] = detected.copy(id = "image-row", source = CandidateSource.IMAGE)

            assertEquals(detected.id, fixture.coordinator.suggestion(detected.id)?.id)
            assertNull(fixture.coordinator.suggestion("missing-suggestion"))
            assertNull(fixture.coordinator.suggestion("image-row"))
        }

    @Test
    fun `database replacement suspension cancels work and rejects new callbacks until initialize`() =
        runTest {
            val fixture =
                fixture(
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                    options = FixtureOptions(aiEnabled = true),
                )
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            assertEquals(1, fixture.ingestions.values.size)

            fixture.coordinator.suspendProcessingForDatabaseReplacement()
            fixture.coordinator.onPosted(financialEnvelope(notificationKey = "after-suspension"))
            advanceUntilIdle()

            assertEquals(1, fixture.ingestions.values.size)
            assertTrue(fixture.scheduler.scheduled.isEmpty())

            fixture.coordinator.initialize()
            assertTrue(fixture.coordinator.shouldCollect(BANK_PACKAGE))
        }

    @Test
    fun `deleting notification-derived data keeps candidates from other sources`() =
        runTest {
            val fixture = fixture(CoroutineScope(StandardTestDispatcher(testScheduler)))
            fixture.coordinator.initialize()
            fixture.coordinator.onPosted(financialEnvelope())
            advanceUntilIdle()
            val detected = fixture.candidates.values.values.single()
            fixture.candidates.values["image-row"] = detected.copy(id = "image-row", source = CandidateSource.IMAGE)

            fixture.coordinator.deleteNotificationDerivedData()

            assertTrue(fixture.ingestions.values.isEmpty())
            assertEquals(setOf("image-row"), fixture.candidates.values.keys)
            assertEquals(listOf(detected.id), fixture.notifier.cancelled)
            assertTrue(fixture.coordinator.state.value.pendingSuggestions.isEmpty())
        }
}

private fun fixture(
    scope: CoroutineScope,
    options: FixtureOptions = FixtureOptions(),
): Fixture {
    val settings =
        FakeSettings(
            mutableMapOf(
                AutomaticDetectionSettingKeys.ENABLED to options.enabled.toString(),
                AutomaticDetectionSettingKeys.AI_ANALYSIS_ENABLED to options.aiEnabled.toString(),
                AutomaticDetectionSettingKeys.USER_NOTIFICATIONS_ENABLED to true.toString(),
                AutomaticDetectionSettingKeys.PROVIDER_PROFILE_ID to PROFILE_ID,
            ),
        )
    val monitored =
        FakeMonitoredApplications(
            mutableMapOf(
                BANK_PACKAGE to monitoredApplication(options.sourcePolicy),
            ),
        )
    val candidates = FakeCandidates()
    val ingestions = FakeIngestions(candidates)
    val scheduler = FakeScheduler()
    val approval = FakeApprovalRepository(candidates)
    val coordinator =
        AndroidNotificationDetectionCoordinator(
            settingsRepository = settings,
            monitoredApplicationRepository = monitored,
            ingestionRepository = ingestions,
            candidateRepository = candidates,
            candidateApprovalRepository = approval,
            categoryRepository = EmptyCategoryRepository,
            providerProfileRepository = FakeProfiles(providerProfile(options.providerCapabilities)),
            secretStore = FakeSecretStore(options.apiKey),
            providerClient = options.provider,
            permissionGateway = options.permission,
            applicationCatalog = InstalledApplicationSource { emptyList() },
            scheduler = scheduler,
            notifier = options.notifier,
            contentProtector = PrefixContentProtector,
            scope = scope,
        )
    return Fixture(
        coordinator,
        settings,
        ingestions,
        candidates,
        scheduler,
        options.notifier,
        options.provider,
        approval,
    )
}

private fun monitoredApplication(policy: MonitoredApplicationPolicy): MonitoredApplication {
    val now = Clock.System.now()
    return MonitoredApplication(
        packageName = BANK_PACKAGE,
        displayName = "Example Bank",
        policy = policy,
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )
}

private fun providerProfile(capabilities: ProviderCapabilities): ProviderProfile {
    val now = Clock.System.now()
    return ProviderProfile(
        id = PROFILE_ID,
        providerId = "test-provider",
        displayName = "Test provider",
        baseUrl = "https://example.invalid/v1",
        model = "vision-model",
        apiFormat = "CHAT_COMPLETIONS",
        capabilities = capabilities,
        active = true,
        createdAt = now,
        updatedAt = now,
    )
}

private fun financialEnvelope(
    notificationKey: String = "notification-key",
    text: String = "Compra de R$ 42,90 aprovada na Padaria Exemplo",
    expandedText: String? = null,
    category: String = "status",
) = AndroidNotificationEnvelope(
    identity = AndroidNotificationIdentity(BANK_PACKAGE, notificationKey, 42, null),
    postedAtEpochMillis = Instant.parse("2026-07-14T15:30:00Z").toEpochMilliseconds(),
    title = "Example Bank",
    text = text,
    expandedText = expandedText,
    category = category,
    channelId = "purchases",
    isOngoing = false,
)

private fun interruptedIngestion(id: String): NotificationIngestion {
    val postedAt = Instant.parse("2026-07-14T15:30:00Z")
    return NotificationIngestion(
        id = id,
        sourcePackage = BANK_PACKAGE,
        notificationId = 42,
        notificationKey = "$id-key",
        notificationFingerprint = "$id-fingerprint",
        postedAt = postedAt,
        title = null,
        mainText = null,
        expandedText = null,
        channelId = "purchases",
        category = "status",
        normalizedText =
            PrefixContentProtector.protect(
                "Compra de R$ 42,90 aprovada na Padaria Exemplo",
            ),
        processingStatus = NotificationProcessingStatus.PROCESSING,
        candidateId = null,
        retryCount = 0,
        errorType = null,
        errorMessage = null,
        contentRedactedAt = null,
        createdAt = postedAt,
        updatedAt = postedAt,
    )
}

private data class Fixture(
    val coordinator: AndroidNotificationDetectionCoordinator,
    val settings: FakeSettings,
    val ingestions: FakeIngestions,
    val candidates: FakeCandidates,
    val scheduler: FakeScheduler,
    val notifier: FakeNotifier,
    val provider: FakeProviderClient,
    val approval: FakeApprovalRepository,
)

private data class FixtureOptions(
    val enabled: Boolean = true,
    val aiEnabled: Boolean = false,
    val sourcePolicy: MonitoredApplicationPolicy = MonitoredApplicationPolicy.ALLOW,
    val permission: FakePermissionController = FakePermissionController(),
    val providerCapabilities: ProviderCapabilities =
        ProviderCapabilities(
            textGeneration = true,
            structuredOutput = true,
        ),
    val apiKey: String = "test-key",
    val provider: FakeProviderClient = FakeProviderClient(),
    val notifier: FakeNotifier = FakeNotifier(),
)

private const val BANK_PACKAGE = "com.example.bank"
private const val PROFILE_ID = "profile-test"

private class FakeSettings(
    private val values: MutableMap<String, String>,
) : SettingsRepository {
    fun value(key: String): String? = values[key]

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

private class FakeMonitoredApplications(
    private val values: MutableMap<String, MonitoredApplication>,
) : MonitoredApplicationRepository {
    override suspend fun upsert(application: MonitoredApplication) {
        values[application.packageName] = application
    }

    override suspend fun get(packageName: String): MonitoredApplication? = values[packageName]

    override suspend fun list(): List<MonitoredApplication> = values.values.toList()

    override suspend fun delete(packageName: String) {
        values.remove(packageName)
    }
}

private class FakeIngestions(
    private val candidates: FakeCandidates,
) : NotificationIngestionRepository {
    val values = linkedMapOf<String, NotificationIngestion>()

    override suspend fun insertIdempotently(ingestion: NotificationIngestion): NotificationIngestion =
        values.values.firstOrNull { it.notificationFingerprint == ingestion.notificationFingerprint }
            ?: ingestion.also { values[it.id] = it }

    override suspend fun get(id: String): NotificationIngestion? = values[id]

    override suspend fun getByFingerprint(fingerprint: String): NotificationIngestion? =
        values.values.firstOrNull { it.notificationFingerprint == fingerprint }

    override suspend fun queuedForProcessing(): List<NotificationIngestion> =
        values.values.filter {
            it.processingStatus in
                setOf(
                    NotificationProcessingStatus.RECEIVED,
                    NotificationProcessingStatus.QUEUED,
                    NotificationProcessingStatus.FAILED,
                )
        }

    override suspend fun recoverInterruptedProcessing(updatedAt: Instant) {
        values.entries.forEach { (id, ingestion) ->
            if (
                ingestion.processingStatus == NotificationProcessingStatus.PROCESSING &&
                ingestion.candidateId == null
            ) {
                values[id] =
                    ingestion.copy(
                        processingStatus = NotificationProcessingStatus.QUEUED,
                        errorType = "PROCESS_INTERRUPTED",
                        errorMessage = "Processing was interrupted and will be retried",
                        updatedAt = updatedAt,
                    )
            }
        }
    }

    override suspend fun storeSuggestionAndRedact(
        ingestionId: String,
        candidate: TransactionCandidate,
        updatedAt: Instant,
    ): TransactionCandidate {
        val stored = candidates.insert(candidate)
        values[ingestionId] =
            checkNotNull(values[ingestionId]).copy(
                processingStatus = NotificationProcessingStatus.COMPLETED,
                candidateId = stored.id,
                title = null,
                mainText = null,
                expandedText = null,
                normalizedText = null,
                contentRedactedAt = updatedAt,
                updatedAt = updatedAt,
            )
        return stored
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
        values[id] =
            checkNotNull(values[id]).copy(
                processingStatus = status,
                candidateId = candidateId,
                retryCount = retryCount,
                errorType = errorType,
                errorMessage = safeErrorMessage,
                updatedAt = updatedAt,
            )
    }

    override suspend fun redactContent(
        id: String,
        redactedAt: Instant,
    ) {
        values[id] =
            checkNotNull(values[id]).copy(
                title = null,
                mainText = null,
                expandedText = null,
                normalizedText = null,
                contentRedactedAt = redactedAt,
                updatedAt = redactedAt,
            )
    }

    override suspend fun deleteAll() {
        values.clear()
        candidates.values.entries.removeAll { it.value.source == CandidateSource.ANDROID_NOTIFICATION }
    }
}

private class FakeCandidates : TransactionCandidateRepository {
    val values = linkedMapOf<String, TransactionCandidate>()

    override suspend fun insert(candidate: TransactionCandidate): TransactionCandidate =
        values.values.firstOrNull { it.idempotencyKey == candidate.idempotencyKey }
            ?: candidate.also { values[it.id] = it }

    override suspend fun insertAllAtomically(candidates: List<TransactionCandidate>): List<TransactionCandidate> =
        candidates.map { insert(it) }

    override suspend fun get(id: String): TransactionCandidate? = values[id]

    override suspend fun getByIdempotencyKey(idempotencyKey: String): TransactionCandidate? =
        values.values.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun byImportSession(sessionId: String): List<TransactionCandidate> =
        values.values.filter { it.importSessionId == sessionId }

    override suspend fun pendingReview(): List<TransactionCandidate> {
        return values.values.filter { it.status == CandidateStatus.NEEDS_REVIEW }
    }

    override suspend fun duplicatesByFingerprint(
        fingerprint: String,
        excludingCandidateId: String,
    ): List<TransactionCandidate> =
        values.values.filter { candidate ->
            candidate.fingerprint == fingerprint && candidate.id != excludingCandidateId
        }

    override suspend fun potentialExistingExpenseIds(candidate: TransactionCandidate): List<String> = emptyList()

    override suspend fun update(candidate: TransactionCandidate) {
        values[candidate.id] = candidate
    }

    override suspend fun updateAllAtomically(candidates: List<TransactionCandidate>) {
        candidates.forEach { values[it.id] = it }
    }

    override suspend fun updateStatus(
        id: String,
        status: CandidateStatus,
        retryCount: Long,
        errorType: String?,
        safeErrorMessage: String?,
        updatedAt: Instant,
    ) {
        values[id] =
            checkNotNull(values[id]).copy(
                status = status,
                retryCount = retryCount,
                errorType = errorType,
                errorMessage = safeErrorMessage,
                updatedAt = updatedAt,
            )
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
        val current = values[id]
        val canTransition = current?.status == expectedStatus
        if (canTransition) {
            val candidate = checkNotNull(current)
            values[id] =
                candidate.copy(
                    status = status,
                    retryCount = retryCount,
                    errorType = errorType,
                    errorMessage = safeErrorMessage,
                    updatedAt = updatedAt,
                )
        }
        return canTransition
    }

    override suspend fun deleteUnapproved(id: String) {
        if (values[id]?.status != CandidateStatus.APPROVED) values.remove(id)
    }
}

private class FakeApprovalRepository(
    private val candidates: FakeCandidates,
) : CandidateApprovalRepository {
    var insertedExpenseCount = 0
        private set

    override suspend fun approve(command: ApproveExpenseCandidateCommand): CandidateApprovalResult {
        val candidate = candidates.get(command.candidateId)
        return when {
            candidate == null -> CandidateApprovalResult.Rejected(CandidateApprovalFailure.CANDIDATE_NOT_FOUND)
            candidate.status == CandidateStatus.APPROVED -> {
                CandidateApprovalResult.AlreadyApproved(checkNotNull(candidate.linkedExpenseId))
            }
            candidate.status != CandidateStatus.NEEDS_REVIEW -> {
                CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_STATE)
            }
            else -> approveCandidate(candidate, command)
        }
    }

    private suspend fun approveCandidate(
        candidate: TransactionCandidate,
        command: ApproveExpenseCandidateCommand,
    ): CandidateApprovalResult {
        insertedExpenseCount += 1
        candidates.update(
            candidate.copy(
                status = CandidateStatus.APPROVED,
                linkedExpenseId = command.expenseId,
                updatedAt = command.createdAt,
            ),
        )
        return CandidateApprovalResult.Approved(command.expenseId)
    }

    override suspend fun approveImportAtomically(
        importSessionId: String,
        commands: List<ApproveExpenseCandidateCommand>,
        completedAt: Instant,
    ): List<CandidateApprovalResult> = emptyList()
}

private object EmptyCategoryRepository : CategoryRepository {
    override suspend fun list(): List<Category> = emptyList()

    override suspend fun upsert(category: Category) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun usageCount(id: String): Long = 0
}

private class FakeProfiles(
    private val profile: ProviderProfile,
) : ProviderProfileRepository {
    override suspend fun upsert(profile: ProviderProfile) = Unit

    override suspend fun get(id: String): ProviderProfile? = profile.takeIf { it.id == id }

    override suspend fun list(): List<ProviderProfile> = listOf(profile)

    override suspend fun listVisionCapable(): List<ProviderProfile> = emptyList()

    override suspend fun listSupporting(required: ProviderCapabilities): List<ProviderProfile> =
        listOf(profile).filter { it.capabilities.supports(required) }

    override suspend fun deactivate(
        id: String,
        updatedAt: Instant,
    ) = Unit
}

private class FakeSecretStore(
    private val apiKey: String,
) : AdvisorSecretStore {
    override suspend fun readApiKey(): String = apiKey

    override suspend fun writeApiKey(apiKey: String) = Unit

    override suspend fun clearApiKey() = Unit

    override suspend fun readApiKey(profileId: String): String = apiKey
}

private class FakeProviderClient : AiProviderClient {
    var requests = 0
    private val results = ArrayDeque<Result<AiProviderResponse>>()

    fun enqueueFailure(code: AiProviderFailureCode) {
        results.addLast(Result.failure(AiProviderException(code)))
    }

    fun enqueueContent(content: String) {
        results.addLast(Result.success(AiProviderResponse(content)))
    }

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
        images: List<com.samluiz.gyst.domain.service.AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse {
        requests += 1
        return results.removeFirstOrNull()?.getOrThrow()
            ?: AiProviderResponse(
                content =
                    """
                    {
                        "description":"Padaria Exemplo",
                        "amount":"R$ 42,90",
                        "currency":"BRL",
                        "transactionType":"expense",
                        "confidence":0.95
                    }
                    """.trimIndent(),
            )
    }
}

private class FakePermissionController(
    var listenerAccessGranted: Boolean = true,
    var applicationNotificationsEnabled: Boolean = true,
    var applicationPermission: ApplicationNotificationPermissionState = ApplicationNotificationPermissionState.GRANTED,
) : DetectionPermissionController {
    var listenerRebindRequests = 0

    override fun snapshot(
        permissionWasRequestedBefore: Boolean,
        shouldShowRationale: Boolean?,
    ) = DetectionPermissionSnapshot(
        notificationListenerAccessGranted = listenerAccessGranted,
        applicationNotificationsEnabled = applicationNotificationsEnabled,
        applicationNotificationPermission = applicationPermission,
    )

    override fun openNotificationListenerSettings(): Boolean = true

    override fun openApplicationNotificationSettings(): Boolean = true

    override fun requestListenerRebind() {
        listenerRebindRequests += 1
    }
}

private class FakeScheduler : NotificationAnalysisScheduling {
    val scheduled = mutableListOf<String>()

    override fun schedule(suggestionId: String): Boolean = scheduled.add(suggestionId)

    override fun cancel(suggestionId: String) {
        scheduled.remove(suggestionId)
    }

    override fun cancelAll() {
        scheduled.clear()
    }
}

private class FakeNotifier(
    var delivery: DetectionNotificationDelivery = DetectionNotificationDelivery.SENT,
) : DetectedTransactionNotificationSink {
    val sent = mutableListOf<DetectedTransactionNotification>()
    val cancelled = mutableListOf<String>()

    override fun notify(request: DetectedTransactionNotification): DetectionNotificationDelivery {
        sent += request
        return delivery
    }

    override fun cancel(suggestionId: String) {
        cancelled += suggestionId
    }
}

private object PrefixContentProtector : NotificationContentProtector {
    override fun protect(plainText: String): String = "protected:$plainText"

    override fun reveal(protectedText: String): String = protectedText.removePrefix("protected:")
}
