package com.samluiz.gyst.app

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
actual fun rememberRemoteProfileImage(photoUrl: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = photoUrl) {
        value = null
        if (photoUrl.isNullOrBlank()) return@produceState
        value = runCatching {
            withContext(Dispatchers.IO) {
                URL(photoUrl).openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        }.getOrNull()
    }
    return state.value
}
