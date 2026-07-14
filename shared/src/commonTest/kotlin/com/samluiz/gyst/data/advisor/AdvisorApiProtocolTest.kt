package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorApiProtocolTest {
    private val json = Json

    @Test
    fun chatCompletionsOwnsEndpointRequestAndResponseShape() {
        val protocol = AdvisorApiFormat.CHAT_COMPLETIONS.protocol()

        assertEquals("https://opencode.ai/zen/v1/chat/completions", protocol.endpoint("https://opencode.ai/zen/v1"))
        val request = protocol.requestBody("deepseek-v4-pro", "instructions", messages())
        assertEquals("system", request["messages"]?.jsonArray?.first()?.jsonObject?.get("role")?.jsonPrimitive?.content)
        val response = json.parseToJsonElement("""{"choices":[{"message":{"content":"Grounded answer"}}]}""").jsonObject
        assertEquals("Grounded answer", protocol.parseResponse(response))
    }

    @Test
    fun responsesOwnsEndpointRequestAndResponseShape() {
        val protocol = AdvisorApiFormat.RESPONSES.protocol()

        assertEquals("https://opencode.ai/zen/v1/responses", protocol.endpoint("https://opencode.ai/zen/v1/chat/completions"))
        val request = protocol.requestBody("gpt-5.4-mini", "instructions", messages())
        assertEquals("instructions", request["instructions"]?.jsonPrimitive?.content)
        val response = json.parseToJsonElement("""{"output_text":"Grounded response"}""").jsonObject
        assertEquals("Grounded response", protocol.parseResponse(response))
    }

    private fun messages() = listOf(AdvisorMessage(AdvisorRole.USER, "How is next month?"))
}
