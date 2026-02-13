package com.samluiz.gyst.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.di.initKoin

fun initGystIos() {
    initKoin(platformModule = iosPlatformModule())
}

fun GystMainViewController() = ComposeUIViewController { GystRoot() }

