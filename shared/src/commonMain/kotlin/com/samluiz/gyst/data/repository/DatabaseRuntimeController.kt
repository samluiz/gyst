package com.samluiz.gyst.data.repository

class DatabaseRuntimeController(
    private val holder: DatabaseHolder,
) {
    /** Waits for active repository work, then replaces the driver without exposing an intermediate state. */
    suspend fun reloadDatabase() {
        holder.reloadDatabase()
    }

    /** Performs a rollback-capable storage replacement under the repository access gate. */
    suspend fun replaceDatabase(
        install: () -> Unit,
        rollback: () -> Unit,
        commit: () -> Unit,
    ) {
        holder.replaceDatabase(
            install = install,
            rollback = rollback,
            commit = commit,
        )
    }
}
