package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    @Test
    fun chatStructuredVisionRequestUsesStrictSchemaAndDataUrl() {
        val protocol = AdvisorApiFormat.CHAT_COMPLETIONS.protocol()
        val request =
            protocol.structuredRequestBody(
                model = "vision-model",
                instructions = "Return transactions.",
                messages = listOf(AiMessage(AiMessageRole.USER, "Extract every row.")),
                images = listOf(AiImageInput("page-1", "image/png", byteArrayOf(1, 2, 3))),
                schema = AiStructuredOutputSchema("transactions", buildJsonObject { put("type", "object") }),
            )

        val format = request["response_format"]?.jsonObject
        assertEquals("json_schema", format?.get("type")?.jsonPrimitive?.content)
        assertEquals(true, format?.get("json_schema")?.jsonObject?.get("strict")?.jsonPrimitive?.content?.toBoolean())
        val imageUrl =
            request["messages"]
                ?.jsonArray
                ?.last()
                ?.jsonObject
                ?.get("content")
                ?.jsonArray
                ?.last()
                ?.jsonObject
                ?.get("image_url")
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
        assertEquals("data:image/png;base64,AQID", imageUrl)
    }

    @Test
    fun responsesStructuredVisionRequestUsesProviderNativeInputParts() {
        val request =
            AdvisorApiFormat.RESPONSES.protocol().structuredRequestBody(
                model = "vision-model",
                instructions = "Return transactions.",
                messages = emptyList(),
                images = listOf(AiImageInput("page-1", "image/jpeg", byteArrayOf(4, 5))),
                schema = AiStructuredOutputSchema("transactions", buildJsonObject { put("type", "object") }),
            )

        assertEquals(
            "input_image",
            request["input"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("content")
                ?.jsonArray
                ?.last()
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "json_schema",
            request["text"]?.jsonObject?.get("format")?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    private fun messages() = listOf(AdvisorMessage(AdvisorRole.USER, "How is next month?"))
}
