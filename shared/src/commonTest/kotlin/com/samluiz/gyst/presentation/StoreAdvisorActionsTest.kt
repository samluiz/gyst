package com.samluiz.gyst.presentation

import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorService
import com.samluiz.gyst.domain.service.AdvisorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreAdvisorActionsTest {
    @Test
    fun conversationActionsDelegateStableIdentifiers() =
        runTest {
            val service = RecordingAdvisorService()
            val actions = StoreAdvisorActions(service) { MainState() }

            actions.createConversation(null)
            actions.selectConversation("conversation-1")
            actions.renameConversation("conversation-1", "House plan")
            actions.deleteConversation("conversation-2")
            actions.cancelResponse()

            assertEquals(listOf<String?>(null), service.createdTitles)
            assertEquals(listOf("conversation-1"), service.selectedIds)
            assertEquals(listOf("conversation-1" to "House plan"), service.renamed)
            assertEquals(listOf("conversation-2"), service.deletedIds)
            assertTrue(service.cancelled)
        }

    @Test
    fun retryUsesCurrentFinancialContextAndLanguage() =
        runTest {
            val state = MainState()
            val service = RecordingAdvisorService()
            val actions = StoreAdvisorActions(service) { state }

            actions.retryMessage("assistant-7", "pt")

            assertEquals("assistant-7", service.retriedMessageId)
            assertEquals("pt", service.retryLanguage)
            assertEquals(state.currentMonth, service.retryContext?.month)
        }
}

private class RecordingAdvisorService : AdvisorService {
    override val state: StateFlow<AdvisorState> = MutableStateFlow(AdvisorState())
    val createdTitles = mutableListOf<String?>()
    val selectedIds = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val deletedIds = mutableListOf<String>()
    var cancelled = false
    var retriedMessageId: String? = null
    var retryLanguage: String? = null
    var retryContext: AdvisorFinancialContext? = null

    override suspend fun initialize() = Unit

    override suspend fun configure(
        config: AdvisorConfig,
        apiKey: String?,
    ) = Unit

    override suspend fun ask(
        prompt: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    ) = Unit

    override suspend fun ensureOverview(
        context: AdvisorFinancialContext,
        languageCode: String,
        force: Boolean,
    ) = Unit

    override suspend fun clearConversation() = Unit

    override suspend fun createConversation(title: String?): String {
        createdTitles += title
        return "created"
    }

    override suspend fun selectConversation(conversationId: String) {
        selectedIds += conversationId
    }

    override suspend fun renameConversation(
        conversationId: String,
        title: String,
    ) {
        renamed += conversationId to title
    }

    override suspend fun deleteConversation(conversationId: String) {
        deletedIds += conversationId
    }

    override suspend fun retryMessage(
        messageId: String,
        context: AdvisorFinancialContext,
        languageCode: String,
    ) {
        retriedMessageId = messageId
        retryContext = context
        retryLanguage = languageCode
    }

    override suspend fun cancelResponse() {
        cancelled = true
    }

    override suspend fun disconnect() = Unit
}
