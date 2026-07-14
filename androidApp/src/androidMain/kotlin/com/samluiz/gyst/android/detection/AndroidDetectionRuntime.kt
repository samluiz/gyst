package com.samluiz.gyst.android.detection

import android.content.Context

/**
 * Fast, process-local bridge between Android framework entry points and the durable shared
 * transaction-detection pipeline. Implementations must return from [NotificationIngress.onPosted]
 * immediately after handing the envelope to durable storage or a background dispatcher.
 */
interface NotificationIngress {
    /**
     * This check happens before notification extras are read. Implementations should use a cached
     * opt-in and source-application policy so disabled or blocked notifications are never collected.
     */
    fun shouldCollect(sourcePackage: String): Boolean

    fun onPosted(envelope: AndroidNotificationEnvelope)

    fun onRemoved(identity: AndroidNotificationIdentity)
}

interface NotificationListenerLifecycleObserver {
    fun onListenerConnected()

    fun onListenerDisconnected()
}

sealed interface NotificationAnalysisOutcome {
    data object Completed : NotificationAnalysisOutcome

    data object RetryableFailure : NotificationAnalysisOutcome

    data object PermanentFailure : NotificationAnalysisOutcome

    data object Cancelled : NotificationAnalysisOutcome
}

fun interface NotificationAnalysisRunner {
    /** Receives only a stable local identifier. Raw notification content never enters WorkManager Data. */
    suspend fun analyze(
        suggestionId: String,
        runAttemptCount: Int,
    ): NotificationAnalysisOutcome
}

/** Optional process-start hook supplied by the Android platform DI module. */
fun interface AndroidDetectionRuntimeInitializer {
    fun initialize(context: Context)
}

data class AndroidDetectionBindings(
    val ingress: NotificationIngress = DisabledNotificationIngress,
    val lifecycleObserver: NotificationListenerLifecycleObserver = NoOpLifecycleObserver,
    val analysisRunner: NotificationAnalysisRunner = UnavailableAnalysisRunner,
)

/**
 * Framework services can be created before any Activity. Keeping bindings here lets the
 * Application install the real shared adapters once and makes workers safe after process recreation.
 */
object AndroidDetectionRuntime {
    @Volatile
    private var bindings = AndroidDetectionBindings()

    fun install(newBindings: AndroidDetectionBindings) {
        bindings = newBindings
    }

    fun current(): AndroidDetectionBindings = bindings

    internal fun resetForTests() {
        bindings = AndroidDetectionBindings()
    }
}

private object DisabledNotificationIngress : NotificationIngress {
    override fun shouldCollect(sourcePackage: String): Boolean = false

    override fun onPosted(envelope: AndroidNotificationEnvelope) = Unit

    override fun onRemoved(identity: AndroidNotificationIdentity) = Unit
}

private object NoOpLifecycleObserver : NotificationListenerLifecycleObserver {
    override fun onListenerConnected() = Unit

    override fun onListenerDisconnected() = Unit
}

private object UnavailableAnalysisRunner : NotificationAnalysisRunner {
    override suspend fun analyze(
        suggestionId: String,
        runAttemptCount: Int,
    ): NotificationAnalysisOutcome = NotificationAnalysisOutcome.PermanentFailure
}
