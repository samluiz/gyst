package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlSettingsRepository(private val holder: DatabaseHolder) : SettingsRepository {
    override suspend fun getString(key: String): String? =
        holder.withDatabase { db ->
            db.financeQueries.getAppSetting(key).executeAsOneOrNull()
        }

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        holder.withDatabase { db ->
            db.financeQueries.upsertAppSetting(value_ = value, key = key, key_ = key, value__ = value)
        }
    }
}
