package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.MonitoredApplicationPolicy
import com.samluiz.gyst.domain.model.ProviderProfile
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.repository.CandidateApprovalFailure
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stable settings keys shared by the Android runtime and the common settings UI. */
object AutomaticDetectionSettingKeys {
    const val ENABLED = "automatic_detection.enabled"
    const val AI_ANALYSIS_ENABLED = "automatic_detection.ai_analysis_enabled"
    const val USER_NOTIFICATIONS_ENABLED = "automatic_detection.user_notifications_enabled"
    const val PROVIDER_PROFILE_ID = "automatic_detection.provider_profile_id"
    const val POST_NOTIFICATIONS_REQUESTED = "automatic_detection.post_notifications_requested"

    fun notificationDelivery(candidateId: String): String = "automatic_detection.notification_delivery.$candidateId"
}

enum class DetectionPermissionState {
    NOT_REQUIRED,
    GRANTED,
    DENIED_CAN_REQUEST,
    DENIED_PERMANENTLY,
}

enum class DetectionServiceError {
    NOTIFICATION_ACCESS_DENIED,
    APPLICATION_NOTIFICATION_PERMISSION_DENIED,
    MISSING_PROVIDER_CONFIGURATION,
    MISSING_PROVIDER_CREDENTIAL,
    UNSUPPORTED_PROVIDER_CAPABILITY,
    DATABASE,
    PLATFORM,
}

data class DetectionApplication(
    val packageName: String,
    val displayName: String,
    val policy: MonitoredApplicationPolicy,
    val enabled: Boolean,
)

data class AutomaticTransactionDetectionState(
    val isSupported: Boolean = false,
    val initialized: Boolean = false,
    val enabled: Boolean = false,
    val aiAnalysisEnabled: Boolean = false,
    val userNotificationsEnabled: Boolean = true,
    val providerProfileId: String? = null,
    val notificationListenerAccessGranted: Boolean = false,
    val applicationNotificationsEnabled: Boolean = false,
    val applicationNotificationPermission: DetectionPermissionState = DetectionPermissionState.NOT_REQUIRED,
    val listenerConnected: Boolean = false,
    val monitoredApplications: List<DetectionApplication> = emptyList(),
    val compatibleProviderProfiles: List<ProviderProfile> = emptyList(),
    val pendingSuggestions: List<TransactionCandidate> = emptyList(),
    val failedAnalysisCount: Int = 0,
    val lastError: DetectionServiceError? = null,
)

/**
 * Android's automatic detection surface. Other platforms may expose an unsupported implementation;
 * shared UI and domain code never depend on Android framework types.
 */
interface AutomaticTransactionDetectionService {
    val state: StateFlow<AutomaticTransactionDetectionState>

    suspend fun initialize()

    /** Quiesces listener and worker writes before the database file is atomically replaced. */
    suspend fun suspendProcessingForDatabaseReplacement()

    suspend fun refresh()

    suspend fun installedApplications(): List<DetectionApplication>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setAiAnalysisEnabled(enabled: Boolean)

    suspend fun setUserNotificationsEnabled(enabled: Boolean)

    suspend fun selectProviderProfile(profileId: String?)

    suspend fun setApplicationPolicy(
        packageName: String,
        displayName: String,
        policy: MonitoredApplicationPolicy,
        enabled: Boolean,
    )

    suspend fun updateSuggestion(candidate: TransactionCandidate)

    /** Loads one durable notification suggestion, including already-reviewed records. */
    suspend fun suggestion(id: String): TransactionCandidate?

    suspend fun approveSuggestion(candidateId: String): CandidateApprovalResult

    suspend fun rejectSuggestion(candidateId: String): Boolean

    suspend fun deleteSuggestion(candidateId: String): Boolean

    suspend fun retryFailedAnalyses()

    suspend fun deleteNotificationDerivedData()

    /** Activity hosts bind the contextual Android 13 launcher and rationale state without leaking Activity types. */
    fun bindApplicationNotificationPermissionRequester(
        requester: ((String) -> Unit)?,
        shouldShowRationale: (() -> Boolean)?,
    )

    /** Returns true only when a bound launcher was invoked. It never prompts on its own. */
    fun requestApplicationNotificationPermission(): Boolean

    fun onApplicationNotificationPermissionResult(granted: Boolean)

    fun openNotificationAccessSettings(): Boolean

    fun openApplicationNotificationSettings(): Boolean
}

class UnsupportedAutomaticTransactionDetectionService : AutomaticTransactionDetectionService {
    override val state: StateFlow<AutomaticTransactionDetectionState> =
        MutableStateFlow(AutomaticTransactionDetectionState(initialized = true))

    override suspend fun initialize() = Unit

    override suspend fun suspendProcessingForDatabaseReplacement() = Unit

    override suspend fun refresh() = Unit

    override suspend fun installedApplications(): List<DetectionApplication> = emptyList()

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun setAiAnalysisEnabled(enabled: Boolean) = Unit

    override suspend fun setUserNotificationsEnabled(enabled: Boolean) = Unit

    override suspend fun selectProviderProfile(profileId: String?) = Unit

    override suspend fun setApplicationPolicy(
        packageName: String,
        displayName: String,
        policy: MonitoredApplicationPolicy,
        enabled: Boolean,
    ) = Unit

    override suspend fun updateSuggestion(candidate: TransactionCandidate) = Unit

    override suspend fun suggestion(id: String): TransactionCandidate? = null

    override suspend fun approveSuggestion(candidateId: String): CandidateApprovalResult =
        CandidateApprovalResult.Rejected(CandidateApprovalFailure.INVALID_STATE)

    override suspend fun rejectSuggestion(candidateId: String): Boolean = false

    override suspend fun deleteSuggestion(candidateId: String): Boolean = false

    override suspend fun retryFailedAnalyses() = Unit

    override suspend fun deleteNotificationDerivedData() = Unit

    override fun bindApplicationNotificationPermissionRequester(
        requester: ((String) -> Unit)?,
        shouldShowRationale: (() -> Boolean)?,
    ) = Unit

    override fun requestApplicationNotificationPermission(): Boolean = false

    override fun onApplicationNotificationPermissionResult(granted: Boolean) = Unit

    override fun openNotificationAccessSettings(): Boolean = false

    override fun openApplicationNotificationSettings(): Boolean = false
}
