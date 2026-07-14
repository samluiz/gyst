package com.samluiz.gyst.data.repository

import com.samluiz.gyst.domain.model.*
import com.samluiz.gyst.domain.repository.*

class SqlSettingsRepository(private val holder: DatabaseHolder) : SettingsRepository {
    private val q get() = holder.db.financeQueries

    override suspend fun getString(key: String): String? = q.getAppSetting(key).executeAsOneOrNull()

    override suspend fun setString(
        key: String,
        value: String,
    ) {
        q.upsertAppSetting(value_ = value, key = key, key_ = key, value__ = value)
    }
}
