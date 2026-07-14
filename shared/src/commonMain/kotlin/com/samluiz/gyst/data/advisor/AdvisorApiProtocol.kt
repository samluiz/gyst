package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

internal interface AdvisorApiProtocol {
    fun endpoint(baseUrl: String): String

    fun requestBody(
        model: String,
        instructions: String,
        messages: List<AdvisorMessage>,
    ): JsonObject

    fun textRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        stream: Boolean,
    ): JsonObject

    fun structuredRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
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

    override fun textRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        stream: Boolean,
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
            if (stream) {
                put("stream", JsonPrimitive(true))
                put(
                    "stream_options",
                    buildJsonObject { put("include_usage", JsonPrimitive(true)) },
                )
            }
        }

    override fun structuredRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put(
                "messages",
                buildJsonArray {
                    add(message("system", instructions))
                    messages.forEach { add(message(it.role.apiRole(), it.content)) }
                    if (images.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("text"))
                                                put("text", JsonPrimitive("Analyze the attached financial image(s)."))
                                            },
                                        )
                                        images.forEach { image ->
                                            add(
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("image_url"))
                                                    put(
                                                        "image_url",
                                                        buildJsonObject {
                                                            put("url", JsonPrimitive(image.dataUrl()))
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "response_format",
                buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put(
                        "json_schema",
                        buildJsonObject {
                            put("name", JsonPrimitive(schema.name))
                            put("strict", JsonPrimitive(true))
                            put("schema", schema.jsonSchema)
                        },
                    )
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

    override fun textRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        stream: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put("instructions", JsonPrimitive(instructions))
            put("input", responseInput(messages, emptyList()))
            if (stream) put("stream", JsonPrimitive(true))
        }

    override fun structuredRequestBody(
        model: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put("instructions", JsonPrimitive(instructions))
            put("input", responseInput(messages, images))
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", JsonPrimitive("json_schema"))
                            put("name", JsonPrimitive(schema.name))
                            put("strict", JsonPrimitive(true))
                            put("schema", schema.jsonSchema)
                        },
                    )
                },
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

private fun AiMessageRole.apiRole(): String =
    when (this) {
        AiMessageRole.SYSTEM -> "system"
        AiMessageRole.USER -> "user"
        AiMessageRole.ASSISTANT -> "assistant"
        AiMessageRole.TOOL -> "tool"
    }

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

private fun responseInput(
    messages: List<AiMessage>,
    images: List<AiImageInput>,
): JsonArray =
    buildJsonArray {
        messages.forEach { aiMessage ->
            add(
                buildJsonObject {
                    put("role", JsonPrimitive(aiMessage.role.apiRole()))
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "type",
                                        JsonPrimitive(
                                            if (aiMessage.role == AiMessageRole.ASSISTANT) "output_text" else "input_text",
                                        ),
                                    )
                                    put("text", JsonPrimitive(aiMessage.content))
                                },
                            )
                        },
                    )
                },
            )
        }
        if (images.isNotEmpty()) {
            add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("input_text"))
                                    put("text", JsonPrimitive("Analyze the attached financial image(s)."))
                                },
                            )
                            images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("input_image"))
                                        put("image_url", JsonPrimitive(image.dataUrl()))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

private fun AiImageInput.dataUrl(): String = "data:$mimeType;base64,${Base64.Default.encode(bytes)}"
