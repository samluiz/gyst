package com.samluiz.gyst.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DetectedTransactionNotifierTest {
    @Test
    fun `notification identifier is stable per suggestion`() {
        val first = DetectedTransactionNotifier.stableNotificationId("suggestion-1")

        assertEquals(first, DetectedTransactionNotifier.stableNotificationId("suggestion-1"))
        assertNotEquals(first, DetectedTransactionNotifier.stableNotificationId("suggestion-2"))
    }

    @Test
    fun `notification display text is bounded and redacts long digits`() {
        val safe = NotificationDisplaySafety.safeOneLine("Store   card 550209341234\napproved")

        assertEquals("Store card •••• 1234 approved", safe)
    }
}
