package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.repository.SettingsRepository
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorProviderPreset
import com.samluiz.gyst.domain.service.AdvisorProviderPresetId
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import com.samluiz.gyst.domain.service.AiCapability
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
            check(requireNotNull(preset.apiKeyUrl).startsWith("https://"))
        }
        assertEquals(null, AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.CUSTOM }.apiKeyUrl)
    }

    @Test
    fun openCodeZenPresetUsesTheFreeDeepSeekChatCompletionsModel() {
        val preset = AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.OPENCODE_ZEN }

        val config = requireNotNull(preset.config)
        assertEquals("https://opencode.ai/zen/v1", config.baseUrl)
        assertEquals("deepseek-v4-flash-free", config.model)
        assertEquals(AdvisorApiFormat.CHAT_COMPLETIONS, config.apiFormat)
        assertEquals("opencode-zen", config.providerId)
        assertEquals(setOf(AiCapability.TEXT_GENERATION, AiCapability.STREAMING), config.capabilities)
    }

    @Test
    fun openRouterPresetUsesTheFreeModelsRouter() {
        val preset = AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.OPENROUTER }

        val config = requireNotNull(preset.config)
        assertEquals("https://openrouter.ai/api/v1", config.baseUrl)
        assertEquals("openrouter/free", config.model)
        assertEquals(AdvisorApiFormat.CHAT_COMPLETIONS, config.apiFormat)
        assertEquals("openrouter", config.providerId)
        assertEquals(setOf(AiCapability.TEXT_GENERATION, AiCapability.STREAMING), config.capabilities)
    }

    @Test
    fun geminiPresetUsesTheStableFlashLiteVisionModel() {
        val preset = AdvisorProviderPreset.entries.single { it.id == AdvisorProviderPresetId.GEMINI }

        val config = requireNotNull(preset.config)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", config.baseUrl)
        assertEquals("gemini-3.1-flash-lite", config.model)
        assertEquals(AdvisorApiFormat.CHAT_COMPLETIONS, config.apiFormat)
        assertEquals("gemini", config.providerId)
        assertEquals(
            setOf(
                AiCapability.TEXT_GENERATION,
                AiCapability.VISION_INPUT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.STREAMING,
            ),
            config.capabilities,
        )
    }

    @Test
    fun initializeUpgradesOnlyTheRetiredGeminiPresetModel() =
        runTest {
            val settings = MemorySettings()
            val secrets = MemorySecretStore()
            val service = OpenAiCompatibleAdvisorService(settings, secrets)
            service.configure(
                AdvisorConfig(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                    model = "gemini-3.5-flash",
                    apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
                    providerId = "gemini",
                ),
                "secret",
            )

            val recreated = OpenAiCompatibleAdvisorService(settings, secrets)
            recreated.initialize()

            assertEquals("gemini-3.1-flash-lite", recreated.state.value.config.model)
            assertEquals("default", recreated.state.value.config.profileId)
            assertEquals(
                setOf(
                    AiCapability.TEXT_GENERATION,
                    AiCapability.VISION_INPUT,
                    AiCapability.STRUCTURED_OUTPUT,
                    AiCapability.STREAMING,
                ),
                recreated.state.value.config.capabilities,
            )
        }

    @Test
    fun visionSupportIsDeclaredCentrallyByPreset() {
        val visionProviders =
            AdvisorProviderPreset.entries
                .mapNotNull { preset -> preset.config?.takeIf { AiCapability.VISION_INPUT in it.capabilities }?.let { preset.id } }

        assertEquals(setOf(AdvisorProviderPresetId.OPENAI, AdvisorProviderPresetId.GEMINI), visionProviders.toSet())
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
