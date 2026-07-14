package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.domain.service.AiMessage
import com.samluiz.gyst.domain.service.AiMessageRole
import com.samluiz.gyst.domain.service.AiProviderException
import com.samluiz.gyst.domain.service.AiProviderFailureCode
import com.samluiz.gyst.domain.service.AiStreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OpenAiCompatibleProviderClientTest {
    @Test
    fun rejectsUnsupportedCapabilityBeforeCallingProvider() =
        runTest {
            var requests = 0
            val client =
                providerClient {
                    requests++
                    respond("{}")
                }

            val error =
                assertFailsWith<AiProviderException> {
                    client.streamText(textConfig(capabilities = setOf(AiCapability.TEXT_GENERATION)), "key", "system", emptyList()).toList()
                }

            assertEquals(AiProviderFailureCode.UNSUPPORTED_CAPABILITY, error.code)
            assertEquals(0, requests)
        }

    @Test
    fun mapsAuthenticationFailureWithoutExposingRequestPayload() =
        runTest {
            val client =
                providerClient {
                    respond(
                        content = """{"error":{"message":"Invalid credential"}}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = jsonHeaders,
                    )
                }

            val error =
                assertFailsWith<AiProviderException> {
                    client.generateText(
                        textConfig(),
                        "secret-that-must-not-appear",
                        "system",
                        listOf(AiMessage(AiMessageRole.USER, "hello")),
                    )
                }

            assertEquals(AiProviderFailureCode.AUTHENTICATION, error.code)
            assertEquals("Invalid credential", error.message)
        }

    @Test
    fun preservesSafeProviderDiagnosticsForRejectedRequests() =
        runTest {
            val client =
                providerClient {
                    respond(
                        content =
                            """
                            {
                              "error": {
                                "code": 400,
                                "status": "INVALID_ARGUMENT",
                                "message": "Unknown schema field; api_key=secret-value; account 12345678901"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.BadRequest,
                        headers = jsonHeaders,
                    )
                }

            val error =
                assertFailsWith<AiProviderException> {
                    client.generateText(textConfig(), "key", "system", emptyList())
                }

            assertEquals(AiProviderFailureCode.REQUEST_FAILED, error.code)
            assertEquals(400, error.httpStatusCode)
            assertEquals("INVALID_ARGUMENT", error.providerErrorCode)
            assertEquals(
                "Unknown schema field; api_key=[redacted]; account [number redacted]",
                error.message,
            )
        }

    @Test
    fun retriesTemporaryProviderFailureBeforeReturningSuccess() =
        runTest {
            var requests = 0
            val client =
                providerClient {
                    requests++
                    if (requests < 3) {
                        respond(
                            content = """{"error":{"status":"UNAVAILABLE","message":"Provider returned HTTP 503."}}""",
                            status = HttpStatusCode.ServiceUnavailable,
                            headers = jsonHeaders,
                        )
                    } else {
                        respond(
                            content = """{"choices":[{"message":{"content":"Recovered"}}]}""",
                            headers = jsonHeaders,
                        )
                    }
                }

            val response = client.generateText(textConfig(), "key", "system", emptyList())

            assertEquals("Recovered", response.content)
            assertEquals(3, requests)
        }

    @Test
    fun mapsTextAndTokenUsageToProviderIndependentResponse() =
        runTest {
            val client =
                providerClient {
                    respond(
                        content =
                            """
                            {
                              "id": "message-1",
                              "choices": [{"message": {"content": "Useful answer"}}],
                              "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                            }
                            """.trimIndent(),
                        headers = jsonHeaders,
                    )
                }

            val response = client.generateText(textConfig(), "key", "system", emptyList())

            assertEquals("Useful answer", response.content)
            assertEquals("message-1", response.providerMessageId)
            assertEquals(19L, response.tokenUsage?.totalTokens)
        }

    @Test
    fun parsesStreamingDeltasAndCompletionUsage() =
        runTest {
            val client =
                providerClient {
                    respond(
                        content =
                            """
                            data: {"id":"message-2","choices":[{"delta":{"content":"Hello "}}]}

                            data: {"id":"message-2","choices":[{"delta":{"content":"there"}}],"usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}

                            data: [DONE]

                            """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }

            val events =
                client.streamText(
                    textConfig(capabilities = setOf(AiCapability.TEXT_GENERATION, AiCapability.STREAMING)),
                    "key",
                    "system",
                    emptyList(),
                ).toList()

            assertIs<AiStreamEvent.Started>(events.first())
            assertEquals(
                listOf("Hello ", "there"),
                events.filterIsInstance<AiStreamEvent.ContentDelta>().map { it.content },
            )
            assertEquals(4L, assertIs<AiStreamEvent.Completed>(events.last()).tokenUsage?.totalTokens)
        }

    private fun providerClient(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OpenAiCompatibleProviderClient =
        OpenAiCompatibleProviderClient(
            HttpClient(
                MockEngine { request -> handler(request) },
            ),
        )

    private fun textConfig(capabilities: Set<AiCapability> = setOf(AiCapability.TEXT_GENERATION)) =
        AdvisorConfig(
            baseUrl = "https://example.test/v1",
            model = "test-model",
            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
            providerId = "test",
            capabilities = capabilities,
        )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
