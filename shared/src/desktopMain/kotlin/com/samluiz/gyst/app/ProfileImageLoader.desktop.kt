package com.samluiz.gyst.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val desktopProfileImageCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
actual fun rememberRemoteProfileImage(photoUrl: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = photoUrl) {
        value = null
        val url = photoUrl?.trim().orEmpty()
        if (url.isEmpty()) return@produceState

        desktopProfileImageCache[url]?.let {
            value = it
            return@produceState
        }

        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "gyst-desktop")
                }
                connection.inputStream.use { input ->
                    val bytes = input.readBytes()
                    Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }.getOrNull()

        if (loaded != null) {
            desktopProfileImageCache[url] = loaded
        }
        value = loaded
    }
    return state.value
}
