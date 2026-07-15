package com.samluiz.gyst.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val installedApplicationIconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
internal actual fun rememberInstalledApplicationIcon(packageName: String): ImageBitmap? {
    val packageManager = LocalContext.current.packageManager
    return produceState<ImageBitmap?>(initialValue = installedApplicationIconCache[packageName], packageName) {
        if (value != null) return@produceState
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    packageManager.getApplicationIcon(packageName)
                        .toBitmap(width = APPLICATION_ICON_SIZE_PX, height = APPLICATION_ICON_SIZE_PX)
                        .asImageBitmap()
                        .also { installedApplicationIconCache[packageName] = it }
                }.getOrNull()
            }
    }.value
}

private const val APPLICATION_ICON_SIZE_PX = 96
