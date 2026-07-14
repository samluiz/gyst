package com.samluiz.gyst.android.detection

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.samluiz.gyst.android.MainActivity
import java.net.URI

object TransactionSuggestionDeepLink {
    const val EXTRA_SUGGESTION_ID = "com.samluiz.gyst.extra.TRANSACTION_SUGGESTION_ID"

    private const val SCHEME = "gyst"
    private const val HOST = "transaction-suggestions"
    private const val REVIEW_SEGMENT = "review"

    fun uriString(suggestionId: String): String? =
        suggestionId
            .takeIf(NotificationAnalysisWorkContract::isValidSuggestionId)
            ?.let { "$SCHEME://$HOST/$REVIEW_SEGMENT/$it" }

    fun parse(rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return null
        val parsed = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!parsed.host.equals(HOST, ignoreCase = true)) return null
        val segments = parsed.path.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.size != 2 || segments.first() != REVIEW_SEGMENT) return null
        return segments.last().takeIf(NotificationAnalysisWorkContract::isValidSuggestionId)
    }

    fun parse(intent: Intent?): String? =
        parse(intent?.dataString)
            ?: intent
                ?.getStringExtra(EXTRA_SUGGESTION_ID)
                ?.takeIf(NotificationAnalysisWorkContract::isValidSuggestionId)

    fun createIntent(
        context: Context,
        suggestionId: String,
    ): Intent? {
        val route = uriString(suggestionId) ?: return null
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = route.toUri()
            putExtra(EXTRA_SUGGESTION_ID, suggestionId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}
