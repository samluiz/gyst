package com.samluiz.gyst.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionSuggestionDeepLinkTest {
    @Test
    fun `route round trips a stable suggestion identifier`() {
        val id = "0190f82e-582b-7e76-a534-6dc3d01742a1"
        val route = TransactionSuggestionDeepLink.uriString(id)

        assertEquals(id, TransactionSuggestionDeepLink.parse(route))
    }

    @Test
    fun `parser rejects another route or an unsafe identifier`() {
        assertNull(TransactionSuggestionDeepLink.parse("gyst://transaction-suggestions/list"))
        assertNull(TransactionSuggestionDeepLink.parse("https://transaction-suggestions/review/valid-id"))
        assertNull(TransactionSuggestionDeepLink.uriString("unsafe/id"))
    }
}
