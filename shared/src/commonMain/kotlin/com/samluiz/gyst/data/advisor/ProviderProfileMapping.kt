package com.samluiz.gyst.data.advisor

import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AiCapability

internal fun AdvisorConfig.toProviderCapabilities(): ProviderCapabilities =
    ProviderCapabilities(
        textGeneration = AiCapability.TEXT_GENERATION in capabilities,
        visionInput = AiCapability.VISION_INPUT in capabilities,
        structuredOutput = AiCapability.STRUCTURED_OUTPUT in capabilities,
        streaming = AiCapability.STREAMING in capabilities,
        toolCalling = AiCapability.TOOL_CALLING in capabilities,
    )

fun ProviderProfile.toAdvisorConfig(): AdvisorConfig =
    AdvisorConfig(
        baseUrl = baseUrl,
        model = model,
        apiFormat = runCatching { AdvisorApiFormat.valueOf(apiFormat) }.getOrDefault(AdvisorApiFormat.CHAT_COMPLETIONS),
        providerId = providerId,
        profileId = id,
        capabilities = capabilities.toAiCapabilities(),
    )

private fun ProviderCapabilities.toAiCapabilities(): Set<AiCapability> =
    buildSet {
        if (textGeneration) add(AiCapability.TEXT_GENERATION)
        if (visionInput) add(AiCapability.VISION_INPUT)
        if (structuredOutput) add(AiCapability.STRUCTURED_OUTPUT)
        if (streaming) add(AiCapability.STREAMING)
        if (toolCalling) add(AiCapability.TOOL_CALLING)
    }
