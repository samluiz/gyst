package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.domain.service.AiImageInput
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiProviderClient
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiProviderResponse
import com.samluiz.gyst.domain.service.AiStreamEvent
import com.samluiz.gyst.domain.service.AiStructuredOutputSchema
import com.samluiz.gyst.domain.service.AiTokenUsage
import com.samluiz.gyst.domain.service.supports
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

class OpenAiCompatibleProviderClient(
    providedClient: HttpClient? = null,
) : AiProviderClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        providedClient ?: HttpClient {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
            }
        }

    override suspend fun generateText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): AiProviderResponse {
        config.requireCapability(AiCapability.TEXT_GENERATION)
        return execute(config, apiKey) { protocol ->
            protocol.textRequestBody(config.model, instructions, messages, stream = false)
        }
    }

    override fun streamText(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
    ): Flow<AiStreamEvent> =
        flow {
            config.requireCapability(AiCapability.TEXT_GENERATION)
            config.requireCapability(AiCapability.STREAMING)
            val protocol = config.apiFormat.protocol()
            client
                .preparePost(protocol.endpoint(config.baseUrl)) {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(protocol.textRequestBody(config.model, instructions, messages, stream = true).toString())
                }.execute { response ->
                    if (!response.status.isSuccess()) throw response.toProviderException()
                    emit(AiStreamEvent.Started())
                    val channel = response.bodyAsChannel()
                    var providerMessageId: String? = null
                    var usage: AiTokenUsage? = null
                    while (!channel.isClosedForRead) {
                        val line = channel.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isBlank() || payload == "[DONE]") continue
                        val root =
                            runCatching { json.parseToJsonElement(payload).jsonObject }
                                .getOrElse { error ->
                                    throw AiProviderException(
                                        code = AiProviderFailureCode.INVALID_RESPONSE,
                                        message = "Provider returned an invalid stream event.",
                                        cause = error,
                                    )
                                }
                        providerMessageId = root.providerMessageId() ?: providerMessageId
                        root.streamDelta(config.apiFormat)?.takeIf(String::isNotEmpty)?.let { delta ->
                            emit(AiStreamEvent.ContentDelta(delta))
                        }
                        root.tokenUsage(config.apiFormat)?.let { usage = it }
                    }
                    emit(AiStreamEvent.Completed(providerMessageId = providerMessageId, tokenUsage = usage))
                }
        }.catch { error -> throw error.asProviderException() }

    override suspend fun generateStructured(
        config: AdvisorConfig,
        apiKey: String,
        instructions: String,
        messages: List<AiMessage>,
        images: List<AiImageInput>,
        schema: AiStructuredOutputSchema,
    ): AiProviderResponse {
        config.requireCapability(AiCapability.STRUCTURED_OUTPUT)
        if (images.isNotEmpty()) config.requireCapability(AiCapability.VISION_INPUT)
        return execute(config, apiKey) { protocol ->
            protocol.structuredRequestBody(config.model, instructions, messages, images, schema)
        }
    }

    private suspend fun execute(
        config: AdvisorConfig,
        apiKey: String,
        body: (AdvisorApiProtocol) -> JsonObject,
    ): AiProviderResponse =
        try {
            executeWithHttpRetry(config, apiKey, body)
        } catch (error: Throwable) {
            throw error.asProviderException()
        }

    private suspend fun executeWithHttpRetry(
        config: AdvisorConfig,
        apiKey: String,
        body: (AdvisorApiProtocol) -> JsonObject,
    ): AiProviderResponse {
        val protocol = config.apiFormat.protocol()
        val requestBody = body(protocol).toString()
        var retryAttempt = 0
        while (true) {
            val response =
                client.post(protocol.endpoint(config.baseUrl)) {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            if (response.status.isSuccess()) {
                return response.toProviderResponse(protocol, config.apiFormat)
            }
            val failure = response.toProviderException()
            if (!failure.isRetryableHttpFailure() || retryAttempt >= MAX_HTTP_RETRY_ATTEMPTS) {
                throw failure
            }
            delay(failure.retryDelayMillis(retryAttempt))
            retryAttempt++
        }
    }

    private suspend fun HttpResponse.toProviderResponse(
        protocol: AdvisorApiProtocol,
        apiFormat: AdvisorApiFormat,
    ): AiProviderResponse {
        val responseText = body<String>()
        if (!status.isSuccess()) throw toProviderException(responseText)
        val root =
            runCatching { json.parseToJsonElement(responseText).jsonObject }
                .getOrElse { error ->
                    throw AiProviderException(
                        code = AiProviderFailureCode.INVALID_RESPONSE,
                        message = "Provider returned invalid JSON.",
                        cause = error,
                    )
                }
        val content =
            runCatching { protocol.parseResponse(root) }
                .getOrElse { error ->
                    throw AiProviderException(
                        code = AiProviderFailureCode.INVALID_RESPONSE,
                        message = "Provider response did not contain valid output.",
                        cause = error,
                    )
                }
        return AiProviderResponse(
            content = content,
            providerMessageId = root.providerMessageId(),
            tokenUsage = root.tokenUsage(apiFormat),
        )
    }

    private suspend fun HttpResponse.toProviderException(bodyOverride: String? = null): AiProviderException {
        val responseText = bodyOverride ?: runCatching { body<String>() }.getOrDefault("")
        val providerError =
            runCatching {
                json.parseToJsonElement(responseText)
                    .jsonObject["error"]
                    ?.jsonObject
            }.getOrNull()
        val providerMessage = providerError?.get("message")?.jsonPrimitive?.contentOrNull?.safeProviderMessage()
        val providerErrorCode =
            sequenceOf("status", "type", "code")
                .mapNotNull { key -> providerError?.get(key)?.jsonPrimitive?.contentOrNull }
                .mapNotNull(String::safeProviderErrorCode)
                .firstOrNull()
        val retryAfter = headers["Retry-After"]?.toLongOrNull()
        val code =
            when (status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AiProviderFailureCode.AUTHENTICATION
                HttpStatusCode.TooManyRequests -> AiProviderFailureCode.RATE_LIMITED
                else -> AiProviderFailureCode.REQUEST_FAILED
            }
        return AiProviderException(
            code = code,
            retryAfterSeconds = retryAfter,
            httpStatusCode = status.value,
            providerErrorCode = providerErrorCode,
            message = providerMessage?.take(240) ?: "Provider returned HTTP ${status.value}.",
        )
    }
}

private fun String.safeProviderErrorCode(): String? =
    trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_PROVIDER_ERROR_CODE_LENGTH }
        ?.takeIf { value -> value.all { it.isLetterOrDigit() || it in "._-" } }

private fun String.safeProviderMessage(): String? =
    replace(DATA_URL_PATTERN, "[image data redacted]")
        .replace(BEARER_TOKEN_PATTERN, "$1[redacted]")
        .replace(SECRET_ASSIGNMENT_PATTERN, "$1[redacted]")
        .replace(LONG_DIGIT_SEQUENCE_PATTERN, "[number redacted]")
        .trim()
        .take(MAX_SAFE_PROVIDER_MESSAGE_LENGTH)
        .takeIf(String::isNotBlank)

private fun AdvisorConfig.requireCapability(capability: AiCapability) {
    if (!supports(capability)) {
        throw AiProviderException(
            code = AiProviderFailureCode.UNSUPPORTED_CAPABILITY,
            message = "The selected provider model does not declare ${capability.name.lowercase()} support.",
        )
    }
}

private fun Throwable.asProviderException(): Throwable =
    when (this) {
        is AiProviderException -> this
        is CancellationException -> this
        is HttpRequestTimeoutException ->
            AiProviderException(AiProviderFailureCode.TIMEOUT, message = "Provider request timed out.", cause = this)
        is IOException ->
            AiProviderException(AiProviderFailureCode.NETWORK, message = "Provider network request failed.", cause = this)
        else -> AiProviderException(AiProviderFailureCode.REQUEST_FAILED, message = message, cause = this)
    }

private fun AiProviderException.isRetryableHttpFailure(): Boolean = httpStatusCode in RETRYABLE_HTTP_STATUS_CODES

private fun AiProviderException.retryDelayMillis(retryAttempt: Int): Long {
    val retryAfterMillis = retryAfterSeconds?.times(1_000L)
    if (retryAfterMillis != null) return retryAfterMillis.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    return (INITIAL_RETRY_DELAY_MILLIS shl retryAttempt).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
}

private fun JsonObject.providerMessageId(): String? =
    this["id"]?.jsonPrimitive?.contentOrNull
        ?: this["response"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull

private fun JsonObject.streamDelta(apiFormat: AdvisorApiFormat): String? =
    when (apiFormat) {
        AdvisorApiFormat.CHAT_COMPLETIONS ->
            this["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
        AdvisorApiFormat.RESPONSES ->
            if (this["type"]?.jsonPrimitive?.contentOrNull == "response.output_text.delta") {
                this["delta"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
    }

private fun JsonObject.tokenUsage(apiFormat: AdvisorApiFormat): AiTokenUsage? {
    val usage =
        when (apiFormat) {
            AdvisorApiFormat.CHAT_COMPLETIONS -> this["usage"]?.jsonObject
            AdvisorApiFormat.RESPONSES ->
                this["usage"]?.jsonObject
                    ?: this["response"]?.jsonObject?.get("usage")?.jsonObject
        } ?: return null
    val prompt =
        usage["prompt_tokens"]?.jsonPrimitive?.longOrNull
            ?: usage["input_tokens"]?.jsonPrimitive?.longOrNull
    val completion =
        usage["completion_tokens"]?.jsonPrimitive?.longOrNull
            ?: usage["output_tokens"]?.jsonPrimitive?.longOrNull
    val total = usage["total_tokens"]?.jsonPrimitive?.longOrNull ?: prompt?.plus(completion ?: 0L)
    return AiTokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
}

private val DATA_URL_PATTERN = Regex("data:[^,\\s]+,[^\\s\\\"]+", RegexOption.IGNORE_CASE)
private val BEARER_TOKEN_PATTERN = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~-]+")
private val SECRET_ASSIGNMENT_PATTERN = Regex("(?i)((?:api[_ -]?key|token|secret)\\s*[:=]\\s*)[^,;\\s]+")
private val LONG_DIGIT_SEQUENCE_PATTERN = Regex("\\b\\d{9,}\\b")
private const val MAX_PROVIDER_ERROR_CODE_LENGTH = 64
private const val MAX_SAFE_PROVIDER_MESSAGE_LENGTH = 240
private const val MAX_HTTP_RETRY_ATTEMPTS = 2
private const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
private const val MAX_RETRY_DELAY_MILLIS = 10_000L
private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
