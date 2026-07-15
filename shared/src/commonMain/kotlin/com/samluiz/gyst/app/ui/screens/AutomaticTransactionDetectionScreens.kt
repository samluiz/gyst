package com.samluiz.gyst.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.CandidateIssueCode
import com.samluiz.gyst.domain.model.CandidateStatus
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.MonitoredApplicationPolicy
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.model.validateCandidateForExpenseApproval
import com.samluiz.gyst.domain.repository.CandidateApprovalResult
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionState
import com.samluiz.gyst.domain.service.DetectionApplication
import com.samluiz.gyst.domain.service.DetectionPermissionState
import com.samluiz.gyst.domain.service.DetectionServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

@Composable
internal fun AutomaticDetectionSettingsRoute(
    s: AppStrings,
    service: AutomaticTransactionDetectionService,
    onBack: () -> Unit,
    onOpenPending: () -> Unit,
    onConfigureProvider: () -> Unit,
) {
    val state by service.state.collectAsState()
    val scope = rememberCoroutineScope()
    var installedApplications by remember { mutableStateOf(emptyList<DetectionApplication>()) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var operationError by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    suspend fun refreshAll() {
        val applications =
            withContext(Dispatchers.Default) {
                service.refresh()
                service.installedApplications()
            }
        installedApplications = applications
    }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        operationError = null
        scope.launch {
            try {
                withContext(Dispatchers.Default) { action() }
                refreshAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError = s.automaticDetection.actionFailed
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(service) {
        installedApplications =
            withContext(Dispatchers.Default) {
                service.refresh()
                service.installedApplications()
            }
    }

    AutomaticDetectionSettingsScreen(
        s = s,
        state = state,
        installedApplications = installedApplications,
        busy = busy,
        operationError = operationError,
        onBack = onBack,
        onEnabledChange = { enabled -> perform { service.setEnabled(enabled) } },
        onOpenListenerSettings = { service.openNotificationAccessSettings() },
        onRequestNotificationPermission = {
            if (!service.requestApplicationNotificationPermission()) {
                service.openApplicationNotificationSettings()
            }
        },
        onOpenNotificationSettings = { service.openApplicationNotificationSettings() },
        onUserNotificationsChange = { enabled ->
            perform {
                service.setUserNotificationsEnabled(enabled)
                if (enabled && state.applicationNotificationPermission == DetectionPermissionState.DENIED_CAN_REQUEST) {
                    service.requestApplicationNotificationPermission()
                }
            }
        },
        onApplicationEnabledChange = { application, enabled ->
            installedApplications =
                installedApplications.map {
                    if (it.packageName == application.packageName) {
                        it.copy(
                            policy = if (enabled) MonitoredApplicationPolicy.ALLOW else MonitoredApplicationPolicy.BLOCK,
                            enabled = enabled,
                        )
                    } else {
                        it
                    }
                }
            perform {
                service.setApplicationPolicy(
                    packageName = application.packageName,
                    displayName = application.displayName,
                    policy = if (enabled) MonitoredApplicationPolicy.ALLOW else MonitoredApplicationPolicy.BLOCK,
                    enabled = enabled,
                )
            }
        },
        onAiEnabledChange = { enabled -> perform { service.setAiAnalysisEnabled(enabled) } },
        onProviderSelected = { profileId -> perform { service.selectProviderProfile(profileId) } },
        onConfigureProvider = onConfigureProvider,
        onOpenPending = onOpenPending,
        onRetryFailed = { perform { service.retryFailedAnalyses() } },
        onDeleteData = { showDeleteConfirmation = true },
        onRefresh = { perform { service.refresh() } },
    )

    if (showDeleteConfirmation) {
        AppDialog(
            title = s.automaticDetection.deleteDataConfirmTitle,
            onClose = { showDeleteConfirmation = false },
            closeLabel = s.close,
            onDismissRequest = { showDeleteConfirmation = false },
        ) {
            Text(
                s.automaticDetection.deleteDataConfirmBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CompactPrimaryButton(
                    text = s.close,
                    compact = true,
                    subtle = true,
                    onClick = { showDeleteConfirmation = false },
                )
                CompactPrimaryButton(
                    text = s.automaticDetection.deleteDataAction,
                    compact = true,
                    danger = true,
                    onClick = {
                        showDeleteConfirmation = false
                        perform { service.deleteNotificationDerivedData() }
                    },
                )
            }
        }
    }
}

@Composable
private fun AutomaticDetectionSettingsScreen(
    s: AppStrings,
    state: AutomaticTransactionDetectionState,
    installedApplications: List<DetectionApplication>,
    busy: Boolean,
    operationError: String?,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onUserNotificationsChange: (Boolean) -> Unit,
    onApplicationEnabledChange: (DetectionApplication, Boolean) -> Unit,
    onAiEnabledChange: (Boolean) -> Unit,
    onProviderSelected: (String) -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenPending: () -> Unit,
    onRetryFailed: () -> Unit,
    onDeleteData: () -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = s.automaticDetection
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetectionRouteHeader(
            title = strings.settingsTitle,
            subtitle = strings.settingsSubtitle,
            backLabel = s.close,
            onBack = onBack,
            trailing = {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconCompactButton(
                        onClick = onRefresh,
                        icon = Icons.Default.Refresh,
                        contentDescription = strings.refresh,
                        compact = true,
                    )
                }
            },
        )

        if (!state.initialized) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        if (!state.isSupported) {
            DetectionEmptyState(
                icon = Icons.Default.Android,
                title = strings.unsupportedTitle,
                body = strings.unsupportedBody,
            )
            return@Column
        }

        operationError?.let {
            DetectionInlineNotice(
                icon = Icons.Default.ErrorOutline,
                text = it,
                danger = true,
            )
        }
        state.lastError?.let { error ->
            DetectionInlineNotice(
                icon = Icons.Default.ErrorOutline,
                text = detectionServiceErrorLabel(error, s),
                danger = true,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                DetectionMasterSwitch(
                    strings = strings,
                    enabled = state.enabled,
                    busy = busy,
                    onEnabledChange = onEnabledChange,
                )
            }
            item {
                DetectionSettingsSection(title = strings.listenerAccessTitle) {
                    PermissionRow(
                        title =
                            if (state.notificationListenerAccessGranted) {
                                strings.listenerGranted
                            } else {
                                strings.listenerMissing
                            },
                        body = strings.listenerAccessBody,
                        granted = state.notificationListenerAccessGranted,
                        action = strings.openListenerSettings,
                        onAction = onOpenListenerSettings,
                    )
                }
            }
            item {
                DetectionSettingsSection(title = strings.appNotificationsTitle) {
                    PermissionRow(
                        title =
                            if (state.applicationNotificationsEnabled) {
                                strings.appNotificationsGranted
                            } else {
                                strings.appNotificationsDenied
                            },
                        body = strings.appNotificationsBody,
                        granted = state.applicationNotificationsEnabled,
                        action =
                            if (state.applicationNotificationPermission == DetectionPermissionState.DENIED_CAN_REQUEST) {
                                strings.requestAppNotifications
                            } else {
                                strings.openAppNotificationSettings
                            },
                        onAction =
                            if (state.applicationNotificationPermission == DetectionPermissionState.DENIED_CAN_REQUEST) {
                                onRequestNotificationPermission
                            } else {
                                onOpenNotificationSettings
                            },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                    DetectionSwitchRow(
                        title = strings.reviewNotificationsTitle,
                        body = strings.reviewNotificationsBody,
                        checked = state.userNotificationsEnabled,
                        enabled = !busy,
                        onCheckedChange = onUserNotificationsChange,
                    )
                }
            }
            item {
                DetectionSettingsSection(
                    title = strings.monitoredAppsTitle,
                    subtitle = strings.monitoredAppsBody,
                ) {
                    if (installedApplications.isEmpty()) {
                        Text(
                            strings.monitoredAppsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        installedApplications.sortedBy { it.displayName.lowercase() }.forEachIndexed { index, application ->
                            ApplicationPolicyRow(
                                strings = strings,
                                application = application,
                                controlsEnabled = !busy,
                                onEnabledChange = { enabled -> onApplicationEnabledChange(application, enabled) },
                            )
                            if (index != installedApplications.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.13f))
                            }
                        }
                    }
                }
            }
            item {
                DetectionSettingsSection(
                    title = strings.aiTitle,
                    subtitle = strings.aiBody,
                ) {
                    DetectionSwitchRow(
                        title = strings.aiTitle,
                        body = strings.aiPrivacy,
                        checked = state.aiAnalysisEnabled,
                        enabled =
                            !busy &&
                                (state.compatibleProviderProfiles.isNotEmpty() || state.aiAnalysisEnabled),
                        onCheckedChange = onAiEnabledChange,
                    )
                    if (state.compatibleProviderProfiles.isEmpty()) {
                        Text(
                            strings.aiNoProvider,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CompactPrimaryButton(
                            text = strings.configureProvider,
                            compact = true,
                            onClick = onConfigureProvider,
                        )
                    } else {
                        Text(
                            strings.aiProvider,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            state.compatibleProviderProfiles.forEach { profile ->
                                AppToggleChip(
                                    selected = state.providerProfileId == profile.id,
                                    onClick = { if (!busy) onProviderSelected(profile.id) },
                                    text = "${profile.displayName} · ${profile.model}",
                                )
                            }
                        }
                    }
                }
            }
            item {
                DetectionSettingsSection(
                    title = strings.pendingTitle,
                    subtitle = strings.pendingBody,
                ) {
                    DetectionNavigationRow(
                        title =
                            tokenizedDetection(
                                strings.pendingCount,
                                "count" to state.pendingSuggestions.size,
                            ),
                        body = strings.reviewSuggestion,
                        icon = Icons.Default.Notifications,
                        onClick = onOpenPending,
                    )
                    if (state.failedAnalysisCount > 0) {
                        DetectionInlineNotice(
                            icon = Icons.Default.WarningAmber,
                            text =
                                tokenizedDetection(
                                    strings.failedCount,
                                    "count" to state.failedAnalysisCount,
                                ),
                        )
                        CompactPrimaryButton(
                            text = strings.retryFailed,
                            compact = true,
                            enabled = !busy,
                            onClick = onRetryFailed,
                        )
                    }
                }
            }
            item {
                DetectionSettingsSection(
                    title = strings.deleteDataTitle,
                    subtitle = strings.deleteDataBody,
                ) {
                    CompactPrimaryButton(
                        text = strings.deleteDataAction,
                        danger = true,
                        subtle = true,
                        compact = true,
                        enabled = !busy,
                        onClick = onDeleteData,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(6.dp)) }
        }
    }
}

@Composable
internal fun PendingTransactionSuggestionsRoute(
    s: AppStrings,
    service: AutomaticTransactionDetectionService,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.state.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(service) {
        withContext(Dispatchers.Default) { service.refresh() }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetectionRouteHeader(
            title = s.automaticDetection.pendingTitle,
            subtitle = s.automaticDetection.pendingBody,
            backLabel = s.close,
            onBack = onBack,
            trailing = {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconCompactButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                try {
                                    withContext(Dispatchers.Default) { service.refresh() }
                                } finally {
                                    refreshing = false
                                }
                            }
                        },
                        icon = Icons.Default.Refresh,
                        contentDescription = s.automaticDetection.refresh,
                        compact = true,
                    )
                }
            },
        )

        if (!state.initialized) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (!state.isSupported) {
            DetectionEmptyState(
                icon = Icons.Default.Android,
                title = s.automaticDetection.unsupportedTitle,
                body = s.automaticDetection.unsupportedBody,
            )
        } else if (state.pendingSuggestions.isEmpty()) {
            DetectionEmptyState(
                icon = Icons.Default.CheckCircleOutline,
                title = s.automaticDetection.pendingEmptyTitle,
                body = s.automaticDetection.pendingEmptyBody,
                action = s.automaticDetection.settingsTitle,
                onAction = onOpenSettings,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.pendingSuggestions, key = { it.id }) { candidate ->
                    PendingSuggestionRow(
                        s = s,
                        candidate = candidate,
                        onClick = { onReview(candidate.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
internal fun TransactionSuggestionReviewRoute(
    suggestionId: String,
    s: AppStrings,
    service: AutomaticTransactionDetectionService,
    categories: List<Category>,
    onBack: () -> Unit,
    onOpenPending: () -> Unit,
) {
    var candidate by remember(suggestionId) { mutableStateOf<TransactionCandidate?>(null) }
    var loading by remember(suggestionId) { mutableStateOf(true) }
    var actionBusy by rememberSaveable(suggestionId) { mutableStateOf(false) }
    var outcome by rememberSaveable(suggestionId) { mutableStateOf<String?>(null) }
    var showRejectConfirmation by rememberSaveable(suggestionId) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(suggestionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        candidate =
            withContext(Dispatchers.Default) {
                service.refresh()
                service.suggestion(suggestionId)
            }
    }

    fun perform(
        successMessage: String,
        action: suspend () -> Boolean,
    ) {
        if (actionBusy) return
        actionBusy = true
        outcome = null
        scope.launch {
            try {
                val succeeded = withContext(Dispatchers.Default) { action() }
                outcome = if (succeeded) successMessage else s.automaticDetection.actionFailed
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                outcome = s.automaticDetection.actionFailed
            } finally {
                actionBusy = false
            }
        }
    }

    LaunchedEffect(suggestionId, service) {
        withContext(Dispatchers.Default) { service.refresh() }
        reload()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetectionRouteHeader(
            title = s.automaticDetection.reviewTitle,
            subtitle = s.automaticDetection.reviewSubtitle,
            backLabel = s.automaticDetection.backToPending,
            onBack = onBack,
        )

        when {
            loading ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            candidate == null ->
                DetectionEmptyState(
                    icon = Icons.Default.NotificationsOff,
                    title = s.automaticDetection.suggestionUnavailableTitle,
                    body = s.automaticDetection.suggestionUnavailableBody,
                    action = s.automaticDetection.backToPending,
                    onAction = onOpenPending,
                )
            else -> {
                val loaded = candidate ?: return@Column
                TransactionSuggestionReviewScreen(
                    s = s,
                    candidate = loaded,
                    categories = categories,
                    actionBusy = actionBusy,
                    outcome = outcome,
                    onSave = { updated ->
                        perform(s.automaticDetection.saved) {
                            service.updateSuggestion(updated)
                            true
                        }
                    },
                    onApprove = { updated ->
                        perform(s.automaticDetection.approved) {
                            service.updateSuggestion(updated)
                            when (service.approveSuggestion(updated.id)) {
                                is CandidateApprovalResult.Approved,
                                is CandidateApprovalResult.AlreadyApproved,
                                -> true
                                is CandidateApprovalResult.Rejected -> false
                            }
                        }
                    },
                    onReject = { showRejectConfirmation = true },
                    onDelete = { showDeleteConfirmation = true },
                    onOpenPending = onOpenPending,
                )
            }
        }
    }

    if (showRejectConfirmation) {
        DetectionActionConfirmation(
            title = s.automaticDetection.rejectConfirmTitle,
            body = s.automaticDetection.rejectConfirmBody,
            actionLabel = s.automaticDetection.rejectSuggestion,
            danger = true,
            closeLabel = s.close,
            onDismiss = { showRejectConfirmation = false },
            onConfirm = {
                showRejectConfirmation = false
                perform(s.automaticDetection.rejected) { service.rejectSuggestion(suggestionId) }
            },
        )
    }

    if (showDeleteConfirmation) {
        DetectionActionConfirmation(
            title = s.automaticDetection.deleteConfirmTitle,
            body = s.automaticDetection.deleteConfirmBody,
            actionLabel = s.automaticDetection.deleteSuggestion,
            danger = true,
            closeLabel = s.close,
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                perform(s.automaticDetection.deleted) { service.deleteSuggestion(suggestionId) }
            },
        )
    }
}

@Composable
private fun TransactionSuggestionReviewScreen(
    s: AppStrings,
    candidate: TransactionCandidate,
    categories: List<Category>,
    actionBusy: Boolean,
    outcome: String?,
    onSave: (TransactionCandidate) -> Unit,
    onApprove: (TransactionCandidate) -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit,
    onOpenPending: () -> Unit,
) {
    var description by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.description.orEmpty()) }
    var amountDigits by remember(candidate.id, candidate.updatedAt) {
        mutableStateOf(candidate.amountCents?.toString().orEmpty())
    }
    var currency by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.currency.orEmpty()) }
    var date by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.occurredDate?.toString().orEmpty()) }
    var time by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.occurredTime.orEmpty()) }
    var type by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.transactionType) }
    var categoryId by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.suggestedCategoryId) }
    var paymentMethod by remember(candidate.id, candidate.updatedAt) {
        mutableStateOf(candidate.accountOrPaymentMethod)
    }
    var note by remember(candidate.id, candidate.updatedAt) { mutableStateOf(candidate.note.orEmpty()) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    val editedCandidate =
        reviewedDetectionCandidate(
            candidate = candidate,
            description = description,
            amountDigits = amountDigits,
            currency = currency,
            date = date,
            time = time,
            type = type,
            categoryId = categoryId,
            paymentMethod = paymentMethod,
            note = note,
            updatedAt = Clock.System.now(),
        )
    val validationIssues = validateCandidateForExpenseApproval(editedCandidate)
    val blockingIssues = validationIssues.map { it.code }.distinct()
    val canEdit = candidate.status == CandidateStatus.NEEDS_REVIEW
    val hasDuplicate = candidate.duplicateCandidateId != null || candidate.duplicateExpenseId != null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SuggestionSignalSummary(s = s, candidate = candidate)
        }
        outcome?.let { message ->
            item {
                DetectionInlineNotice(
                    icon =
                        if (message == s.automaticDetection.actionFailed) {
                            Icons.Default.ErrorOutline
                        } else {
                            Icons.Default.CheckCircleOutline
                        },
                    text = message,
                    danger = message == s.automaticDetection.actionFailed,
                )
            }
        }
        if (!canEdit) {
            item {
                ReviewedSuggestionState(s = s, candidate = candidate, onOpenPending = onOpenPending)
            }
        } else {
            item {
                DetectionSettingsSection(title = s.automaticDetection.detectedDetails) {
                    CompactInput(
                        value = description,
                        onValueChange = { description = it },
                        label = s.description,
                        isError = blockingIssues.contains(CandidateIssueCode.EMPTY_DESCRIPTION),
                    )
                    CompactMoneyInput(
                        centsDigits = amountDigits,
                        onCentsDigitsChange = { amountDigits = it },
                        label = s.amount,
                        isError =
                            blockingIssues.contains(CandidateIssueCode.INVALID_AMOUNT) ||
                                blockingIssues.contains(CandidateIssueCode.ZERO_AMOUNT),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactInput(
                            value = date,
                            onValueChange = { date = it.filter { char -> char.isDigit() || char == '-' }.take(10) },
                            label = s.automaticDetection.transactionDate,
                            keyboardType = KeyboardType.Number,
                            isError = blockingIssues.contains(CandidateIssueCode.INVALID_DATE),
                            modifier = Modifier.weight(1.25f),
                        )
                        CompactInput(
                            value = time,
                            onValueChange = { time = it.filter { char -> char.isDigit() || char == ':' }.take(5) },
                            label = s.automaticDetection.transactionTime,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.75f),
                        )
                    }
                    CompactInput(
                        value = currency,
                        onValueChange = { currency = it.take(3) },
                        label = s.automaticDetection.currency,
                        isError = blockingIssues.contains(CandidateIssueCode.UNSUPPORTED_CURRENCY),
                    )
                    Text(
                        s.automaticDetection.transactionType,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        CandidateTransactionType.entries.forEach { option ->
                            AppToggleChip(
                                selected = type == option,
                                onClick = { type = option },
                                text = candidateTypeLabel(option, s),
                            )
                        }
                    }
                    Box {
                        CompactPrimaryButton(
                            text =
                                categories.firstOrNull { it.id == categoryId }?.name
                                    ?: s.selectCategory,
                            subtle = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { categoryMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                        ) {
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        categoryId = category.id
                                        categoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        s.automaticDetection.paymentMethod,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        PaymentMethod.entries.forEach { payment ->
                            AppToggleChip(
                                selected = paymentMethod == payment.name,
                                onClick = { paymentMethod = payment.name },
                                text = paymentMethodLabel(payment, s),
                            )
                        }
                    }
                    CompactInput(
                        value = note,
                        onValueChange = { note = it },
                        label = s.automaticDetection.notes,
                    )
                }
            }
            if (candidate.lowConfidenceFields.isNotEmpty() || candidate.warnings.isNotEmpty() || hasDuplicate) {
                item {
                    CandidateReviewWarnings(s = s, candidate = candidate, hasDuplicate = hasDuplicate)
                }
            }
            if (blockingIssues.isNotEmpty()) {
                item {
                    CandidateValidationNotice(s = s, issues = blockingIssues)
                }
            }
            item {
                Text(
                    s.automaticDetection.approvalExpenseOnly,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactPrimaryButton(
                        text = s.automaticDetection.approveAndAdd,
                        enabled = blockingIssues.isEmpty() && !actionBusy,
                        loading = actionBusy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onApprove(editedCandidate) },
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CompactPrimaryButton(
                            text = s.automaticDetection.saveDraft,
                            compact = true,
                            enabled = !actionBusy,
                            onClick = { onSave(editedCandidate) },
                        )
                        Row {
                            CompactPrimaryButton(
                                text = s.automaticDetection.rejectSuggestion,
                                compact = true,
                                subtle = true,
                                enabled = !actionBusy,
                                onClick = onReject,
                            )
                            CompactPrimaryButton(
                                text = s.automaticDetection.deleteSuggestion,
                                compact = true,
                                subtle = true,
                                danger = true,
                                enabled = !actionBusy,
                                onClick = onDelete,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun DetectionMasterSwitch(
    strings: AutomaticDetectionStrings,
    enabled: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetectionStatusDot(active = enabled)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings.detectionTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) strings.detectionEnabled else strings.detectionBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = onEnabledChange,
                colors = appSwitchColors(),
                modifier = Modifier.semantics { contentDescription = strings.detectionTitle },
            )
        }
    }
}

@Composable
private fun DetectionStatusDot(active: Boolean) {
    Box(
        modifier =
            Modifier
                .size(10.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
    )
}

@Composable
private fun DetectionSettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (granted) Icons.Default.CheckCircleOutline else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                CompactPrimaryButton(text = action, compact = true, onClick = onAction)
            }
        }
    }
}

@Composable
private fun DetectionSwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = appSwitchColors(),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun ApplicationPolicyRow(
    strings: AutomaticDetectionStrings,
    application: DetectionApplication,
    controlsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val allowed = application.enabled && application.policy == MonitoredApplicationPolicy.ALLOW
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val appIcon = rememberInstalledApplicationIcon(application.packageName)
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(30.dp),
                )
            } else {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(application.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                application.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = allowed,
            enabled = controlsEnabled,
            onCheckedChange = onEnabledChange,
            colors = appSwitchColors(),
            modifier =
                Modifier.semantics {
                    contentDescription =
                        tokenizedDetection(
                            if (allowed) strings.blockApp else strings.allowApp,
                            "app" to application.displayName,
                        )
                },
        )
    }
}

@Composable
private fun DetectionNavigationRow(
    title: String,
    body: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = body,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun PendingSuggestionRow(
    s: AppStrings,
    candidate: TransactionCandidate,
    onClick: () -> Unit,
) {
    val confidence = detectionConfidenceBand(candidate.confidence)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetectionStatusDot(active = confidence != DetectionConfidenceBand.LOW)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    candidate.description ?: s.noDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    candidate.amountCents?.let(::formatBrl) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                listOfNotNull(candidate.sourceReference, candidate.occurredDate?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.duplicateCandidateId != null || candidate.duplicateExpenseId != null) {
                Text(
                    s.automaticDetection.duplicateWarning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = s.automaticDetection.reviewSuggestion,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun SuggestionSignalSummary(
    s: AppStrings,
    candidate: TransactionCandidate,
) {
    val band = detectionConfidenceBand(candidate.confidence)
    val confidenceLabel =
        when (band) {
            DetectionConfidenceBand.HIGH -> s.automaticDetection.confidenceHigh
            DetectionConfidenceBand.MEDIUM -> s.automaticDetection.confidenceMedium
            DetectionConfidenceBand.LOW -> s.automaticDetection.confidenceLow
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    candidate.description ?: s.noDescription,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.automaticDetection.sourceApplication}: ${candidate.sourceReference ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                candidate.amountCents?.let(::formatBrl) ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                s.automaticDetection.confidence,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(confidenceLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { (candidate.confidence ?: 0.0).toFloat().coerceIn(0f, 1f) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .semantics {
                        contentDescription = "${s.automaticDetection.confidence}: $confidenceLabel"
                    },
            color = confidenceColor(band),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun CandidateReviewWarnings(
    s: AppStrings,
    candidate: TransactionCandidate,
    hasDuplicate: Boolean,
) {
    DetectionSettingsSection(title = s.automaticDetection.warnings) {
        if (hasDuplicate) {
            DetectionInlineNotice(
                icon = Icons.Default.WarningAmber,
                text = s.automaticDetection.duplicateWarning,
                danger = true,
            )
        }
        if (candidate.lowConfidenceFields.isNotEmpty()) {
            DetectionInlineNotice(
                icon = Icons.Default.WarningAmber,
                text =
                    tokenizedDetection(
                        s.automaticDetection.lowConfidenceFields,
                        "fields" to candidate.lowConfidenceFields.sorted().joinToString(", "),
                    ),
            )
        }
        candidate.warnings.filter(String::isNotBlank).distinct().forEach { warning ->
            DetectionInlineNotice(icon = Icons.Default.WarningAmber, text = warning)
        }
    }
}

@Composable
private fun CandidateValidationNotice(
    s: AppStrings,
    issues: List<CandidateIssueCode>,
) {
    DetectionSettingsSection(title = s.automaticDetection.validationTitle) {
        issues.forEach { issue ->
            DetectionInlineNotice(
                icon = Icons.Default.ErrorOutline,
                text = candidateIssueLabel(issue, s),
                danger = true,
            )
        }
    }
}

@Composable
private fun ReviewedSuggestionState(
    s: AppStrings,
    candidate: TransactionCandidate,
    onOpenPending: () -> Unit,
) {
    val label =
        when (candidate.status) {
            CandidateStatus.APPROVED -> s.automaticDetection.approved
            CandidateStatus.REJECTED -> s.automaticDetection.rejected
            CandidateStatus.FAILED -> s.automaticDetection.extractionFailure
            else -> s.automaticDetection.suggestionUnavailableBody
        }
    DetectionInlineNotice(
        icon =
            if (candidate.status == CandidateStatus.APPROVED) {
                Icons.Default.CheckCircleOutline
            } else {
                Icons.Default.NotificationsOff
            },
        text = label,
        danger = candidate.status == CandidateStatus.FAILED,
    )
    CompactPrimaryButton(
        text = s.automaticDetection.backToPending,
        compact = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenPending,
    )
}

@Composable
private fun DetectionRouteHeader(
    title: String,
    subtitle: String,
    backLabel: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconCompactButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = backLabel,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun DetectionEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 54.dp, start = 8.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            CompactPrimaryButton(text = action, compact = true, onClick = onAction)
        }
    }
}

@Composable
private fun DetectionInlineNotice(
    icon: ImageVector,
    text: String,
    danger: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetectionActionConfirmation(
    title: String,
    body: String,
    actionLabel: String,
    danger: Boolean,
    closeLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        title = title,
        onClose = onDismiss,
        closeLabel = closeLabel,
        onDismissRequest = onDismiss,
    ) {
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CompactPrimaryButton(text = closeLabel, compact = true, subtle = true, onClick = onDismiss)
            CompactPrimaryButton(text = actionLabel, compact = true, danger = danger, onClick = onConfirm)
        }
    }
}

internal enum class DetectionConfidenceBand {
    HIGH,
    MEDIUM,
    LOW,
}

internal fun detectionConfidenceBand(confidence: Double?): DetectionConfidenceBand =
    when {
        confidence != null && confidence >= 0.8 -> DetectionConfidenceBand.HIGH
        confidence != null && confidence >= 0.6 -> DetectionConfidenceBand.MEDIUM
        else -> DetectionConfidenceBand.LOW
    }

internal fun reviewedDetectionCandidate(
    candidate: TransactionCandidate,
    description: String,
    amountDigits: String,
    currency: String,
    date: String,
    time: String,
    type: CandidateTransactionType,
    categoryId: String?,
    paymentMethod: String?,
    note: String,
    updatedAt: kotlin.time.Instant,
): TransactionCandidate =
    candidate.copy(
        description = description.trim().takeIf(String::isNotEmpty),
        amountCents = amountDigits.toLongOrNull(),
        currency = currency.trim().uppercase().takeIf(String::isNotEmpty),
        occurredDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull(),
        occurredTime = time.trim().takeIf(String::isNotEmpty),
        transactionType = type,
        suggestedCategoryId = categoryId,
        accountOrPaymentMethod = paymentMethod,
        note = note.trim().takeIf(String::isNotEmpty),
        updatedAt = updatedAt,
    )

@Composable
private fun confidenceColor(band: DetectionConfidenceBand): Color =
    when (band) {
        DetectionConfidenceBand.HIGH -> MaterialTheme.colorScheme.primary
        DetectionConfidenceBand.MEDIUM -> MaterialTheme.colorScheme.tertiary
        DetectionConfidenceBand.LOW -> MaterialTheme.colorScheme.error
    }

private fun paymentMethodLabel(
    method: PaymentMethod,
    s: AppStrings,
): String =
    when (method) {
        PaymentMethod.PIX -> s.imageImportPaymentPix
        PaymentMethod.DEBIT -> s.imageImportPaymentDebit
        PaymentMethod.CASH -> s.imageImportPaymentCash
        PaymentMethod.TRANSFER -> s.imageImportPaymentTransfer
    }

private fun candidateIssueLabel(
    issue: CandidateIssueCode,
    s: AppStrings,
): String =
    when (issue) {
        CandidateIssueCode.EMPTY_DESCRIPTION -> s.imageImportIssueDescription
        CandidateIssueCode.INVALID_AMOUNT,
        CandidateIssueCode.ZERO_AMOUNT,
        -> s.imageImportIssueAmount
        CandidateIssueCode.MISSING_CURRENCY,
        CandidateIssueCode.UNSUPPORTED_CURRENCY,
        -> s.imageImportIssueCurrency
        CandidateIssueCode.INVALID_DATE,
        CandidateIssueCode.AMBIGUOUS_DATE,
        -> s.imageImportIssueDate
        CandidateIssueCode.UNKNOWN_TYPE,
        CandidateIssueCode.UNSUPPORTED_TYPE,
        -> s.imageImportIssueType
        CandidateIssueCode.MISSING_CATEGORY -> s.imageImportIssueCategory
        CandidateIssueCode.INVALID_PAYMENT_METHOD -> s.imageImportIssuePayment
        CandidateIssueCode.INVALID_INSTALLMENT -> s.imageImportIssueInstallment
        CandidateIssueCode.POSSIBLE_SUMMARY_ROW -> s.imageImportIssueSummary
        CandidateIssueCode.LOW_CONFIDENCE -> s.imageImportLowConfidence
        CandidateIssueCode.POSSIBLE_DUPLICATE -> s.automaticDetection.duplicateWarning
    }

private fun detectionServiceErrorLabel(
    error: DetectionServiceError,
    s: AppStrings,
): String =
    when (error) {
        DetectionServiceError.NOTIFICATION_ACCESS_DENIED -> s.automaticDetection.listenerMissing
        DetectionServiceError.APPLICATION_NOTIFICATION_PERMISSION_DENIED ->
            s.automaticDetection.appNotificationsDenied
        DetectionServiceError.MISSING_PROVIDER_CONFIGURATION,
        DetectionServiceError.UNSUPPORTED_PROVIDER_CAPABILITY,
        -> s.automaticDetection.aiNoProvider
        DetectionServiceError.MISSING_PROVIDER_CREDENTIAL -> s.advisorKeyRequired
        DetectionServiceError.DATABASE -> s.automaticDetection.actionFailed
        DetectionServiceError.PLATFORM -> s.automaticDetection.unsupportedBody
    }

private fun tokenizedDetection(
    template: String,
    vararg values: Pair<String, Any>,
): String = values.fold(template) { result, (key, value) -> result.replace("{$key}", value.toString()) }
