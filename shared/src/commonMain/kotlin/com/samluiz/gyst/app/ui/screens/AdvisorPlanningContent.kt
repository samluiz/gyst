package com.samluiz.gyst.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.service.AdvisorApiFormat
import com.samluiz.gyst.domain.service.AdvisorConfig
import com.samluiz.gyst.domain.service.AdvisorFailure
import com.samluiz.gyst.domain.service.AdvisorFailureCode
import com.samluiz.gyst.domain.service.AdvisorFinancialContext
import com.samluiz.gyst.domain.service.AdvisorProviderPreset
import com.samluiz.gyst.domain.service.AdvisorProviderPresetId
import com.samluiz.gyst.domain.service.AdvisorRole
import com.samluiz.gyst.presentation.MainState
import com.samluiz.gyst.presentation.toAdvisorFinancialContext

@Composable
internal fun AdvisorPlanningContent(
    s: AppStrings,
    state: MainState,
    onConfigure: (String, String, AdvisorApiFormat, String?) -> Unit,
    onAsk: (String, String) -> Unit,
    onEnsureOverview: (Boolean, String) -> Unit,
    onClear: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val advisor = state.advisor
    var showConfig by remember(advisor.isConfigured) { mutableStateOf(!advisor.isConfigured) }
    var prompt by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<AdvisorConfig?>(null) }
    val context = remember(state) { state.toAdvisorFinancialContext() }

    androidx.compose.runtime.LaunchedEffect(advisor.isConfigured, advisor.config, context, state.language) {
        if (advisor.isConfigured) onEnsureOverview(false, s.languageCode)
    }

    androidx.compose.runtime.LaunchedEffect(advisor.config, advisor.hasApiKey, pendingConfig) {
        if (advisor.hasApiKey && pendingConfig == advisor.config) {
            pendingConfig = null
            showConfig = false
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.advisorBriefing, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (advisor.isConfigured) {
                    Text(
                        "${s.advisorConfiguredWith} ${advisor.config.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (advisor.messages.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.RestartAlt, contentDescription = s.advisorClear)
                    }
                }
                IconButton(onClick = { showConfig = true }) {
                    Icon(if (advisor.isConfigured) Icons.Default.Settings else Icons.Default.Key, s.advisorSetupTitle)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "advisor-welcome") {
                when {
                    advisor.overview != null -> AdvisorMessageBubble(AdvisorRole.ADVISOR, advisor.overview.content)
                    advisor.isOverviewLoading -> AdvisorOverviewLoading(s)
                    else -> AdvisorMessageBubble(AdvisorRole.ADVISOR, deterministicBriefing(context, s))
                }
            }
            if (advisor.messages.isEmpty()) {
                item(key = "advisor-suggestions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            s.advisorSuggestionExplain,
                            s.advisorSuggestionChange,
                            s.advisorSuggestionPurchase,
                        ).forEach { suggestion ->
                            AppToggleChip(selected = false, onClick = { prompt = suggestion }, text = suggestion)
                        }
                    }
                }
            }
            itemsIndexed(
                items = advisor.messages,
                key = { index, message -> message.id.ifBlank { "${message.role}-$index" } },
            ) { _, message ->
                AdvisorMessageBubble(message.role, message.content)
            }
        }

        advisor.lastError?.let {
            Text(advisorErrorText(it, s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text(s.advisorAsk) },
                modifier = Modifier.weight(1f),
                enabled = advisor.isConfigured && !advisor.isLoading && !advisor.isOverviewLoading,
                maxLines = 4,
            )
            IconButton(
                enabled = advisor.isConfigured && prompt.isNotBlank() && !advisor.isLoading && !advisor.isOverviewLoading,
                onClick = {
                    onAsk(prompt, s.languageCode)
                    prompt = ""
                },
            ) {
                if (advisor.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s.advisorAsk)
                }
            }
        }
    }

    if (showConfig) {
        AdvisorConfigDialog(
            s = s,
            currentBaseUrl = advisor.config.baseUrl,
            currentModel = advisor.config.model,
            currentApiFormat = advisor.config.apiFormat,
            hasKey = advisor.hasApiKey,
            isSaving = advisor.isConfiguring,
            failure = advisor.lastError,
            onClose = { showConfig = false },
            onDisconnect = onDisconnect,
            onRefreshOverview = {
                showConfig = false
                onEnsureOverview(true, s.languageCode)
            },
            onSave = { baseUrl, model, apiFormat, key ->
                pendingConfig = AdvisorConfig(baseUrl.trim().trimEnd('/'), model.trim(), apiFormat)
                onConfigure(baseUrl, model, apiFormat, key)
            },
        )
    }
}

@Composable
private fun AdvisorConfigDialog(
    s: AppStrings,
    currentBaseUrl: String,
    currentModel: String,
    currentApiFormat: AdvisorApiFormat,
    hasKey: Boolean,
    isSaving: Boolean,
    failure: AdvisorFailure?,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshOverview: () -> Unit,
    onSave: (String, String, AdvisorApiFormat, String?) -> Unit,
) {
    val currentConfig =
        remember(currentBaseUrl, currentModel, currentApiFormat) { AdvisorConfig(currentBaseUrl, currentModel, currentApiFormat) }
    val initialPreset =
        remember(currentConfig) {
            AdvisorProviderPreset.matching(currentConfig)
                ?: if (currentModel.isBlank()) {
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
    var showAdvanced by remember { mutableStateOf(initialPreset.id == AdvisorProviderPresetId.CUSTOM) }
    var apiKey by remember { mutableStateOf("") }
    val selectedPreset = AdvisorProviderPreset.entries.first { it.id == selectedPresetId }
    val uriHandler = LocalUriHandler.current
    val normalizedDraft = AdvisorConfig(baseUrl.trim().trimEnd('/'), model.trim(), apiFormat)
    val canReuseExistingKey = hasKey && normalizedDraft == currentConfig
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
                            showAdvanced = false
                        } ?: run { showAdvanced = true }
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
                onClick = { onSave(baseUrl, model, apiFormat, apiKey.takeIf(String::isNotBlank)) },
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
            AdvisorFailureCode.REQUEST_FAILED -> s.advisorRequestFailed
        }
    return failure.detail?.takeIf(String::isNotBlank)?.let { "$message $it" } ?: message
}

@Composable
private fun AdvisorMessageBubble(
    role: AdvisorRole,
    content: String,
) {
    val advisor = role == AdvisorRole.ADVISOR
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (advisor) Arrangement.Start else Arrangement.End) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(if (advisor) 1f else 0.88f)
                    .background(
                        if (advisor) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(if (advisor) 0.dp else 11.dp),
        ) {
            if (advisor) {
                AdvisorMarkdown(
                    content = content,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
