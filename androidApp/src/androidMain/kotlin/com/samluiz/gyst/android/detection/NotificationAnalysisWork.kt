package com.samluiz.gyst.android.detection

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object NotificationAnalysisWorkContract {
    const val TAG = "gyst-transaction-detection-analysis"
    const val MAX_RUN_ATTEMPTS = 5
    const val BACKOFF_SECONDS = 30L
    internal const val SUGGESTION_ID_KEY = "suggestion_id"

    private const val UNIQUE_WORK_PREFIX = "gyst-transaction-analysis-"
    private val stableIdentifier = Regex("[A-Za-z0-9._-]{1,128}")

    fun isValidSuggestionId(value: String?): Boolean = value != null && stableIdentifier.matches(value)

    fun uniqueWorkName(suggestionId: String): String? =
        suggestionId
            .takeIf(::isValidSuggestionId)
            ?.let { "$UNIQUE_WORK_PREFIX$it" }
}

interface NotificationAnalysisScheduling {
    fun schedule(suggestionId: String): Boolean

    fun cancel(suggestionId: String)

    fun cancelAll()
}

class NotificationAnalysisScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) : NotificationAnalysisScheduling {
    /**
     * Schedules only provider-backed analysis. Rule-only extraction belongs in the local ingress
     * pipeline and does not need a network-constrained worker.
     */
    override fun schedule(suggestionId: String): Boolean {
        val uniqueWorkName = NotificationAnalysisWorkContract.uniqueWorkName(suggestionId) ?: return false
        val input =
            Data.Builder()
                .putString(NotificationAnalysisWorkContract.SUGGESTION_ID_KEY, suggestionId)
                .build()
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val request =
            OneTimeWorkRequestBuilder<NotificationAnalysisWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    NotificationAnalysisWorkContract.BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                ).addTag(NotificationAnalysisWorkContract.TAG)
                .build()

        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request)
        return true
    }

    override fun cancel(suggestionId: String) {
        NotificationAnalysisWorkContract.uniqueWorkName(suggestionId)?.let(workManager::cancelUniqueWork)
    }

    /** Called when detection or AI-assisted analysis is disabled. */
    override fun cancelAll() {
        workManager.cancelAllWorkByTag(NotificationAnalysisWorkContract.TAG)
    }
}

class NotificationAnalysisWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val suggestionId = inputData.getString(NotificationAnalysisWorkContract.SUGGESTION_ID_KEY)
        if (!NotificationAnalysisWorkContract.isValidSuggestionId(suggestionId)) {
            AppLogger.w(TAG, "Discarding analysis work with an invalid local identifier")
            return Result.failure()
        }
        checkNotNull(suggestionId)

        if (runAttemptCount >= NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS) {
            AppLogger.w(TAG, "Analysis retry budget exhausted")
            return Result.failure()
        }

        val outcome =
            try {
                AndroidDetectionRuntime.current().analysisRunner.analyze(
                    suggestionId = suggestionId,
                    runAttemptCount = runAttemptCount,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // The durable runner records typed details. This boundary intentionally logs no payload.
                AppLogger.e(TAG, "Notification analysis runner failed (${error::class.simpleName})")
                NotificationAnalysisOutcome.RetryableFailure
            }

        return when (outcome) {
            NotificationAnalysisOutcome.Completed -> Result.success()
            NotificationAnalysisOutcome.PermanentFailure -> Result.failure()
            NotificationAnalysisOutcome.Cancelled -> Result.failure()
            NotificationAnalysisOutcome.RetryableFailure -> {
                if (runAttemptCount + 1 >= NotificationAnalysisWorkContract.MAX_RUN_ATTEMPTS) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    private companion object {
        const val TAG = "NotificationAnalysis"
    }
}
