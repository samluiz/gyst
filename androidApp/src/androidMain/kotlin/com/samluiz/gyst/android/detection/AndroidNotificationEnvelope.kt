package com.samluiz.gyst.android.detection

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import com.samluiz.gyst.domain.model.sha256

data class AndroidNotificationIdentity(
    val sourcePackage: String,
    val notificationKey: String,
    val notificationId: Int,
    val notificationTag: String?,
)

/** Minimum data needed by the local-first financial-notification pipeline. */
data class AndroidNotificationEnvelope(
    val identity: AndroidNotificationIdentity,
    val postedAtEpochMillis: Long,
    val title: String,
    val text: String,
    val expandedText: String?,
    val category: String?,
    val channelId: String?,
    val isOngoing: Boolean,
)

object AndroidNotificationEnvelopeExtractor {
    fun extract(statusBarNotification: StatusBarNotification): AndroidNotificationEnvelope? {
        val notification = statusBarNotification.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (notification.category == Notification.CATEGORY_CALL) return null
        if (notification.category == Notification.CATEGORY_MESSAGE) return null
        if (notification.category == Notification.CATEGORY_TRANSPORT) return null

        val title = NotificationTextSafety.normalize(notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val text = NotificationTextSafety.normalize(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        val expanded =
            NotificationTextSafety.normalize(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                .takeIf(String::isNotBlank)

        if (title.isBlank() && text.isBlank() && expanded.isNullOrBlank()) return null
        if (NotificationTextSafety.containsAuthenticationCode(title, text, expanded.orEmpty())) return null

        return AndroidNotificationEnvelope(
            identity = identity(statusBarNotification),
            postedAtEpochMillis = statusBarNotification.postTime,
            title = NotificationTextSafety.redactLongNumbers(title),
            text = NotificationTextSafety.redactLongNumbers(text),
            expandedText = expanded?.let(NotificationTextSafety::redactLongNumbers),
            category = notification.category?.take(NotificationTextSafety.MAX_METADATA_LENGTH),
            channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notification.channelId?.take(NotificationTextSafety.MAX_METADATA_LENGTH)
                } else {
                    null
                },
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    fun identity(statusBarNotification: StatusBarNotification): AndroidNotificationIdentity =
        AndroidNotificationIdentity(
            sourcePackage = statusBarNotification.packageName,
            notificationKey = statusBarNotification.key,
            notificationId = statusBarNotification.id,
            notificationTag = statusBarNotification.tag?.take(NotificationTextSafety.MAX_METADATA_LENGTH),
        )
}

/** Identical callbacks converge, while a meaningful update to the same Android notification is re-evaluated. */
object AndroidNotificationFingerprint {
    fun create(envelope: AndroidNotificationEnvelope): String =
        sha256(
            listOf(
                envelope.identity.sourcePackage,
                envelope.identity.notificationKey,
                envelope.identity.notificationId.toString(),
                envelope.identity.notificationTag.orEmpty(),
                envelope.title,
                envelope.text,
                envelope.expandedText.orEmpty(),
                envelope.category.orEmpty(),
                envelope.channelId.orEmpty(),
            ).joinToString("|").encodeToByteArray(),
        )
}

/** Pure text handling kept independent from the notification-listener lifecycle. */
object NotificationTextSafety {
    internal const val MAX_TEXT_LENGTH = 700
    internal const val MAX_METADATA_LENGTH = 120

    private val whitespace = Regex("\\s+")
    private val controlCharacters = Regex("[\\p{Cc}&&[^\\n\\t]]")
    private val longDigitSequence = Regex("(?<!\\d)(?:\\d[ .-]?){8,}\\d(?!\\d)")
    private val authenticationTerms =
        Regex(
            pattern =
                "(?:otp|one[ -]?time|verification code|security code|access code|passcode|" +
                    "c[oó]digo (?:de )?(?:verifica[cç][aã]o|seguran[cç]a|acesso)|" +
                    "senha tempor[aá]ria|token de acesso|n[aã]o compartilhe)",
            option = RegexOption.IGNORE_CASE,
        )
    private val shortNumericCode = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun normalize(value: CharSequence?): String =
        value
            ?.toString()
            .orEmpty()
            .replace(controlCharacters, " ")
            .replace(whitespace, " ")
            .trim()
            .take(MAX_TEXT_LENGTH)

    fun containsAuthenticationCode(vararg parts: String): Boolean {
        val combined = parts.joinToString(separator = " ").take(MAX_TEXT_LENGTH * 2)
        return authenticationTerms.containsMatchIn(combined) && shortNumericCode.containsMatchIn(combined)
    }

    /** Keeps the last four digits so users can still distinguish a card without exposing it. */
    fun redactLongNumbers(value: String): String =
        longDigitSequence.replace(value) { match ->
            val digits = match.value.filter(Char::isDigit)
            "•••• ${digits.takeLast(4)}"
        }
}
