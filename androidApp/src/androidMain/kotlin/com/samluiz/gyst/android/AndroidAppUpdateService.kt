package com.samluiz.gyst.android

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Clock

private const val DEFAULT_UPDATE_API_URL = "https://api.github.com/repos/samluiz/gyst/releases/latest"

class AndroidAppUpdateService(
    private val context: Context,
) : AppUpdateService {
    private val internal =
        MutableStateFlow(
            AppUpdateState(
                isAvailable = true,
                currentVersion = BuildInfo.VERSION_NAME,
            ),
        )
    override val state: StateFlow<AppUpdateState> = internal.asStateFlow()

    override suspend fun checkForUpdates(silent: Boolean) {
        if (internal.value.isChecking) return
        internal.update { it.copy(isChecking = true, lastError = null) }
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(requestText(resolveUpdateApiUrl()))
                val latestVersion = root.optString("tag_name").removePrefix("v")
                val releasePageUrl = root.optString("html_url").takeIf { it.isNotBlank() }
                val publishedAt = root.optString("published_at").takeIf { it.isNotBlank() }
                val notes = root.optString("body").takeIf { it.isNotBlank() }
                val asset = pickAndroidAsset(root)
                val isUpdateAvailable =
                    latestVersion.isNotBlank() &&
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
        withContext(Dispatchers.Main) {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }
    }

    private fun resolveUpdateApiUrl(): String = BuildInfo.UPDATE_API_URL.ifBlank { DEFAULT_UPDATE_API_URL }

    private fun pickAndroidAsset(root: JSONObject): Pair<String, String>? {
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                return url to name
            }
        }
        return null
    }

    private fun requestText(url: String): String {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "gyst-android-updater")
            }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Update API error ($code)")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
