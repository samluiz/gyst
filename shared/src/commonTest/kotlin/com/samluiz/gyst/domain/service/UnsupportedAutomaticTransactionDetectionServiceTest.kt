package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnsupportedAutomaticTransactionDetectionServiceTest {
    @Test
    fun `unsupported implementation remains inert and explicit`() =
        runTest {
            val service = UnsupportedAutomaticTransactionDetectionService()

            assertTrue(service.state.value.initialized)
            assertFalse(service.state.value.isSupported)
            assertFalse(service.requestApplicationNotificationPermission())
            assertTrue(service.installedApplications().isEmpty())
            assertIs<CandidateApprovalResult.Rejected>(service.approveSuggestion("suggestion"))
        }
}
