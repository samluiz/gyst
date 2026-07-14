package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal interface AdvisorApiProtocol {
    fun endpoint(baseUrl: String): String

    fun requestBody(
        model: String,
        instructions: String,
        messages: List<AdvisorMessage>,
    ): JsonObject

    fun parseResponse(root: JsonObject): String
}

internal fun AdvisorApiFormat.protocol(): AdvisorApiProtocol =
    when (this) {
        AdvisorApiFormat.CHAT_COMPLETIONS -> ChatCompletionsProtocol
        AdvisorApiFormat.RESPONSES -> ResponsesProtocol
    }

private object ChatCompletionsProtocol : AdvisorApiProtocol {
    override fun endpoint(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/chat/completions"

    override fun requestBody(
        model: String,
        instructions: String,
        messages: List<AdvisorMessage>,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put(
                "messages",
                buildJsonArray {
                    add(message("system", instructions))
                    messages.forEach { add(message(it.role.apiRole(), it.content)) }
                },
            )
        }

    override fun parseResponse(root: JsonObject): String {
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray ->
                content.joinToString("\n") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            else -> error("Provider response did not contain assistant text.")
        }.trim().ifBlank { error("Provider returned an empty response.") }
    }
}

private object ResponsesProtocol : AdvisorApiProtocol {
    override fun endpoint(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/responses"

    override fun requestBody(
        model: String,
        instructions: String,
        messages: List<AdvisorMessage>,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put("instructions", JsonPrimitive(instructions))
            put(
                "input",
                JsonPrimitive(
                    messages.joinToString("\n\n") {
                        "${if (it.role == AdvisorRole.USER) "User" else "Advisor"}: ${it.content}"
                    },
                ),
            )
        }

    override fun parseResponse(root: JsonObject): String {
        root["output_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        val text =
            root["output"]
                ?.jsonArray
                ?.flatMap { item -> item.jsonObject["content"]?.jsonArray.orEmpty() }
                ?.mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
                .orEmpty()
                .trim()
        return text.ifBlank { error("Provider response did not contain assistant text.") }
    }
}

private fun AdvisorRole.apiRole(): String = if (this == AdvisorRole.USER) "user" else "assistant"

private fun message(
    role: String,
    content: String,
) = buildJsonObject {
    put("role", JsonPrimitive(role))
    put("content", JsonPrimitive(content))
}

private fun normalizeBaseUrl(value: String): String =
    value
        .trim()
        .trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/responses")
