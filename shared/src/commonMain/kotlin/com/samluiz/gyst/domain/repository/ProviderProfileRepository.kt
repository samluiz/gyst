package com.samluiz.gyst.domain.repository

import com.samluiz.gyst.domain.model.ProviderCapabilities
import com.samluiz.gyst.domain.model.ProviderProfile
import kotlin.time.Instant

interface ProviderProfileRepository {
    suspend fun upsert(profile: ProviderProfile)

    suspend fun get(id: String): ProviderProfile?

    suspend fun list(): List<ProviderProfile>

    suspend fun listVisionCapable(): List<ProviderProfile>

    suspend fun listSupporting(required: ProviderCapabilities): List<ProviderProfile>

    suspend fun deactivate(
        id: String,
        updatedAt: Instant,
    )
}
