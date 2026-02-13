package com.samluiz.gyst.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
