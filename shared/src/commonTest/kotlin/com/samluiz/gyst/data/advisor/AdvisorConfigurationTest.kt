package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorProviderPreset
import com.samluiz.gyst.domain.service.AdvisorProviderPresetId
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorConfigurationTest {
    @Test
    fun configurePreservesTheUsersUrl() =
        runTest {
            val service = OpenAiCompatibleAdvisorService(MemorySettings(), MemorySecretStore())
            val configuredUrl = "https://example.com/openai/v1/responses"

            service.configure(
                AdvisorConfig(configuredUrl, "custom-model", AdvisorApiFormat.RESPONSES),
                "secret",
            )

            assertEquals(configuredUrl, service.state.value.config.baseUrl)
        }

    @Test
    fun providerPresetsContainCompleteConnectionDetails() {
        val expected =
            setOf(
                AdvisorProviderPresetId.OPENAI,
                AdvisorProviderPresetId.OPENCODE_ZEN,
                AdvisorProviderPresetId.GEMINI,
                AdvisorProviderPresetId.OPENROUTER,
                AdvisorProviderPresetId.GROQ,
            )
        val configured = AdvisorProviderPreset.entries.filter { it.config != null }

        assertEquals(expected, configured.map { it.id }.toSet())
        configured.forEach { preset ->
            val config = requireNotNull(preset.config)
            check(config.baseUrl.isNotBlank())
            check(config.model.isNotBlank())
        }
    }

    @Test
    fun openCodeZenPresetUsesTheFreeDeepSeekChatCompletionsModel() {
        val preset = AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.OPENCODE_ZEN }

        assertEquals(
            AdvisorConfig(
                baseUrl = "https://opencode.ai/zen/v1",
                model = "deepseek-v4-flash-free",
                apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
            ),
            preset.config,
        )
    }

    @Test
    fun openRouterPresetUsesTheFreeModelsRouter() {
        val preset = AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.OPENROUTER }

        assertEquals(
            AdvisorConfig(
                baseUrl = "https://openrouter.ai/api/v1",
                model = "openrouter/free",
                apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
            ),
            preset.config,
        )
    }
}

private class MemorySettings : SettingsRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

private class MemorySecretStore : AdvisorSecretStore {
    private var key: String? = null

    override suspend fun readApiKey(): String? = key

    override suspend fun writeApiKey(apiKey: String) {
        key = apiKey
    }

    override suspend fun clearApiKey() {
        key = null
    }
}
