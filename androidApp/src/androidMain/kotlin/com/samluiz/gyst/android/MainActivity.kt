package com.samluiz.gyst.android

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            navigationBarStyle =
                SystemBarStyle.auto(
                    lightScrim = Color.TRANSPARENT,
                    darkScrim = Color.TRANSPARENT,
                ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        installAndroidLogging()
        initKoin(platformModule = androidPlatformModule(applicationContext))
        val googleService = GlobalContext.get().get<GoogleAuthSyncService>()
        if (googleService is AndroidGoogleAuthSyncService) {
            googleService.bind(this)
        }
        setContent {
            GystRoot()
        }
    }
}
