package com.samluiz.gyst.android.detection

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.samluiz.gyst.android.R

enum class ApplicationNotificationPermissionState {
    NOT_REQUIRED,
    GRANTED,
    DENIED_CAN_REQUEST,
    DENIED_PERMANENTLY,
}

data class DetectionPermissionSnapshot(
    val notificationListenerAccessGranted: Boolean,
    val applicationNotificationsEnabled: Boolean,
    val applicationNotificationPermission: ApplicationNotificationPermissionState,
)

interface DetectionPermissionController {
    fun snapshot(
        permissionWasRequestedBefore: Boolean = false,
        shouldShowRationale: Boolean? = null,
    ): DetectionPermissionSnapshot

    fun openNotificationListenerSettings(): Boolean

    fun openApplicationNotificationSettings(): Boolean

    fun requestListenerRebind()
}

class AndroidDetectionPermissionGateway(
    context: Context,
) : DetectionPermissionController {
    private val appContext = context.applicationContext
    private val listenerComponent =
        ComponentName(appContext, GystNotificationListenerService::class.java)

    fun notificationListenerAccessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            appContext
                .getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listenerComponent)
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)
        }

    fun applicationNotificationPermissionState(
        permissionWasRequestedBefore: Boolean = false,
        shouldShowRationale: Boolean? = null,
    ): ApplicationNotificationPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ApplicationNotificationPermissionState.NOT_REQUIRED
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return ApplicationNotificationPermissionState.GRANTED
        }
        return if (!permissionWasRequestedBefore || shouldShowRationale == true) {
            ApplicationNotificationPermissionState.DENIED_CAN_REQUEST
        } else {
            ApplicationNotificationPermissionState.DENIED_PERMANENTLY
        }
    }

    override fun snapshot(
        permissionWasRequestedBefore: Boolean,
        shouldShowRationale: Boolean?,
    ): DetectionPermissionSnapshot =
        DetectionPermissionSnapshot(
            notificationListenerAccessGranted = notificationListenerAccessGranted(),
            applicationNotificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            applicationNotificationPermission =
                applicationNotificationPermissionState(permissionWasRequestedBefore, shouldShowRationale),
        )

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationAccessExplanation(): String = appContext.getString(R.string.notification_access_explanation)

    fun applicationNotificationPermissionExplanation(): String = appContext.getString(R.string.post_notifications_explanation)

    fun applicationNotificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(LEGACY_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(LEGACY_APP_PACKAGE_EXTRA, appContext.packageName)
                putExtra(LEGACY_APP_UID_EXTRA, appContext.applicationInfo.uid)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    override fun openNotificationListenerSettings(): Boolean =
        runCatching {
            appContext.startActivity(notificationListenerSettingsIntent())
            true
        }.getOrDefault(false)

    override fun openApplicationNotificationSettings(): Boolean =
        runCatching {
            appContext.startActivity(applicationNotificationSettingsIntent())
            true
        }.getOrDefault(false)

    override fun requestListenerRebind() {
        NotificationListenerServiceRebind.request(listenerComponent)
    }

    companion object {
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        private const val LEGACY_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS"
        private const val LEGACY_APP_PACKAGE_EXTRA = "app_package"
        private const val LEGACY_APP_UID_EXTRA = "app_uid"
    }
}

private object NotificationListenerServiceRebind {
    fun request(componentName: ComponentName) {
        android.service.notification.NotificationListenerService.requestRebind(componentName)
    }
}
