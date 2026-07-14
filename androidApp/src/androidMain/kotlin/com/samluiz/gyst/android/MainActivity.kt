package com.samluiz.gyst.android

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.samluiz.gyst.android.detection.TransactionSuggestionDeepLink
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.presentation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    private lateinit var appNavigator: AppNavigator
    private lateinit var imageImportService: ImageImportService
    private var automaticDetectionService: AutomaticTransactionDetectionService? = null
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            automaticDetectionService?.onApplicationNotificationPermissionResult(granted)
        }

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
        val koin = GlobalContext.get()
        appNavigator = koin.get()
        imageImportService = koin.get()
        val googleService = koin.get<GoogleAuthSyncService>()
        if (googleService is AndroidGoogleAuthSyncService) {
            googleService.bind(this)
        }
        koin.getOrNull<AndroidImageSourceService>()?.bind(this)
        automaticDetectionService = koin.getOrNull()
        handleLaunchIntent(intent)
        setContent {
            GystRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        automaticDetectionService?.bindApplicationNotificationPermissionRequester(
            requester = { permission -> notificationPermissionLauncher.launch(permission) },
            shouldShowRationale = {
                shouldShowRequestPermissionRationale(
                    com.samluiz.gyst.android.detection.AndroidDetectionPermissionGateway.POST_NOTIFICATIONS_PERMISSION,
                )
            },
        )
        lifecycleScope.launch(Dispatchers.Default) { automaticDetectionService?.refresh() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.Default) { imageImportService.recoverPendingSources() }
    }

    override fun onStop() {
        automaticDetectionService?.bindApplicationNotificationPermissionRequester(null, null)
        super.onStop()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        TransactionSuggestionDeepLink.parse(intent)?.let(appNavigator::reviewSuggestion)
    }
}
