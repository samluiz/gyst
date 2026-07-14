package com.samluiz.gyst.domain.service

enum class AdvisorProviderPresetId { OPENAI, OPENCODE_ZEN, GEMINI, OPENROUTER, GROQ, CUSTOM }

data class AdvisorProviderPreset(
    val id: AdvisorProviderPresetId,
    val displayName: String,
    val config: AdvisorConfig?,
    val apiKeyUrl: String?,
) {
    companion object {
        val entries =
            listOf(
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENAI,
                    displayName = "OpenAI",
                    config =
                        AdvisorConfig(
                            baseUrl = "https://api.openai.com/v1",
                            model = "gpt-5.4-mini",
                            apiFormat = AdvisorApiFormat.RESPONSES,
                            providerId = "openai",
                            profileId = "preset-openai",
                            capabilities =
                                setOf(
                                    AiCapability.TEXT_GENERATION,
                                    AiCapability.VISION_INPUT,
                                    AiCapability.STRUCTURED_OUTPUT,
                                    AiCapability.STREAMING,
                                ),
                        ),
                    apiKeyUrl = "https://platform.openai.com/api-keys",
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENCODE_ZEN,
                    displayName = "OpenCode Zen",
                    config =
                        AdvisorConfig(
                            baseUrl = "https://opencode.ai/zen/v1",
                            model = "deepseek-v4-flash-free",
                            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
                            providerId = "opencode-zen",
                            profileId = "preset-opencode-zen",
                            capabilities = setOf(AiCapability.TEXT_GENERATION, AiCapability.STREAMING),
                        ),
                    apiKeyUrl = "https://opencode.ai/zen",
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.GEMINI,
                    displayName = "Gemini",
                    config =
                        AdvisorConfig(
                            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                            model = "gemini-3.1-flash-lite",
                            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
                            providerId = "gemini",
                            profileId = "preset-gemini",
                            capabilities =
                                setOf(
                                    AiCapability.TEXT_GENERATION,
                                    AiCapability.VISION_INPUT,
                                    AiCapability.STRUCTURED_OUTPUT,
                                    AiCapability.STREAMING,
                                ),
                        ),
                    apiKeyUrl = "https://aistudio.google.com/app/apikey",
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENROUTER,
                    displayName = "OpenRouter",
                    config =
                        AdvisorConfig(
                            baseUrl = "https://openrouter.ai/api/v1",
                            model = "openrouter/free",
                            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
                            providerId = "openrouter",
                            profileId = "preset-openrouter",
                            capabilities = setOf(AiCapability.TEXT_GENERATION, AiCapability.STREAMING),
                        ),
                    apiKeyUrl = "https://openrouter.ai/settings/keys",
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.GROQ,
                    displayName = "Groq",
                    config =
                        AdvisorConfig(
                            baseUrl = "https://api.groq.com/openai/v1",
                            model = "openai/gpt-oss-120b",
                            apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS,
                            providerId = "groq",
                            profileId = "preset-groq",
                            capabilities =
                                setOf(
                                    AiCapability.TEXT_GENERATION,
                                    AiCapability.STRUCTURED_OUTPUT,
                                    AiCapability.STREAMING,
                                ),
                        ),
                    apiKeyUrl = "https://console.groq.com/keys",
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.CUSTOM,
                    displayName = "Custom",
                    config = null,
                    apiKeyUrl = null,
                ),
            )

        fun matching(config: AdvisorConfig): AdvisorProviderPreset? =
            entries.firstOrNull { preset ->
                preset.config?.let {
                    it.providerId == config.providerId &&
                        it.baseUrl == config.baseUrl &&
                        it.model == config.model &&
                        it.apiFormat == config.apiFormat
                } == true
            }

        fun matchingLegacy(config: AdvisorConfig): AdvisorProviderPreset? =
            entries.firstOrNull { preset ->
                preset.config?.let {
                    it.baseUrl == config.baseUrl && it.model == config.model && it.apiFormat == config.apiFormat
                } == true
            }

        fun upgradeRetiredPreset(config: AdvisorConfig): AdvisorConfig {
            if (
                config.baseUrl != GEMINI_OPENAI_BASE_URL ||
                config.model != RETIRED_GEMINI_MODEL ||
                config.apiFormat != AdvisorApiFormat.CHAT_COMPLETIONS
            ) {
                return config
            }

            val currentGemini = requireNotNull(entries.single { it.id == AdvisorProviderPresetId.GEMINI }.config)
            return config.copy(
                model = currentGemini.model,
                providerId = currentGemini.providerId,
                capabilities = currentGemini.capabilities,
            )
        }

        private const val GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        private const val RETIRED_GEMINI_MODEL = "gemini-3.5-flash"
    }
}
