package com.samluiz.gyst.app

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.AdvisorConversation
import com.samluiz.gyst.domain.model.ConversationMessageStatus
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFailure
import com.samluiz.gyst.domain.service.AdvisorFailureCode
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorMessage
import com.samluiz.gyst.domain.service.AdvisorProviderPreset
import com.samluiz.gyst.domain.service.AdvisorProviderPresetId
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.domain.service.AdvisorState
import com.samluiz.gyst.domain.service.AiCapability
import com.samluiz.gyst.presentation.MainState
import com.samluiz.gyst.presentation.toAdvisorFinancialContext

@Composable
internal fun AdvisorPlanningContent(
    s: AppStrings,
    state: MainState,
    onConfigure: (AdvisorConfig, String?) -> Unit,
    onAsk: (String, String) -> Unit,
    onEnsureOverview: (Boolean, String) -> Unit,
    onCreateConversation: (String?) -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRetryMessage: (String, String) -> Unit,
    onCancelResponse: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val advisor = state.advisor
    var showConfig by remember(advisor.isConfigured) { mutableStateOf(!advisor.isConfigured) }
    var showConversationList by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<AdvisorConfig?>(null) }
    var pendingOpenId by remember { mutableStateOf<String?>(null) }
    var createBaseline by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    val context = remember(state) { state.toAdvisorFinancialContext() }

    androidx.compose.runtime.LaunchedEffect(
        advisor.isConfigured,
        advisor.config,
        advisor.selectedConversationId,
        context,
        state.language,
        showConversationList,
    ) {
        if (advisor.isConfigured && !showConversationList) onEnsureOverview(false, s.languageCode)
    }

    androidx.compose.runtime.LaunchedEffect(advisor.config, advisor.hasApiKey, pendingConfig) {
        if (advisor.hasApiKey && pendingConfig == advisor.config) {
            pendingConfig = null
            showConfig = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(advisor.selectedConversationId, advisor.conversations.size, pendingOpenId, createBaseline) {
        val target = pendingOpenId
        if (target != null && advisor.selectedConversationId == target) {
            pendingOpenId = null
            showConversationList = false
        }
        val baseline = createBaseline
        if (baseline != null &&
            (advisor.selectedConversationId != baseline.first || advisor.conversations.size > baseline.second)
        ) {
            createBaseline = null
            showConversationList = false
        }
    }

    if (showConversationList) {
        AdvisorConversationList(
            s = s,
            conversations = advisor.conversations,
            selectedConversationId = advisor.selectedConversationId,
            isLoading = advisor.isConversationListLoading,
            failure = advisor.lastError,
            onBack = { showConversationList = false },
            onCreate = {
                createBaseline = advisor.selectedConversationId to advisor.conversations.size
                onCreateConversation(null)
            },
            onOpen = { conversationId ->
                if (advisor.selectedConversationId == conversationId) {
                    showConversationList = false
                } else {
                    pendingOpenId = conversationId
                    onSelectConversation(conversationId)
                }
            },
            onRename = onRenameConversation,
            onDelete = onDeleteConversation,
        )
    } else {
        AdvisorConversationContent(
            s = s,
            advisor = advisor,
            context = context,
            prompt = prompt,
            onPromptChange = { prompt = it },
            onShowConversations = { showConversationList = true },
            onCreateConversation = { onCreateConversation(null) },
            onShowConfig = { showConfig = true },
            onAsk = {
                onAsk(prompt, s.languageCode)
                prompt = ""
            },
            onRefreshOverview = { onEnsureOverview(true, s.languageCode) },
            onRetryMessage = { onRetryMessage(it, s.languageCode) },
            onCancelResponse = onCancelResponse,
        )
    }

    if (showConfig) {
        AdvisorConfigDialog(
            s = s,
            currentConfig = advisor.config,
            hasKey = advisor.hasApiKey,
            configuredProfileIds = advisor.configuredProfileIds,
            isSaving = advisor.isConfiguring,
            failure = advisor.lastError,
            onClose = { showConfig = false },
            onDisconnect = onDisconnect,
            onRefreshOverview = {
                showConfig = false
                onEnsureOverview(true, s.languageCode)
            },
            onSave = { config, key ->
                pendingConfig = config
                onConfigure(config, key)
            },
        )
    }
}

@Composable
private fun AdvisorConversationContent(
    s: AppStrings,
    advisor: AdvisorState,
    context: AdvisorFinancialContext,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onShowConversations: () -> Unit,
    onCreateConversation: () -> Unit,
    onShowConfig: () -> Unit,
    onAsk: () -> Unit,
    onRefreshOverview: () -> Unit,
    onRetryMessage: (String) -> Unit,
    onCancelResponse: () -> Unit,
) {
    val selectedConversation = advisor.conversations.firstOrNull { it.id == advisor.selectedConversationId }
    val listState = rememberLazyListState()
    val lastMessage = advisor.messages.lastOrNull()

    androidx.compose.runtime.LaunchedEffect(
        advisor.selectedConversationId,
        advisor.messages.size,
        lastMessage?.content,
        lastMessage?.status,
    ) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AdvisorConversationHeader(
            s = s,
            conversation = selectedConversation,
            model = advisor.config.model.takeIf { advisor.isConfigured },
            interactionEnabled = !advisor.isLoading && !advisor.isOverviewLoading,
            onShowConversations = onShowConversations,
            onCreateConversation = onCreateConversation,
            onShowConfig = onShowConfig,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "advisor-welcome") {
                AdvisorOpeningMessage(
                    s = s,
                    advisor = advisor,
                    context = context,
                    onRefresh = onRefreshOverview,
                )
            }
            if (advisor.messages.isEmpty() && !advisor.isLoading) {
                item(key = "advisor-suggestions") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            s.advisorSuggestionExplain,
                            s.advisorSuggestionChange,
                            s.advisorSuggestionPurchase,
                        ).forEach { suggestion ->
                            AppToggleChip(selected = false, onClick = { onPromptChange(suggestion) }, text = suggestion)
                        }
                    }
                }
            }
            itemsIndexed(
                items = advisor.messages,
                key = { index, message -> message.id.ifBlank { "${message.role}-$index" } },
            ) { _, message ->
                AdvisorMessageBubble(
                    message = message,
                    s = s,
                    onRetry = { onRetryMessage(message.id) },
                )
            }
        }

        advisor.lastError?.let { failure ->
            Text(
                advisorErrorText(failure, s),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = { Text(s.advisorAsk) },
                modifier = Modifier.weight(1f),
                enabled = advisor.isConfigured && !advisor.isLoading && !advisor.isOverviewLoading,
                maxLines = 4,
            )
            when {
                advisor.isLoading -> {
                    IconButton(onClick = onCancelResponse) {
                        Icon(Icons.Default.StopCircle, contentDescription = s.advisorCancelResponse)
                    }
                }
                advisor.isOverviewLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                else -> {
                    IconButton(
                        enabled = advisor.isConfigured && prompt.isNotBlank(),
                        onClick = onAsk,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s.advisorAsk)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvisorConversationHeader(
    s: AppStrings,
    conversation: AdvisorConversation?,
    model: String?,
    interactionEnabled: Boolean,
    onShowConversations: () -> Unit,
    onCreateConversation: () -> Unit,
    onShowConfig: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayConversationTitle(conversation?.title, s.advisorUntitledConversation),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model?.let {
                Text(
                    "${s.advisorConfiguredWith} $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = interactionEnabled, onClick = onShowConversations) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = s.advisorManageConversations)
            }
            IconButton(enabled = interactionEnabled, onClick = onCreateConversation) {
                Icon(Icons.Default.Add, contentDescription = s.advisorNewConversation)
            }
            IconButton(enabled = interactionEnabled, onClick = onShowConfig) {
                Icon(
                    if (model != null) Icons.Default.Settings else Icons.Default.Key,
                    contentDescription = s.advisorSetupTitle,
                )
            }
        }
    }
}

@Composable
private fun AdvisorOpeningMessage(
    s: AppStrings,
    advisor: AdvisorState,
    context: AdvisorFinancialContext,
    onRefresh: () -> Unit,
) {
    val overview = advisor.overview
    when {
        advisor.isOverviewLoading -> AdvisorOverviewLoading(s)
        overview?.status == ConversationMessageStatus.COMPLETED && overview.content.isNotBlank() -> {
            AdvisorMessageBubble(
                message = overview,
                s = s,
                onRetry = onRefresh,
                showRetryForFailure = false,
            )
        }
        overview?.status in setOf(ConversationMessageStatus.FAILED, ConversationMessageStatus.CANCELLED) -> {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    advisorErrorText(AdvisorFailure(AdvisorFailureCode.REQUEST_FAILED), s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactPrimaryButton(
                    text = s.advisorRefreshOverview,
                    compact = true,
                    subtle = true,
                    leadingContent = {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    onClick = onRefresh,
                )
            }
        }
        else -> {
            AdvisorMessageBubble(
                message = AdvisorMessage(role = AdvisorRole.ADVISOR, content = deterministicBriefing(context, s)),
                s = s,
                onRetry = onRefresh,
                showRetryForFailure = false,
            )
        }
    }
}

@Composable
private fun AdvisorConversationList(
    s: AppStrings,
    conversations: List<AdvisorConversation>,
    selectedConversationId: String?,
    isLoading: Boolean,
    failure: AdvisorFailure?,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<AdvisorConversation?>(null) }
    var deleteTarget by remember { mutableStateOf<AdvisorConversation?>(null) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.advisorBackToConversation)
            }
            Text(
                s.advisorConversations,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            CompactPrimaryButton(
                text = s.advisorNewConversation,
                enabled = !isLoading,
                leadingContent = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = onCreate,
            )
        }

        when {
            isLoading && conversations.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        s.advisorLoadingConversations,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            conversations.isEmpty() -> {
                AdvisorConversationEmptyState(s = s, onCreate = onCreate)
            }
            else -> {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    itemsIndexed(conversations, key = { _, conversation -> conversation.id }) { index, conversation ->
                        ConversationListRow(
                            s = s,
                            conversation = conversation,
                            selected = conversation.id == selectedConversationId,
                            enabled = !isLoading,
                            onOpen = { onOpen(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onDelete = { deleteTarget = conversation },
                        )
                        if (index < conversations.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                        }
                    }
                }
            }
        }

        failure?.let {
            Text(
                advisorErrorText(it, s),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    renameTarget?.let { conversation ->
        RenameConversationDialog(
            s = s,
            conversation = conversation,
            onClose = { renameTarget = null },
            onRename = { title ->
                onRename(conversation.id, title)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { conversation ->
        DeleteConversationDialog(
            s = s,
            onClose = { deleteTarget = null },
            onDelete = {
                onDelete(conversation.id)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun AdvisorConversationEmptyState(
    s: AppStrings,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s.advisorNoConversationsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            s.advisorNoConversationsBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CompactPrimaryButton(
            text = s.advisorNewConversation,
            leadingContent = {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            onClick = onCreate,
        )
    }
}

@Composable
private fun ConversationListRow(
    s: AppStrings,
    conversation: AdvisorConversation,
    selected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(conversation.id) { mutableStateOf(false) }
    val title = displayConversationTitle(conversation.title, s.advisorUntitledConversation)
    val rowDescription =
        if (selected) {
            "${s.advisorOpenConversation}: $title. ${s.advisorCurrentConversation}"
        } else {
            "${s.advisorOpenConversation}: $title"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpen)
                .semantics {
                    this.selected = selected
                    role = Role.Button
                    contentDescription = rowDescription
                }
                .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        s.advisorCurrentConversation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            conversation.lastMessagePreview?.takeIf(String::isNotBlank)?.let { preview ->
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(enabled = enabled, onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = s.advisorConversationOptions)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(s.advisorRenameConversation) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(s.delete) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameConversationDialog(
    s: AppStrings,
    conversation: AdvisorConversation,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by remember(conversation.id) {
        mutableStateOf(displayConversationTitle(conversation.title, s.advisorUntitledConversation))
    }
    AppDialog(
        title = s.advisorRenameConversation,
        onClose = onClose,
        closeLabel = s.close,
        onDismissRequest = onClose,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = { Text(s.advisorConversationName) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        CompactPrimaryButton(
            text = s.save,
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onRename(title.trim()) },
        )
    }
}

@Composable
private fun DeleteConversationDialog(
    s: AppStrings,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    AppDialog(
        title = s.advisorDeleteConversationTitle,
        onClose = onClose,
        closeLabel = s.close,
        onDismissRequest = onClose,
    ) {
        Text(
            s.advisorDeleteConversationBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompactPrimaryButton(
            text = s.delete,
            danger = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDelete,
        )
    }
}

internal fun displayConversationTitle(
    title: String?,
    fallback: String,
): String = title?.trim()?.takeIf(String::isNotBlank) ?: fallback

@Composable
private fun AdvisorConfigDialog(
    s: AppStrings,
    currentConfig: AdvisorConfig,
    hasKey: Boolean,
    configuredProfileIds: Set<String>,
    isSaving: Boolean,
    failure: AdvisorFailure?,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshOverview: () -> Unit,
    onSave: (AdvisorConfig, String?) -> Unit,
) {
    val initialPreset =
        remember(currentConfig) {
            AdvisorProviderPreset.matching(currentConfig)
                ?: if (currentConfig.model.isBlank()) {
                    AdvisorProviderPreset.entries.first { it.id == AdvisorProviderPresetId.OPENAI }
                } else {
                    AdvisorProviderPreset.entries.first { it.id == AdvisorProviderPresetId.CUSTOM }
                }
        }
    val initialConfig = initialPreset.config ?: currentConfig
    var selectedPresetId by remember { mutableStateOf(initialPreset.id) }
    var baseUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var model by remember { mutableStateOf(initialConfig.model) }
    var apiFormat by remember { mutableStateOf(initialConfig.apiFormat) }
    var providerId by remember { mutableStateOf(initialConfig.providerId) }
    var profileId by remember { mutableStateOf(initialConfig.profileId) }
    var capabilities by remember { mutableStateOf(initialConfig.capabilities) }
    var showAdvanced by remember { mutableStateOf(initialPreset.id == AdvisorProviderPresetId.CUSTOM) }
    var apiKey by remember { mutableStateOf("") }
    val selectedPreset = AdvisorProviderPreset.entries.first { it.id == selectedPresetId }
    val uriHandler = LocalUriHandler.current
    val normalizedDraft =
        AdvisorConfig(
            baseUrl = baseUrl.trim().trimEnd('/'),
            model = model.trim(),
            apiFormat = apiFormat,
            providerId = providerId,
            profileId = profileId,
            capabilities = capabilities + AiCapability.TEXT_GENERATION,
        )
    val canReuseExistingKey = normalizedDraft.profileId in configuredProfileIds || (hasKey && normalizedDraft == currentConfig)
    AppDialog(s.advisorSetupTitle, onClose, s.close, onDismissRequest = onClose, maxWidth = 460.dp) {
        Text(s.advisorSetupBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s.advisorProvider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdvisorProviderPreset.entries.forEach { preset ->
                AppToggleChip(
                    selected = selectedPresetId == preset.id,
                    onClick = {
                        selectedPresetId = preset.id
                        preset.config?.let {
                            baseUrl = it.baseUrl
                            model = it.model
                            apiFormat = it.apiFormat
                            providerId = it.providerId
                            profileId = it.profileId
                            capabilities = it.capabilities
                            showAdvanced = false
                        } ?: run {
                            providerId = "custom"
                            profileId = "custom-default"
                            capabilities = setOf(AiCapability.TEXT_GENERATION)
                            showAdvanced = true
                        }
                    },
                    text = if (preset.id == AdvisorProviderPresetId.CUSTOM) s.advisorCustomProvider else preset.displayName,
                )
            }
        }
        Text(s.advisorPresetHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        selectedPreset.apiKeyUrl?.let { apiKeyUrl ->
            Row(
                modifier =
                    Modifier.clickable { uriHandler.openUri(apiKeyUrl) }
                        .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.advisorGetApiKey.replace("{provider}", selectedPreset.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (selectedPresetId != AdvisorProviderPresetId.CUSTOM) {
            Text(
                s.advisorAdvancedSettings,
                modifier = Modifier.clickable { showAdvanced = !showAdvanced },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (showAdvanced) {
            CompactInput(baseUrl, { baseUrl = it }, s.advisorBaseUrl)
            CompactInput(model, { model = it }, s.advisorModel)
            Text(s.advisorApiFormat, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppToggleChip(
                    selected = apiFormat == AdvisorApiFormat.CHAT_COMPLETIONS,
                    onClick = { apiFormat = AdvisorApiFormat.CHAT_COMPLETIONS },
                    text = s.advisorChatCompletions,
                )
                AppToggleChip(
                    selected = apiFormat == AdvisorApiFormat.RESPONSES,
                    onClick = { apiFormat = AdvisorApiFormat.RESPONSES },
                    text = s.advisorResponses,
                )
            }
            Text(
                s.advisorCapabilities,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityToggle(
                    label = s.advisorVisionInput,
                    capability = AiCapability.VISION_INPUT,
                    capabilities = capabilities,
                    onChange = { capabilities = it },
                )
                CapabilityToggle(
                    label = s.advisorStructuredOutput,
                    capability = AiCapability.STRUCTURED_OUTPUT,
                    capabilities = capabilities,
                    onChange = { capabilities = it },
                )
                CapabilityToggle(
                    label = s.advisorStreaming,
                    capability = AiCapability.STREAMING,
                    capabilities = capabilities,
                    onChange = { capabilities = it },
                )
            }
            Text(
                s.advisorCapabilitiesHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (canReuseExistingKey) s.advisorReplaceKeyHint else s.advisorApiKey) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(s.advisorPrivacy, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failure?.let {
            Text(advisorErrorText(it, s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CompactPrimaryButton(
                s.advisorSaveConnection,
                enabled = !isSaving && baseUrl.isNotBlank() && model.isNotBlank() && (canReuseExistingKey || apiKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                squared = true,
                onClick = { onSave(normalizedDraft, apiKey.takeIf(String::isNotBlank)) },
            )
            if (hasKey) {
                CompactPrimaryButton(
                    s.advisorRefreshOverview,
                    compact = true,
                    subtle = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefreshOverview,
                )
                CompactPrimaryButton(
                    s.advisorDisconnect,
                    compact = true,
                    subtle = true,
                    danger = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDisconnect,
                )
            }
        }
    }
}

@Composable
private fun CapabilityToggle(
    label: String,
    capability: AiCapability,
    capabilities: Set<AiCapability>,
    onChange: (Set<AiCapability>) -> Unit,
) {
    val selected = capability in capabilities
    AppToggleChip(
        selected = selected,
        onClick = {
            onChange(if (selected) capabilities - capability else capabilities + capability)
        },
        text = label,
    )
}

@Composable
private fun AdvisorOverviewLoading(s: AppStrings) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            s.advisorPreparingOverview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun advisorErrorText(
    failure: AdvisorFailure,
    s: AppStrings,
): String {
    val message =
        when (failure.code) {
            AdvisorFailureCode.INVALID_BASE_URL -> s.advisorInvalidBaseUrl
            AdvisorFailureCode.MODEL_REQUIRED -> s.advisorModelRequired
            AdvisorFailureCode.API_KEY_REQUIRED -> s.advisorKeyRequired
            AdvisorFailureCode.NOT_CONFIGURED -> s.advisorNotConfigured
            AdvisorFailureCode.SECURE_STORAGE -> s.advisorSecureStorageError
            AdvisorFailureCode.AUTHENTICATION,
            AdvisorFailureCode.RATE_LIMITED,
            AdvisorFailureCode.NETWORK,
            AdvisorFailureCode.TIMEOUT,
            AdvisorFailureCode.INVALID_RESPONSE,
            AdvisorFailureCode.UNSUPPORTED_CAPABILITY,
            AdvisorFailureCode.CANCELLED,
            AdvisorFailureCode.DATABASE,
            AdvisorFailureCode.REQUEST_FAILED,
            -> s.advisorRequestFailed
        }
    return failure.detail?.takeIf(String::isNotBlank)?.let { "$message $it" } ?: message
}

@Composable
private fun AdvisorMessageBubble(
    message: AdvisorMessage,
    s: AppStrings,
    onRetry: () -> Unit,
    showRetryForFailure: Boolean = true,
) {
    val advisor = message.role == AdvisorRole.ADVISOR
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (advisor) Arrangement.Start else Arrangement.End) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(if (advisor) 1f else 0.88f)
                    .background(
                        if (advisor) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(if (advisor) 0.dp else 11.dp)
                    .semantics {
                        if (message.status in setOf(ConversationMessageStatus.PENDING, ConversationMessageStatus.STREAMING)) {
                            liveRegion = LiveRegionMode.Polite
                        }
                    },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (message.content.isNotBlank()) {
                if (advisor) {
                    AdvisorMarkdown(
                        content = message.content,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when (message.status) {
                ConversationMessageStatus.PENDING -> {
                    AdvisorMessageProgress(label = s.advisorMessagePending)
                }
                ConversationMessageStatus.STREAMING -> {
                    AdvisorMessageProgress(label = s.advisorMessageStreaming)
                }
                ConversationMessageStatus.FAILED,
                ConversationMessageStatus.CANCELLED,
                -> {
                    val failed = message.status == ConversationMessageStatus.FAILED
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (failed) Icons.Default.ErrorOutline else Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (failed) s.advisorMessageFailed else s.advisorMessageCancelled,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (showRetryForFailure && message.id.isNotBlank()) {
                            CompactPrimaryButton(
                                text = s.advisorRetry,
                                compact = true,
                                subtle = true,
                                leadingContent = {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                onClick = onRetry,
                            )
                        }
                    }
                }
                ConversationMessageStatus.COMPLETED -> Unit
            }
        }
    }
}

@Composable
private fun AdvisorMessageProgress(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun deterministicBriefing(
    context: AdvisorFinancialContext,
    s: AppStrings,
): String {
    val tightest = context.forecast.minByOrNull { it.expectedFreeBalanceCents } ?: return s.advisorEmptyBriefing
    return s.advisorTightestBriefing
        .replace("{month}", formatYearMonthHuman(tightest.yearMonth, s.languageCode))
        .replace("{amount}", formatBrl(tightest.expectedFreeBalanceCents))
}
