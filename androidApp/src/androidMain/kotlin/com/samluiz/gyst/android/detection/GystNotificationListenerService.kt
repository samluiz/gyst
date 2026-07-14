package com.samluiz.gyst.android.detection

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.samluiz.gyst.logging.AppLogger

class GystNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AndroidDetectionRuntime.current().lifecycleObserver.onListenerConnected()
        activeNotifications.orEmpty().forEach(::onNotificationPosted)
        AppLogger.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        AndroidDetectionRuntime.current().lifecycleObserver.onListenerDisconnected()
        AppLogger.w(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        val posted = statusBarNotification ?: return
        val ingress = AndroidDetectionRuntime.current().ingress
        if (!ingress.shouldCollect(posted.packageName)) return

        val envelope = AndroidNotificationEnvelopeExtractor.extract(posted) ?: return
        runCatching { ingress.onPosted(envelope) }
            .onFailure { error ->
                // Deliberately omit notification content and identifiers from logs.
                AppLogger.e(TAG, "Failed to hand off an eligible notification (${error::class.simpleName})")
            }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification?) {
        val removed = statusBarNotification ?: return
        val ingress = AndroidDetectionRuntime.current().ingress
        if (!ingress.shouldCollect(removed.packageName)) return

        runCatching { ingress.onRemoved(AndroidNotificationEnvelopeExtractor.identity(removed)) }
            .onFailure { error ->
                AppLogger.e(TAG, "Failed to hand off a removed-notification marker (${error::class.simpleName})")
            }
    }

    private companion object {
        const val TAG = "NotificationListener"
    }
}
