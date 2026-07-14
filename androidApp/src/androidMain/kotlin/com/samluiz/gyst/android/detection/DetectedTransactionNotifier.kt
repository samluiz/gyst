package com.samluiz.gyst.android.detection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.samluiz.gyst.android.R
import java.security.MessageDigest

data class DetectedTransactionNotification(
    val suggestionId: String,
    val formattedAmount: String? = null,
    val merchantOrDescription: String? = null,
)

enum class DetectionNotificationDelivery {
    SENT,
    INVALID_SUGGESTION_ID,
    PERMISSION_DENIED,
    APP_NOTIFICATIONS_DISABLED,
    DELIVERY_FAILED,
}

interface DetectedTransactionNotificationSink {
    fun notify(request: DetectedTransactionNotification): DetectionNotificationDelivery

    fun cancel(suggestionId: String)
}

class DetectedTransactionNotifier(
    context: Context,
) : DetectedTransactionNotificationSink {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = appContext.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.transaction_detection_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.transaction_detection_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        systemManager.createNotificationChannel(channel)
    }

    override fun notify(request: DetectedTransactionNotification): DetectionNotificationDelivery {
        if (!NotificationAnalysisWorkContract.isValidSuggestionId(request.suggestionId)) {
            return DetectionNotificationDelivery.INVALID_SUGGESTION_ID
        }
        if (!hasPostNotificationsPermission()) {
            return DetectionNotificationDelivery.PERMISSION_DENIED
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return DetectionNotificationDelivery.APP_NOTIFICATIONS_DISABLED
        }

        val reviewIntent =
            TransactionSuggestionDeepLink.createIntent(appContext, request.suggestionId)
                ?: return DetectionNotificationDelivery.INVALID_SUGGESTION_ID
        val notificationId = stableNotificationId(request.suggestionId)
        val pendingIntent =
            PendingIntent.getActivity(
                appContext,
                notificationId,
                reviewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val body = buildPrivateBody(request)
        val publicVersion =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_transaction)
                .setContentTitle(appContext.getString(R.string.transaction_detected_title))
                .setContentText(appContext.getString(R.string.transaction_detected_public_body))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_transaction)
                .setContentTitle(appContext.getString(R.string.transaction_detected_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_notification_transaction,
                    appContext.getString(R.string.transaction_detected_review_action),
                    pendingIntent,
                ).setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .build()

        return try {
            ensureChannel()
            notificationManager.notify(notificationId, notification)
            DetectionNotificationDelivery.SENT
        } catch (_: SecurityException) {
            DetectionNotificationDelivery.PERMISSION_DENIED
        } catch (_: RuntimeException) {
            DetectionNotificationDelivery.DELIVERY_FAILED
        }
    }

    override fun cancel(suggestionId: String) {
        if (!NotificationAnalysisWorkContract.isValidSuggestionId(suggestionId)) return
        notificationManager.cancel(stableNotificationId(suggestionId))
    }

    private fun hasPostNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildPrivateBody(request: DetectedTransactionNotification): String {
        val amount = request.formattedAmount?.let(NotificationDisplaySafety::safeOneLine)
        val merchant = request.merchantOrDescription?.let(NotificationDisplaySafety::safeOneLine)
        return when {
            !amount.isNullOrBlank() && !merchant.isNullOrBlank() ->
                appContext.getString(R.string.transaction_detected_body_with_merchant, amount, merchant)

            !amount.isNullOrBlank() ->
                appContext.getString(R.string.transaction_detected_body_with_amount, amount)

            else -> appContext.getString(R.string.transaction_detected_body_generic)
        }
    }

    companion object {
        const val CHANNEL_ID = "transaction_detections"

        fun stableNotificationId(suggestionId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(suggestionId.encodeToByteArray())
            val value =
                (digest[0].toInt() and 0xff shl 24) or
                    (digest[1].toInt() and 0xff shl 16) or
                    (digest[2].toInt() and 0xff shl 8) or
                    (digest[3].toInt() and 0xff)
            return value and Int.MAX_VALUE
        }
    }
}

object NotificationDisplaySafety {
    private val whitespace = Regex("\\s+")

    fun safeOneLine(value: String): String =
        NotificationTextSafety
            .redactLongNumbers(value)
            .replace(whitespace, " ")
            .trim()
            .take(72)
}
