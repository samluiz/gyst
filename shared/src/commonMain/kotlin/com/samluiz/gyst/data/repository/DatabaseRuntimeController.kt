package com.samluiz.gyst.data.repository

class DatabaseRuntimeController(
    private val holder: DatabaseHolder,
) {
    suspend fun reloadDatabase() {
        holder.reloadDatabase()
    }
}
