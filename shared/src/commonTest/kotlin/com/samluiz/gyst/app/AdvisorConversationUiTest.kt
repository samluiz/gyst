package com.samluiz.gyst.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorConversationUiTest {
    @Test
    fun blankConversationTitleUsesLocalizedFallback() {
        assertEquals("Nova conversa", displayConversationTitle("   ", "Nova conversa"))
        assertEquals("New conversation", displayConversationTitle(null, "New conversation"))
    }

    @Test
    fun manualConversationTitleIsTrimmed() {
        assertEquals("Emergency fund", displayConversationTitle("  Emergency fund  ", "New conversation"))
    }
}
