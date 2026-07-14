package com.samluiz.gyst.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.service.AppUpdateState
import com.samluiz.gyst.domain.service.AutomaticTransactionDetectionService
import com.samluiz.gyst.domain.service.GoogleSyncErrorCode
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.domain.service.SyncSource
import com.samluiz.gyst.presentation.AppDestination
import com.samluiz.gyst.presentation.AppNavigator
import com.samluiz.gyst.presentation.MainStore
import com.samluiz.gyst.presentation.MainTab
import gyst.shared.generated.resources.OpenspaceRegular
import gyst.shared.generated.resources.Res
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.Font
import org.koin.compose.koinInject

private data class NavDestination(
    val screen: MainTab,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun GystRoot() {
    val store: MainStore = koinInject()
    val navigator: AppNavigator = koinInject()
    val imageImportService: ImageImportService = koinInject()
    val automaticDetectionService: AutomaticTransactionDetectionService = koinInject()
    val state by store.state.collectAsState()
    val navigation by navigator.state.collectAsState()
    val automaticDetectionState by automaticDetectionService.state.collectAsState()
    val s = rememberStrings(state.language)
    val screen = navigation.selectedTab
    val isFullScreenDestination =
        navigation.destination is AppDestination.ImageImport ||
            navigation.destination is AppDestination.DetectionSettings ||
            navigation.destination is AppDestination.PendingSuggestions ||
            navigation.destination is AppDestination.SuggestionReview
    var despesasSectionIndex by rememberSaveable { mutableStateOf(0) }
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    GystTheme(themeMode = state.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            LaunchedEffect(Unit) { store.bootstrap() }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        MaterialTheme.colorScheme.background,
                                        MaterialTheme.colorScheme.surface,
                                        MaterialTheme.colorScheme.background,
                                    ),
                            ),
                        ),
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (!isKeyboardVisible && !isFullScreenDestination) {
                            BottomNav(
                                s = s,
                                selected = screen,
                                onSelect = navigator::selectTab,
                            )
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.errorMessage?.let { error ->
                            PanelCard(title = s.errorTitle, icon = Icons.Default.CreditCardOff) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconCompactButton(
                                        onClick = store::clearError,
                                        icon = Icons.Default.Close,
                                        contentDescription = s.close,
                                        compact = true,
                                    )
                                }
                            }
                        }

                        state.infoMessage?.let { info ->
                            PanelCard(title = "Info", icon = Icons.Default.Info) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        info,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconCompactButton(
                                        onClick = store::clearInfo,
                                        icon = Icons.Default.Close,
                                        contentDescription = s.close,
                                        compact = true,
                                    )
                                }
                            }
                            LaunchedEffect(info) {
                                delay(3500)
                                store.clearInfo()
                            }
                        }

                        if (isFullScreenDestination) {
                            when (val destination = navigation.destination) {
                                is AppDestination.ImageImport ->
                                    ImageImportRoute(
                                        s = s,
                                        service = imageImportService,
                                        categories = state.categories,
                                        onBack = { navigator.back() },
                                        onConfigureProvider = { navigator.selectTab(MainTab.PLANNING) },
                                    )
                                AppDestination.DetectionSettings ->
                                    AutomaticDetectionSettingsRoute(
                                        s = s,
                                        service = automaticDetectionService,
                                        onBack = { navigator.back() },
                                        onOpenPending = {
                                            navigator.navigate(AppDestination.PendingSuggestions)
                                        },
                                        onConfigureProvider = { navigator.selectTab(MainTab.PLANNING) },
                                    )
                                AppDestination.PendingSuggestions ->
                                    PendingTransactionSuggestionsRoute(
                                        s = s,
                                        service = automaticDetectionService,
                                        onBack = { navigator.back() },
                                        onReview = navigator::reviewSuggestion,
                                        onOpenSettings = {
                                            navigator.navigate(AppDestination.DetectionSettings)
                                        },
                                    )
                                is AppDestination.SuggestionReview ->
                                    TransactionSuggestionReviewRoute(
                                        suggestionId = destination.suggestionId,
                                        s = s,
                                        service = automaticDetectionService,
                                        categories = state.categories,
                                        onBack = {
                                            navigator.navigate(AppDestination.PendingSuggestions)
                                        },
                                        onOpenPending = {
                                            navigator.navigate(AppDestination.PendingSuggestions)
                                        },
                                    )
                                else -> Unit
                            }
                        } else {
                            Header(
                                s = s,
                                month = capitalizeFirst(formatYearMonthHuman(state.currentMonth, s.languageCode)),
                                onPrev = store::goToPreviousMonth,
                                onNext = store::goToNextMonth,
                                onToday = store::goToCurrentMonth,
                                showMonthSelector = screen != MainTab.PROFILE,
                            )

                            AnimatedContent(targetState = screen to state.isLoading, label = "screen") { (current, loading) ->
                                if (loading) {
                                    ScreenSkeleton(current = current)
                                } else {
                                    when (current) {
                                        MainTab.SUMMARY ->
                                            ResumoTab(
                                                s = s,
                                                state = state,
                                                onSaveIncome = store::saveIncome,
                                            )
                                        MainTab.EXPENSES ->
                                            DespesasTab(
                                                s,
                                                state,
                                                selectedSectionIndex = despesasSectionIndex,
                                                onSelectedSectionChange = { despesasSectionIndex = it },
                                                onLoadMoreExpenses = store::loadMoreExpenses,
                                                onAddExpense = store::addExpense,
                                                onAddCategory = store::addCategory,
                                                onUpdateCategory = store::updateCategoryName,
                                                onDeleteCategory = store::deleteCategory,
                                                onAddSubscription = store::addSubscription,
                                                onAddInstallment = store::addInstallment,
                                                onUpdateExpense = store::updateExpense,
                                                onDeleteExpense = store::deleteExpense,
                                                onUpdateSubscription = store::updateSubscription,
                                                onDeleteSubscription = store::deleteSubscription,
                                                onUpdateInstallment = store::updateInstallment,
                                                onDeleteInstallment = store::deleteInstallment,
                                                onDuplicateExpense = store::duplicateExpense,
                                                onDuplicateSubscription = store::duplicateSubscription,
                                                onDuplicateInstallment = store::duplicateInstallment,
                                                onOpenImageImport = {
                                                    navigator.navigate(AppDestination.ImageImport())
                                                },
                                            )
                                        MainTab.PLANNING ->
                                            PlanningTab(
                                                s = s,
                                                state = state,
                                                onConfigureAdvisor = store::configureAdvisor,
                                                onAskAdvisor = store::askAdvisor,
                                                onEnsureAdvisorOverview = store::ensureAdvisorOverview,
                                                onCreateAdvisorConversation = store::createAdvisorConversation,
                                                onSelectAdvisorConversation = store::selectAdvisorConversation,
                                                onRenameAdvisorConversation = store::renameAdvisorConversation,
                                                onDeleteAdvisorConversation = store::deleteAdvisorConversation,
                                                onRetryAdvisorMessage = store::retryAdvisorMessage,
                                                onCancelAdvisorResponse = store::cancelAdvisorResponse,
                                                onDisconnectAdvisor = store::disconnectAdvisor,
                                            )
                                        MainTab.PROFILE ->
                                            ProfileTab(
                                                s,
                                                state,
                                                automaticDetectionState = automaticDetectionState,
                                                onSetLanguage = store::setLanguage,
                                                onSetTheme = store::setThemeMode,
                                                onCheckForUpdates = store::checkForUpdates,
                                                onStartUpdate = store::startUpdate,
                                                onSignInGoogle = store::signInGoogle,
                                                onSignOutGoogle = store::signOutGoogle,
                                                onSyncGoogleDrive = store::syncGoogleDrive,
                                                onRestoreGoogleDrive = store::restoreFromGoogleDrive,
                                                onResetLocalData = store::resetLocalData,
                                                onOpenAutomaticDetectionSettings = {
                                                    navigator.navigate(AppDestination.DetectionSettings)
                                                },
                                                onOpenPendingSuggestions = {
                                                    navigator.navigate(AppDestination.PendingSuggestions)
                                                },
                                            )
                                    }
                                }
                            }
                        }
                    }
                }

                state.blockingMessage?.let { message ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                                .pointerInput(Unit) {},
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                                ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = blockingMessageLabel(message, s),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    s: AppStrings,
    month: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    showMonthSelector: Boolean,
) {
    val logoFont = FontFamily(Font(Res.font.OpenspaceRegular))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Gyst",
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = logoFont,
                        fontWeight = FontWeight.Normal,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.alpha(if (showMonthSelector) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCompactButton(
                    onClick = { if (showMonthSelector) onPrev() },
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "previous",
                    enabled = showMonthSelector,
                    compact = true,
                )
                Text(month, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 2.dp))
                CompactPrimaryButton(
                    text = s.currentMonth,
                    compact = true,
                    subtle = true,
                    squared = true,
                    enabled = showMonthSelector,
                    onClick = { if (showMonthSelector) onToday() },
                )
                IconCompactButton(
                    onClick = { if (showMonthSelector) onNext() },
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "next",
                    enabled = showMonthSelector,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ScreenSkeleton(current: MainTab) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PanelCard(
                title =
                    when (current) {
                        MainTab.SUMMARY -> " "
                        MainTab.EXPENSES -> " "
                        MainTab.PLANNING -> " "
                        MainTab.PROFILE -> " "
                    },
            ) {
                SkeletonLine(width = 0.42f)
                SkeletonLine(width = 0.78f)
                SkeletonLine(width = 0.64f)
            }
        }
        item {
            PanelCard(title = " ") {
                SkeletonLine(width = 0.92f)
                SkeletonLine(width = 0.86f)
                SkeletonLine(width = 0.73f)
                SkeletonLine(width = 0.55f)
            }
        }
        item {
            PanelCard(title = " ") {
                SkeletonLine(width = 0.88f)
                SkeletonLine(width = 0.60f)
                SkeletonLine(width = 0.82f)
            }
        }
    }
}

@Composable
private fun SkeletonLine(width: Float) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(width.coerceIn(0.15f, 1f))
                .height(12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    RoundedCornerShape(7.dp),
                ),
    )
}

@Composable
private fun BottomNav(
    s: AppStrings,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations =
        listOf(
            NavDestination(MainTab.SUMMARY, s.tabSummary, Icons.Default.AutoGraph),
            NavDestination(MainTab.EXPENSES, s.tabExpenses, Icons.AutoMirrored.Filled.ReceiptLong),
            NavDestination(MainTab.PLANNING, s.tabPlanning, Icons.Default.Savings),
            NavDestination(MainTab.PROFILE, s.profile, Icons.Default.Person),
        )

    NavigationBar(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(8.dp),
                    spotColor = Color.Black.copy(alpha = 0.14f),
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { destination ->
            val isSelected = selected == destination.screen
            val activeProgress by
                animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(durationMillis = 220),
                    label = "nav-active-${destination.screen}",
                )
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination.screen) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    ),
                icon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier =
                                Modifier
                                    .width(28.dp)
                                    .height(3.dp)
                                    .graphicsLayer {
                                        scaleX = activeProgress
                                        alpha = activeProgress
                                    }.background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(3.dp),
                                    ),
                        )
                        Icon(
                            destination.icon,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(18.dp)
                                    .graphicsLayer {
                                        scaleX = 1f + (activeProgress * 0.08f)
                                        scaleY = 1f + (activeProgress * 0.08f)
                                    },
                        )
                    }
                },
                label = {
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
internal fun syncSourceLabel(
    source: SyncSource,
    s: AppStrings,
): String =
    when (source) {
        SyncSource.LOCAL_TO_CLOUD -> s.syncSourceLocalToCloud
        SyncSource.CLOUD_TO_LOCAL -> s.syncSourceCloudToLocal
    }

private fun blockingMessageLabel(
    token: String,
    s: AppStrings,
): String =
    when (token) {
        "sync.restore.applying" -> s.applyingBackup
        "sync.reload.applying" -> s.applyingData
        else -> token.ifBlank { s.applyingBackup }
    }

internal fun googleFeedbackLabel(
    google: GoogleSyncState,
    s: AppStrings,
): String? =
    when {
        google.isAuthInProgress -> s.signingInGoogle
        google.isSyncing -> s.syncInProgress
        google.hadSyncConflict && google.lastSyncSource == SyncSource.CLOUD_TO_LOCAL -> s.syncConflictApplied
        google.lastSyncSource == SyncSource.LOCAL_TO_CLOUD -> s.syncUploaded
        google.lastSyncSource == SyncSource.CLOUD_TO_LOCAL -> s.restoreApplied
        else -> null
    }

internal fun googleErrorLabel(
    google: GoogleSyncState,
    s: AppStrings,
): String? {
    val mapped =
        when (google.lastErrorCode) {
            GoogleSyncErrorCode.OAUTH_NOT_CONFIGURED -> s.syncErrorOauthConfig
            GoogleSyncErrorCode.SIGN_IN_UNAVAILABLE -> s.syncErrorSignInUnavailable
            GoogleSyncErrorCode.SIGN_IN_CONFIG_MISMATCH -> s.syncErrorSignInConfigMismatch
            GoogleSyncErrorCode.SIGN_IN_CANCELED -> s.syncErrorSignInCanceled
            GoogleSyncErrorCode.SIGN_IN_FAILED -> s.syncErrorGeneric
            GoogleSyncErrorCode.SESSION_EXPIRED -> s.syncErrorSessionExpired
            GoogleSyncErrorCode.ACCOUNT_NOT_AUTHENTICATED -> s.syncErrorAccountNotAuthenticated
            GoogleSyncErrorCode.ACCESS_TOKEN_FAILED -> s.syncErrorSessionExpired
            GoogleSyncErrorCode.BACKUP_NOT_FOUND -> s.syncErrorBackupNotFound
            GoogleSyncErrorCode.LOCAL_DATA_MISSING -> s.syncErrorGeneric
            GoogleSyncErrorCode.INVALID_BACKUP -> s.syncErrorInvalidBackup
            GoogleSyncErrorCode.NETWORK -> s.syncErrorNetwork
            GoogleSyncErrorCode.API -> s.syncErrorApi
            GoogleSyncErrorCode.RESTORE_CANCELED -> null
            GoogleSyncErrorCode.SYNC_FAILED -> s.syncErrorGeneric
            GoogleSyncErrorCode.RESTORE_FAILED -> s.syncErrorGeneric
            GoogleSyncErrorCode.UNKNOWN, null -> null
        }
    return mapped ?: google.lastError
}

@Composable
internal fun appUpdateStatusVisual(
    update: AppUpdateState,
    s: AppStrings,
): SyncBadgeVisual {
    return when {
        !update.isAvailable ->
            SyncBadgeVisual(
                label = s.updateStatusUnavailable,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        update.isChecking ->
            SyncBadgeVisual(
                label = s.updateStatusChecking,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f),
                borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
                dotColor = MaterialTheme.colorScheme.secondary,
            )
        update.isUpdateAvailable ->
            SyncBadgeVisual(
                label = s.updateStatusAvailable,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.34f),
                dotColor = MaterialTheme.colorScheme.error,
            )
        else ->
            SyncBadgeVisual(
                label = s.updateStatusCurrent,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                dotColor = MaterialTheme.colorScheme.primary,
            )
    }
}

internal data class SyncBadgeVisual(
    val label: String,
    val containerColor: Color,
    val borderColor: Color,
    val dotColor: Color,
)

internal fun capitalizeFirst(value: String): String {
    return value.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
