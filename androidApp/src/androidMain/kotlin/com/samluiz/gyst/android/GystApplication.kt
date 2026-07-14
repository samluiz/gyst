package com.samluiz.gyst.android

import android.app.Application
import com.samluiz.gyst.android.detection.AndroidDetectionRuntimeInitializer
import com.samluiz.gyst.android.detection.DetectedTransactionNotifier
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.logging.AppLogger
import org.koin.core.context.GlobalContext

class GystApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installAndroidLogging()
        initKoin(platformModule = androidPlatformModule(this))

        runCatching {
            GlobalContext.get().getOrNull<AndroidDetectionRuntimeInitializer>()?.initialize(this)
        }.onFailure { error ->
            AppLogger.e(
                TAG,
                "Automatic transaction detection initialization failed (${error::class.simpleName})",
            )
        }
        GlobalContext.get().get<DetectedTransactionNotifier>().ensureChannel()
    }

    private companion object {
        const val TAG = "GystApplication"
    }
}
