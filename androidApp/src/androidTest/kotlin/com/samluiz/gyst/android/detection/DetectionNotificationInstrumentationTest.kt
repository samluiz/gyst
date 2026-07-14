package com.samluiz.gyst.android.detection

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectionNotificationInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun detectionChannelHasStablePrivateConfiguration() {
        DetectedTransactionNotifier(context).ensureChannel()

        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(DetectedTransactionNotifier.CHANNEL_ID)

        assertNotNull(channel)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun reviewIntentTargetsTheExactSuggestionAndActivity() {
        val suggestionId = "suggestion-android-test"
        val intent = TransactionSuggestionDeepLink.createIntent(context, suggestionId)

        assertNotNull(intent)
        assertEquals(suggestionId, TransactionSuggestionDeepLink.parse(intent))
        assertTrue(intent?.component?.className?.endsWith("MainActivity") == true)
    }

    @Test
    fun unsafeSuggestionCannotCreateAReviewIntent() {
        assertNull(TransactionSuggestionDeepLink.createIntent(context, "../../unsafe id"))
    }

    @Test
    fun notificationListenerUsesThePlatformPermissionContract() {
        val serviceInfo =
            context.packageManager.getServiceInfo(
                ComponentName(context, GystNotificationListenerService::class.java),
                0,
            )

        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, serviceInfo.permission)
        assertTrue(serviceInfo.exported)
    }

    @Test
    fun notificationAccessAndAppNotificationSettingsAreDistinct() {
        val gateway = AndroidDetectionPermissionGateway(context)
        val listenerIntent = gateway.notificationListenerSettingsIntent()
        val applicationIntent = gateway.applicationNotificationSettingsIntent()

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, listenerIntent.action)
        assertTrue(listenerIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, applicationIntent.action)
            assertEquals(context.packageName, applicationIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
        assertTrue(applicationIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun transientNotificationContentIsEncryptedAtRest() {
        val protector = AndroidKeystoreNotificationContentProtector()
        val source = "Compra de R$ 42,90 aprovada"

        val protected = protector.protect(source)

        assertTrue(!protected.contains(source))
        assertEquals(source, protector.reveal(protected))
    }

    @Test
    fun launchableApplicationCatalogExcludesGystWithoutQueryAllPackages() {
        val applications = AndroidInstalledApplicationCatalog(context).listLaunchableApplications()

        assertTrue(applications.none { it.packageName == context.packageName })
    }
}
