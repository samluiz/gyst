package com.samluiz.gyst.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAnalysisWorkContractTest {
    @Test
    fun `work accepts only bounded identifier characters`() {
        assertTrue(NotificationAnalysisWorkContract.isValidSuggestionId("suggestion_01-abc.def"))
        assertFalse(NotificationAnalysisWorkContract.isValidSuggestionId(""))
        assertFalse(NotificationAnalysisWorkContract.isValidSuggestionId("suggestion with spaces"))
        assertFalse(NotificationAnalysisWorkContract.isValidSuggestionId("a".repeat(129)))
    }

    @Test
    fun `unique work name is deterministic and carries no financial payload`() {
        val id = "suggestion-42"
        assertEquals(
            "gyst-transaction-analysis-suggestion-42",
            NotificationAnalysisWorkContract.uniqueWorkName(id),
        )
        assertNull(NotificationAnalysisWorkContract.uniqueWorkName("R$ 42,90"))
    }

    @Test
    fun `retry policy is bounded and starts with the WorkManager minimum backoff`() {
        assertEquals(5, NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS)
        assertEquals(30L, NotificationAnalysisWorkContract.BACKOFF_SECONDS)
    }
}
