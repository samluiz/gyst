package com.samluiz.gyst.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin(platformModule = iosPlatformModule())
    return ComposeUIViewController { GystRoot() }
}
