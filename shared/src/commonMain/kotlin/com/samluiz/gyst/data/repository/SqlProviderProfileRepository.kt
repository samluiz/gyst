package com.samluiz.gyst.data.repository

import com.samluiz.gyst.db.Advisor_provider_profile
import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.repository.ProviderProfileRepository
import kotlin.time.Instant

class SqlProviderProfileRepository(
    private val holder: DatabaseHolder,
) : ProviderProfileRepository {
    override suspend fun upsert(profile: ProviderProfile) {
        holder.withDatabase { database ->
            database.financeQueries.upsertAdvisorProviderProfile(
                provider_id = profile.providerId,
                display_name = profile.displayName,
                base_url = profile.baseUrl,
                model = profile.model,
                api_format = profile.apiFormat,
                supports_text = profile.capabilities.textGeneration.asLong(),
                supports_vision = profile.capabilities.visionInput.asLong(),
                supports_structured_output = profile.capabilities.structuredOutput.asLong(),
                supports_streaming = profile.capabilities.streaming.asLong(),
                supports_tool_calling = profile.capabilities.toolCalling.asLong(),
                active = profile.active.asLong(),
                updated_at = profile.updatedAt.toString(),
                last_used_at = profile.lastUsedAt?.toString(),
                id = profile.id,
                id_ = profile.id,
                provider_id_ = profile.providerId,
                display_name_ = profile.displayName,
                base_url_ = profile.baseUrl,
                model_ = profile.model,
                api_format_ = profile.apiFormat,
                supports_text_ = profile.capabilities.textGeneration.asLong(),
                supports_vision_ = profile.capabilities.visionInput.asLong(),
                supports_structured_output_ = profile.capabilities.structuredOutput.asLong(),
                supports_streaming_ = profile.capabilities.streaming.asLong(),
                supports_tool_calling_ = profile.capabilities.toolCalling.asLong(),
                active_ = profile.active.asLong(),
                created_at = profile.createdAt.toString(),
                updated_at_ = profile.updatedAt.toString(),
                last_used_at_ = profile.lastUsedAt?.toString(),
            )
        }
    }

    override suspend fun get(id: String): ProviderProfile? =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorProviderProfileById(id).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun list(): List<ProviderProfile> =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorProviderProfiles().executeAsList().map { it.toDomain() }
        }

    override suspend fun listVisionCapable(): List<ProviderProfile> =
        holder.withDatabase { database ->
            database.financeQueries.selectActiveVisionProviderProfiles().executeAsList().map { it.toDomain() }
        }

    override suspend fun listSupporting(required: ProviderCapabilities): List<ProviderProfile> =
        holder.withDatabase { database ->
            database.financeQueries.selectAdvisorProviderProfiles().executeAsList()
                .map { it.toDomain() }
                .filter { it.active && it.capabilities.supports(required) }
        }

    override suspend fun deactivate(
        id: String,
        updatedAt: Instant,
    ) {
        holder.withDatabase { database ->
            database.financeQueries.deactivateAdvisorProviderProfile(updatedAt.toString(), id)
        }
    }
}

private fun Advisor_provider_profile.toDomain(): ProviderProfile =
    ProviderProfile(
        id = id,
        providerId = provider_id,
        displayName = display_name,
        baseUrl = base_url,
        model = model,
        apiFormat = api_format,
        capabilities =
            ProviderCapabilities(
                textGeneration = supports_text == 1L,
                visionInput = supports_vision == 1L,
                structuredOutput = supports_structured_output == 1L,
                streaming = supports_streaming == 1L,
                toolCalling = supports_tool_calling == 1L,
            ),
        active = active == 1L,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
        lastUsedAt = last_used_at?.let(Instant::parse),
    )

internal fun Boolean.asLong(): Long = if (this) 1L else 0L
