package com.samluiz.gyst.domain.service

enum class AdvisorProviderPresetId { OPENAI, OPENCODE_ZEN, GEMINI, OPENROUTER, GROQ, CUSTOM }

data class AdvisorProviderPreset(
    val id: AdvisorProviderPresetId,
    val displayName: String,
    val config: AdvisorConfig?,
) {
    companion object {
        val entries =
            listOf(
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENAI,
                    displayName = "OpenAI",
                    config = AdvisorConfig("https://api.openai.com/v1", "gpt-5.4-mini", AdvisorApiFormat.RESPONSES),
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENCODE_ZEN,
                    displayName = "OpenCode Zen",
                    config =
                        AdvisorConfig(
                            "https://opencode.ai/zen/v1",
                            "deepseek-v4-flash-free",
                            AdvisorApiFormat.CHAT_COMPLETIONS,
                        ),
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.GEMINI,
                    displayName = "Gemini",
                    config =
                        AdvisorConfig(
                            "https://generativelanguage.googleapis.com/v1beta/openai",
                            "gemini-3.5-flash",
                            AdvisorApiFormat.CHAT_COMPLETIONS,
                        ),
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.OPENROUTER,
                    displayName = "OpenRouter",
                    config = AdvisorConfig("https://openrouter.ai/api/v1", "openrouter/free", AdvisorApiFormat.CHAT_COMPLETIONS),
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.GROQ,
                    displayName = "Groq",
                    config = AdvisorConfig("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", AdvisorApiFormat.CHAT_COMPLETIONS),
                ),
                AdvisorProviderPreset(
                    id = AdvisorProviderPresetId.CUSTOM,
                    displayName = "Custom",
                    config = null,
                ),
            )

        fun matching(config: AdvisorConfig): AdvisorProviderPreset? =
            entries.firstOrNull { preset ->
                preset.config?.let {
                    it.baseUrl == config.baseUrl && it.model == config.model && it.apiFormat == config.apiFormat
                } == true
            }
    }
}
