package com.samluiz.gyst.domain.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUpdateState(
    val isAvailable: Boolean,
    val isChecking: Boolean = false,
    val currentVersion: String? = null,
    val latestVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val downloadUrl: String? = null,
    val downloadName: String? = null,
    val releasePageUrl: String? = null,
    val releasedAtIso: String? = null,
    val lastCheckedAtIso: String? = null,
    val notes: String? = null,
    val lastError: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val isUpdateDownloaded: Boolean = false,
    val requiresInstallPermission: Boolean = false,
)

interface AppUpdateService {
    val state: StateFlow<AppUpdateState>

    suspend fun checkForUpdates(silent: Boolean = true)

    suspend fun startUpdate()
}

class NoOpAppUpdateService : AppUpdateService {
    private val internal =
        MutableStateFlow(
            AppUpdateState(
                isAvailable = false,
            ),
        )

    override val state: StateFlow<AppUpdateState> = internal.asStateFlow()

    override suspend fun checkForUpdates(silent: Boolean) = Unit

    override suspend fun startUpdate() = Unit
}

fun compareSemVer(
    a: String,
    b: String,
): Int {
    fun parse(raw: String): Triple<Int, Int, Int> {
        val cleaned = raw.trim().removePrefix("v").substringBefore('-')
        val parts = cleaned.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }
    val pa = parse(a)
    val pb = parse(b)
    return when {
        pa.first != pb.first -> pa.first.compareTo(pb.first)
        pa.second != pb.second -> pa.second.compareTo(pb.second)
        else -> pa.third.compareTo(pb.third)
    }
}
