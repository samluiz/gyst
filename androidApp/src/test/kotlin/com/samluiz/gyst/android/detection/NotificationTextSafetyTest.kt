package com.samluiz.gyst.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextSafetyTest {
    @Test
    fun `normalization removes control characters and collapses whitespace`() {
        assertEquals(
            "Purchase approved at Example Store",
            NotificationTextSafety.normalize(" Purchase\u0000  approved\n at   Example Store "),
        )
    }

    @Test
    fun `authentication codes are rejected in English and Portuguese`() {
        assertTrue(NotificationTextSafety.containsAuthenticationCode("Your verification code is 428190"))
        assertTrue(NotificationTextSafety.containsAuthenticationCode("Código de segurança: 428190"))
        assertFalse(NotificationTextSafety.containsAuthenticationCode("Compra de R$ 42,90 aprovada"))
    }

    @Test
    fun `long account and card numbers retain only their last four digits`() {
        assertEquals(
            "Card •••• 1234 used",
            NotificationTextSafety.redactLongNumbers("Card 5502 0934 1234 used"),
        )
    }
}
