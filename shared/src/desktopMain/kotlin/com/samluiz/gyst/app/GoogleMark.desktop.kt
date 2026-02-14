package com.samluiz.gyst.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gyst.shared.generated.resources.Res
import gyst.shared.generated.resources.google_g_icon_png
import org.jetbrains.compose.resources.painterResource

@Composable
actual fun GoogleMark(modifier: Modifier) {
    Image(
        painter = painterResource(Res.drawable.google_g_icon_png),
        contentDescription = "Google",
        modifier = modifier.size(16.dp),
    )
}
