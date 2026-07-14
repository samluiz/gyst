package com.samluiz.gyst.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.time.Clock

private const val DEFAULT_UPDATE_API_URL = "https://api.github.com/repos/samluiz/gyst/releases/latest"

class AndroidAppUpdateService(
    private val context: Context,
) : AppUpdateService {
    private var downloadedApk: File? = null
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

    override suspend fun startUpdate() {
        if (internal.value.isDownloading) return

        downloadedApk?.takeIf(File::isFile)?.let { apk ->
            launchInstaller(apk)
            return
        }

        val update = state.value
        val url = update.downloadUrl ?: update.releasePageUrl ?: return
        if (update.downloadName?.endsWith(".apk", ignoreCase = true) != true || !url.startsWith("https://")) {
            openExternalUrl(url)
            return
        }

        internal.update {
            it.copy(
                isDownloading = true,
                downloadProgressPercent = 0,
                isUpdateDownloaded = false,
                requiresInstallPermission = false,
                lastError = null,
            )
        }
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    downloadAndVerifyApk(
                        url = url,
                        version = update.latestVersion.orEmpty(),
                    )
                }
            }
        result.onSuccess { apk ->
            downloadedApk = apk
            internal.update {
                it.copy(
                    isDownloading = false,
                    downloadProgressPercent = 100,
                    isUpdateDownloaded = true,
                    lastError = null,
                )
            }
            launchInstaller(apk)
        }.onFailure { error ->
            internal.update {
                it.copy(
                    isDownloading = false,
                    downloadProgressPercent = null,
                    isUpdateDownloaded = false,
                    lastError = error.message ?: "Update download failed",
                )
            }
        }
    }

    private suspend fun openExternalUrl(url: String) {
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    private suspend fun launchInstaller(apk: File) {
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                internal.update { it.copy(requiresInstallPermission = true) }
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri(),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                return@withContext
            }

            internal.update { it.copy(requiresInstallPermission = false) }
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }

    private fun downloadAndVerifyApk(
        url: String,
        version: String,
    ): File {
        check(version.isNotBlank()) { "Update version is missing" }
        val updateDirectory = File(context.cacheDir, "updates")
        check(updateDirectory.isDirectory || updateDirectory.mkdirs()) { "Could not create the update directory" }
        val target = File(updateDirectory, "gyst-update.apk")
        val partial = File(updateDirectory, "${target.name}.part")
        partial.delete()

        try {
            downloadFile(url, partial)
            check(partial.length() > 0L) { "Downloaded update is empty" }
            verifyApk(partial)
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Could not finalize downloaded update" }
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
    ) {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", APK_MIME_TYPE)
                setRequestProperty("User-Agent", "gyst-android-updater")
            }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "Update download failed ($code)" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            check(totalBytes == null || totalBytes <= MAX_APK_BYTES) { "Update file is unexpectedly large" }
            var downloadedBytes = 0L
            var lastProgress = -1
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        check(downloadedBytes <= MAX_APK_BYTES) { "Update file is unexpectedly large" }
                        val progress = totalBytes?.let { ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 99) }
                        if (progress != null && progress != lastProgress) {
                            lastProgress = progress
                            internal.update { it.copy(downloadProgressPercent = progress) }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyApk(apk: File) {
        val packageManager = context.packageManager
        val flags = signingCertificateFlags()

        @Suppress("DEPRECATION")
        val archive =
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
                ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update package does not match Gyst" }
        check(compareSemVer(archive.versionName.orEmpty(), BuildInfo.VERSION_NAME) > 0) {
            "Downloaded APK is not newer than the installed version"
        }
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        check(signingDigests(archive) == signingDigests(installed)) { "Update signature does not match Gyst" }
    }

    private fun signingCertificateFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signingDigests(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = requireNotNull(info.signingInfo) { "APK signing information is missing" }
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            } else {
                info.signatures.orEmpty()
            }
        return signatures
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.toSet()
            .also { check(it.isNotEmpty()) { "APK signing certificate is missing" } }
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

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val MAX_APK_BYTES = 250L * 1024L * 1024L
