package com.samluiz.gyst.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
internal expect fun rememberInstalledApplicationIcon(packageName: String): ImageBitmap?
