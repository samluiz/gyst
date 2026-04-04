package com.samluiz.gyst.desktop

import com.samluiz.gyst.app.BuildInfo
import com.samluiz.gyst.domain.service.AppUpdateService
import com.samluiz.gyst.domain.service.AppUpdateState
import com.samluiz.gyst.domain.service.compareSemVer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Clock

private const val DEFAULT_UPDATE_API_URL = "https://api.github.com/repos/samluiz/gyst/releases/latest"

class DesktopAppUpdateService : AppUpdateService {
    private val json = Json { ignoreUnknownKeys = true }
    private val internal = MutableStateFlow(
        AppUpdateState(
            isAvailable = true,
            currentVersion = BuildInfo.VERSION_NAME,
        )
    )
    override val state: StateFlow<AppUpdateState> = internal.asStateFlow()

    override suspend fun checkForUpdates(silent: Boolean) {
        if (internal.value.isChecking) return
        internal.update { it.copy(isChecking = true, lastError = null) }
        withContext(Dispatchers.IO) {
            runCatching {
                val root = json.parseToJsonElement(requestText(resolveUpdateApiUrl())).jsonObject
                val latestVersion = root["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
                val releasePageUrl = root["html_url"]?.jsonPrimitive?.content
                val publishedAt = root["published_at"]?.jsonPrimitive?.content
                val notes = root["body"]?.jsonPrimitive?.content
                val asset = pickDesktopAsset(root)
                val isUpdateAvailable = latestVersion.isNotBlank() &&
                    compareSemVer(latestVersion, BuildInfo.VERSION_NAME) > 0
                internal.update {
                    it.copy(
                        isChecking = false,
                        latestVersion = latestVersion.ifBlank { null },
                        isUpdateAvailable = isUpdateAvailable,
                        downloadUrl = asset?.first ?: releasePageUrl,
                        downloadName = asset?.second,
                        releasePageUrl = releasePageUrl,
                        releasedAtIso = publishedAt,
                        lastCheckedAtIso = Clock.System.now().toString(),
                        notes = notes,
                        lastError = null,
                    )
                }
            }.onFailure { error ->
                internal.update {
                    it.copy(
                        isChecking = false,
                        lastCheckedAtIso = Clock.System.now().toString(),
                        lastError = if (silent) null else (error.message ?: "Update check failed"),
                    )
                }
            }
        }
    }

    override suspend fun openUpdate() {
        val url = state.value.downloadUrl ?: state.value.releasePageUrl ?: return
        withContext(Dispatchers.IO) {
            if (!Desktop.isDesktopSupported()) return@withContext
            Desktop.getDesktop().browse(URI(url))
        }
    }

    private fun resolveUpdateApiUrl(): String = BuildInfo.UPDATE_API_URL.ifBlank { DEFAULT_UPDATE_API_URL }

    private fun pickDesktopAsset(root: kotlinx.serialization.json.JsonObject): Pair<String, String>? {
        val os = System.getProperty("os.name").lowercase()
        val assets = root["assets"]?.jsonArray.orEmpty()
        val scored = assets.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val score = when {
                os.contains("win") && name.endsWith(".msi", true) -> 100
                os.contains("win") && name.endsWith(".exe", true) -> 90
                os.contains("win") && name.contains("windows-portable", true) -> 80
                os.contains("linux") && name.endsWith(".deb", true) -> 100
                os.contains("linux") && name.contains("linux-portable", true) -> 80
                os.contains("mac") && name.endsWith(".dmg", true) -> 100
                os.contains("mac") && name.contains("macos-portable", true) -> 80
                else -> 0
            }
            if (score <= 0 || !url.startsWith("https://")) return@mapNotNull null
            Triple(url, name, score)
        }
        val best = scored.maxByOrNull { it.third } ?: return null
        return best.first to best.second
    }

    private fun requestText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "gyst-desktop-updater")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Update API error ($code)")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

