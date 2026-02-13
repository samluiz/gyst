package com.samluiz.gyst.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
expect fun rememberRemoteProfileImage(photoUrl: String?): ImageBitmap?
