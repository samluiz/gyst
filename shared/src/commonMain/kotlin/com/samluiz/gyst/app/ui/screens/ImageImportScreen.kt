package com.samluiz.gyst.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samluiz.gyst.domain.model.CandidateIssueCode
import com.samluiz.gyst.domain.model.CandidateIssueSeverity
import com.samluiz.gyst.domain.model.CandidateTransactionType
import com.samluiz.gyst.domain.model.Category
import com.samluiz.gyst.domain.model.PaymentMethod
import com.samluiz.gyst.domain.model.TransactionCandidate
import com.samluiz.gyst.domain.service.ImageImportFailure
import com.samluiz.gyst.domain.service.ImageImportFailureCode
import com.samluiz.gyst.domain.service.ImageImportImage
import com.samluiz.gyst.domain.service.ImageImportService
import com.samluiz.gyst.domain.service.ImageImportStage
import com.samluiz.gyst.domain.service.ImageImportState
import com.samluiz.gyst.domain.service.ReviewableTransactionCandidate
import com.samluiz.gyst.domain.service.TransactionCandidateEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

@Composable
internal fun ImageImportRoute(
    s: AppStrings,
    service: ImageImportService,
    categories: List<Category>,
    onBack: () -> Unit,
    onConfigureProvider: () -> Unit,
) {
    val state by service.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(service) {
        withContext(Dispatchers.Default) { service.initialize() }
    }
    LaunchedEffect(state.compatibleProfiles, state.selectedProviderProfileId) {
        val availableIds = state.compatibleProfiles.map { it.id }.toSet()
        selectedProviderId =
            when {
                state.selectedProviderProfileId in availableIds -> state.selectedProviderProfileId
                selectedProviderId in availableIds -> selectedProviderId
                state.compatibleProfiles.size == 1 -> state.compatibleProfiles.single().id
                else -> null
            }
    }

    fun closeOrConfirm() {
        if (state.stage in setOf(ImageImportStage.IDLE, ImageImportStage.COMPLETED, ImageImportStage.CANCELLED) &&
            state.images.isEmpty()
        ) {
            scope.launchImageImportAction(
                action = { service.clear() },
                onComplete = onBack,
            )
        } else {
            showCancelConfirmation = true
        }
    }

    ImageImportScreen(
        s = s,
        state = state,
        categories = categories,
        selectedProviderId = selectedProviderId,
        privacyAccepted = privacyAccepted,
        onSelectedProviderChange = { selectedProviderId = it },
        onPrivacyAcceptedChange = { privacyAccepted = it },
        onBack = ::closeOrConfirm,
        onConfigureProvider = onConfigureProvider,
        onSelectImages = { scope.launchImageImportAction { service.selectImages() } },
        onCaptureImage = { scope.launchImageImportAction { service.captureImage() } },
        onRemoveImage = { id -> scope.launchImageImportAction { service.removeImage(id) } },
        onAnalyze = {
            selectedProviderId?.let { profileId ->
                scope.launchImageImportAction {
                    service.analyze(
                        providerProfileId = profileId,
                        localeTag = if (s.languageCode == "pt") "pt-BR" else "en-US",
                        defaultCurrency = "BRL",
                    )
                }
            }
        },
        onCancelAnalysis = { scope.launchImageImportAction { service.cancelAnalysis() } },
        onRetryAnalysis = { scope.launchImageImportAction { service.retryAnalysis() } },
        onSetSelected = { id, selected -> scope.launchImageImportAction { service.setCandidateSelected(id, selected) } },
        onSelectAll = { selected ->
            scope.launchImageImportAction {
                state.candidates.forEach { service.setCandidateSelected(it.candidate.id, selected) }
            }
        },
        onUpdateCandidate = { id, edit -> scope.launchImageImportAction { service.updateCandidate(id, edit) } },
        onAddCandidate = { edit -> scope.launchImageImportAction { service.addCandidate(edit) } },
        onDeleteCandidate = { id -> scope.launchImageImportAction { service.deleteCandidate(id) } },
        onApplyCategory = { categoryId -> scope.launchImageImportAction { service.applyCategoryToSelected(categoryId) } },
        onApplyPayment = { payment -> scope.launchImageImportAction { service.applyPaymentMethodToSelected(payment) } },
        onConfirm = { scope.launchImageImportAction { service.confirmImport() } },
        onCancel = { showCancelConfirmation = true },
        onDone = {
            scope.launchImageImportAction(
                action = { service.clear() },
                onComplete = onBack,
            )
        },
    )

    if (showCancelConfirmation) {
        AppDialog(
            title = s.imageImportCancelTitle,
            onClose = { showCancelConfirmation = false },
            closeLabel = s.close,
            onDismissRequest = { showCancelConfirmation = false },
        ) {
            Text(
                s.imageImportCancelBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CompactPrimaryButton(
                    text = s.imageImportKeepReviewing,
                    compact = true,
                    subtle = true,
                    onClick = { showCancelConfirmation = false },
                )
                CompactPrimaryButton(
                    text = s.imageImportCancel,
                    danger = true,
                    compact = true,
                    onClick = {
                        showCancelConfirmation = false
                        scope.launchImageImportAction(
                            action = {
                                service.cancelImport()
                                service.clear()
                            },
                            onComplete = onBack,
                        )
                    },
                )
            }
        }
    }
}

private fun CoroutineScope.launchImageImportAction(
    onComplete: () -> Unit = {},
    action: suspend () -> Unit,
): Job =
    launch {
        withContext(Dispatchers.Default) { action() }
        onComplete()
    }

@Composable
private fun ImageImportScreen(
    s: AppStrings,
    state: ImageImportState,
    categories: List<Category>,
    selectedProviderId: String?,
    privacyAccepted: Boolean,
    onSelectedProviderChange: (String) -> Unit,
    onPrivacyAcceptedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onConfigureProvider: () -> Unit,
    onSelectImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onAnalyze: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onSetSelected: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onUpdateCandidate: (String, TransactionCandidateEdit) -> Unit,
    onAddCandidate: (TransactionCandidateEdit) -> Unit,
    onDeleteCandidate: (String) -> Unit,
    onApplyCategory: (String) -> Unit,
    onApplyPayment: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ImportHeader(s = s, onBack = onBack)
        when (state.stage) {
            ImageImportStage.ANALYZING ->
                AnalyzingContent(
                    s = s,
                    progress = state.progress,
                    failure = state.failure,
                    onCancel = onCancelAnalysis,
                    onRetry = onRetryAnalysis,
                )
            ImageImportStage.PREVIEW,
            ImageImportStage.IMPORTING,
            ->
                PreviewContent(
                    s = s,
                    state = state,
                    categories = categories,
                    onSetSelected = onSetSelected,
                    onSelectAll = onSelectAll,
                    onUpdateCandidate = onUpdateCandidate,
                    onAddCandidate = onAddCandidate,
                    onDeleteCandidate = onDeleteCandidate,
                    onApplyCategory = onApplyCategory,
                    onApplyPayment = onApplyPayment,
                    onConfirm = onConfirm,
                    onRetryAnalysis = onRetryAnalysis,
                    onCancel = onCancel,
                )
            ImageImportStage.COMPLETED -> CompletedContent(s = s, state = state, onDone = onDone)
            ImageImportStage.IDLE,
            ImageImportStage.SOURCES_SELECTED,
            ImageImportStage.CANCELLED,
            ->
                SourceContent(
                    s = s,
                    state = state,
                    selectedProviderId = selectedProviderId,
                    privacyAccepted = privacyAccepted,
                    onSelectedProviderChange = onSelectedProviderChange,
                    onPrivacyAcceptedChange = onPrivacyAcceptedChange,
                    onConfigureProvider = onConfigureProvider,
                    onSelectImages = onSelectImages,
                    onCaptureImage = onCaptureImage,
                    onRemoveImage = onRemoveImage,
                    onAnalyze = onAnalyze,
                    onRetryAnalysis = onRetryAnalysis,
                    onCancel = onCancel,
                )
        }
    }
}

@Composable
private fun ImportHeader(
    s: AppStrings,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconCompactButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = s.imageImportBack,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                s.imageImportTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                s.imageImportSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceContent(
    s: AppStrings,
    state: ImageImportState,
    selectedProviderId: String?,
    privacyAccepted: Boolean,
    onSelectedProviderChange: (String) -> Unit,
    onPrivacyAcceptedChange: (Boolean) -> Unit,
    onConfigureProvider: () -> Unit,
    onSelectImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onAnalyze: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onCancel: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SourcePicker(
                s = s,
                state = state,
                onSelectImages = onSelectImages,
                onCaptureImage = onCaptureImage,
                onRemoveImage = onRemoveImage,
            )
        }
        item {
            ProviderChoice(
                s = s,
                state = state,
                selectedProviderId = selectedProviderId,
                onSelectedProviderChange = onSelectedProviderChange,
                onConfigureProvider = onConfigureProvider,
            )
        }
        if (state.images.isNotEmpty() && state.compatibleProfiles.isNotEmpty()) {
            item {
                PrivacyConsent(
                    s = s,
                    checked = privacyAccepted,
                    onCheckedChange = onPrivacyAcceptedChange,
                )
            }
        }
        state.failure?.let { failure ->
            item {
                ImportFailureNotice(s = s, failure = failure)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactPrimaryButton(
                    text = s.imageImportAnalyze,
                    enabled = state.canAnalyze && selectedProviderId != null && privacyAccepted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAnalyze,
                )
                if (state.failure?.retryable == true && state.images.isNotEmpty()) {
                    CompactPrimaryButton(
                        text = s.imageImportRetryAnalysis,
                        compact = true,
                        subtle = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetryAnalysis,
                    )
                }
                if (state.images.isNotEmpty()) {
                    CompactPrimaryButton(
                        text = s.imageImportCancel,
                        danger = true,
                        compact = true,
                        subtle = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePicker(
    s: AppStrings,
    state: ImageImportState,
    onSelectImages: () -> Unit,
    onCaptureImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.imageImportSources, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tokenized(
                    s.imageImportSourceCount,
                    "count" to state.images.size,
                    "maximum" to state.maximumSelection,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.images.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(s.imageImportNoSourcesTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    s.imageImportNoSourcesBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.images.forEachIndexed { index, image ->
                SourceRow(
                    s = s,
                    image = image,
                    index = index,
                    onRemove = { onRemoveImage(image.id) },
                )
                if (index != state.images.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactPrimaryButton(
                text = s.imageImportSelectImages,
                enabled = state.canSelectImages && state.images.size < state.maximumSelection,
                leadingContent = {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = onSelectImages,
            )
            if (state.canCaptureImage) {
                CompactPrimaryButton(
                    text = s.imageImportTakePhoto,
                    subtle = true,
                    leadingContent = {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    onClick = onCaptureImage,
                )
            }
        }
        if (!state.canSelectImages) {
            Text(
                s.imageImportSourceUnavailable,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceRow(
    s: AppStrings,
    image: ImageImportImage,
    index: Int,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                image.displayName.ifBlank { s.imageImportSources },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${image.mimeType} · ${readableByteSize(image.byteSize)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconCompactButton(
            onClick = onRemove,
            icon = Icons.Default.RemoveCircleOutline,
            contentDescription = s.imageImportRemoveImage,
            compact = true,
            subtle = true,
        )
    }
}

@Composable
private fun ProviderChoice(
    s: AppStrings,
    state: ImageImportState,
    selectedProviderId: String?,
    onSelectedProviderChange: (String) -> Unit,
    onConfigureProvider: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.imageImportProvider, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.compatibleProfiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(s.imageImportNoProviderTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    s.imageImportNoProviderBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactPrimaryButton(
                    text = s.imageImportConfigureProvider,
                    compact = true,
                    onClick = onConfigureProvider,
                )
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.compatibleProfiles.forEach { profile ->
                    AppToggleChip(
                        selected = profile.id == selectedProviderId,
                        onClick = { onSelectedProviderChange(profile.id) },
                        text = "${profile.displayName} · ${profile.model}",
                    )
                }
            }
            Text(
                s.imageImportProviderHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyConsent(
    s: AppStrings,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(s.imageImportPrivacyTitle, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                s.imageImportPrivacyBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                )
                Text(s.imageImportPrivacyConsent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AnalyzingContent(
    s: AppStrings,
    progress: Float,
    failure: ImageImportFailure?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(s.imageImportAnalyzingTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            s.imageImportAnalyzingBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    },
        )
        failure?.let {
            ImportFailureNotice(s = s, failure = it)
            if (it.retryable) {
                CompactPrimaryButton(text = s.imageImportRetryAnalysis, onClick = onRetry)
            }
        }
        CompactPrimaryButton(
            text = s.imageImportCancelAnalysis,
            danger = true,
            subtle = true,
            onClick = onCancel,
        )
    }
}

@Composable
private fun PreviewContent(
    s: AppStrings,
    state: ImageImportState,
    categories: List<Category>,
    onSetSelected: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onUpdateCandidate: (String, TransactionCandidateEdit) -> Unit,
    onAddCandidate: (TransactionCandidateEdit) -> Unit,
    onDeleteCandidate: (String) -> Unit,
    onApplyCategory: (String) -> Unit,
    onApplyPayment: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onCancel: () -> Unit,
) {
    var expandedCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    if (state.candidates.isEmpty()) {
        EmptyPreviewContent(
            s = s,
            failure = state.failure,
            onAddCandidate = { onAddCandidate(blankCandidateEdit()) },
            onRetryAnalysis = onRetryAnalysis,
            onCancel = onCancel,
        )
        return
    }

    val selected = state.selectedCandidates
    val selectedWithErrors = selected.any { row -> row.issues.any { it.severity == CandidateIssueSeverity.ERROR } }
    val canConfirm =
        canConfirmImageImport(
            selectedCount = selected.size,
            selectedHasErrors = selectedWithErrors,
            stage = state.stage,
        )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.imageImportPreviewTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    s.imageImportPreviewBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.failure?.let { failure ->
            item { ImportFailureNotice(s = s, failure = failure) }
        }
        item {
            ReviewToolbar(
                s = s,
                state = state,
                categories = categories,
                onSelectAll = onSelectAll,
                onApplyCategory = onApplyCategory,
                onApplyPayment = onApplyPayment,
                onAddCandidate = {
                    onAddCandidate(blankCandidateEdit())
                },
            )
        }
        items(state.candidates, key = { it.candidate.id }) { row ->
            key(row.candidate.id, row.candidate.updatedAt) {
                CandidateReviewRow(
                    s = s,
                    reviewable = row,
                    categories = categories,
                    expanded = expandedCandidateId == row.candidate.id,
                    enabled = state.stage != ImageImportStage.IMPORTING,
                    onExpandChange = {
                        expandedCandidateId = if (expandedCandidateId == row.candidate.id) null else row.candidate.id
                    },
                    onSelectedChange = { selectedValue -> onSetSelected(row.candidate.id, selectedValue) },
                    onSave = { edit -> onUpdateCandidate(row.candidate.id, edit) },
                    onDelete = { onDeleteCandidate(row.candidate.id) },
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    s.imageImportAtomicHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactPrimaryButton(
                    text = if (state.stage == ImageImportStage.IMPORTING) s.imageImportImporting else s.imageImportConfirm,
                    enabled = canConfirm,
                    loading = state.stage == ImageImportStage.IMPORTING,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onConfirm,
                )
                CompactPrimaryButton(
                    text = s.imageImportCancel,
                    enabled = state.stage != ImageImportStage.IMPORTING,
                    danger = true,
                    compact = true,
                    subtle = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun EmptyPreviewContent(
    s: AppStrings,
    failure: ImageImportFailure?,
    onAddCandidate: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        failure?.let { ImportFailureNotice(s = s, failure = it) }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = s.imageImportEmptyPreviewTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = s.imageImportEmptyPreviewBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactPrimaryButton(
                text = s.imageImportRetryAnalysis,
                modifier = Modifier.fillMaxWidth(),
                onClick = onRetryAnalysis,
            )
            CompactPrimaryButton(
                text = s.imageImportAddRow,
                compact = true,
                subtle = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAddCandidate,
            )
            CompactPrimaryButton(
                text = s.imageImportCancel,
                danger = true,
                compact = true,
                subtle = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun ReviewToolbar(
    s: AppStrings,
    state: ImageImportState,
    categories: List<Category>,
    onSelectAll: (Boolean) -> Unit,
    onApplyCategory: (String) -> Unit,
    onApplyPayment: (String) -> Unit,
    onAddCandidate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tokenized(
                    s.imageImportSelectedCount,
                    "selected" to state.selectedCandidates.size,
                    "total" to state.candidates.size,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Row {
                CompactPrimaryButton(
                    text = if (state.selectedCandidates.size == state.candidates.size) s.imageImportSelectNone else s.imageImportSelectAll,
                    compact = true,
                    subtle = true,
                    onClick = { onSelectAll(state.selectedCandidates.size != state.candidates.size) },
                )
                IconCompactButton(
                    onClick = onAddCandidate,
                    icon = Icons.Default.Add,
                    contentDescription = s.imageImportAddRow,
                    compact = true,
                )
            }
        }
        if (state.selectedCandidates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionPicker(
                    label = s.imageImportApplyCategory,
                    currentLabel = s.imageImportApplyCategory,
                    options = categories.map { it.id to it.name },
                    onSelected = onApplyCategory,
                )
                OptionPicker(
                    label = s.imageImportApplyPayment,
                    currentLabel = s.imageImportApplyPayment,
                    options = paymentMethodOptions(s),
                    onSelected = onApplyPayment,
                )
            }
        }
    }
}

@Composable
private fun CandidateReviewRow(
    s: AppStrings,
    reviewable: ReviewableTransactionCandidate,
    categories: List<Category>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandChange: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
    onSave: (TransactionCandidateEdit) -> Unit,
    onDelete: () -> Unit,
) {
    val candidate = reviewable.candidate
    val errors = reviewable.issues.filter { it.severity == CandidateIssueSeverity.ERROR }
    val warnings = reviewable.issues.filter { it.severity == CandidateIssueSeverity.WARNING }
    val duplicate = candidate.duplicateCandidateId != null || candidate.duplicateExpenseId != null
    val categoryName =
        categories.firstOrNull { it.id == candidate.suggestedCategoryId }?.name
            ?: candidate.suggestedCategoryLabel?.takeIf(String::isNotBlank)?.let {
                tokenized(s.imageImportUnmatchedCategory, "category" to it)
            }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(8.dp),
        border =
            BorderStroke(
                1.dp,
                when {
                    errors.isNotEmpty() -> MaterialTheme.colorScheme.error.copy(alpha = 0.52f)
                    warnings.isNotEmpty() || duplicate -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.42f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = candidate.selected,
                    onCheckedChange = onSelectedChange,
                    enabled = enabled,
                    modifier =
                        Modifier
                            .size(34.dp)
                            .semantics {
                                contentDescription = candidateSelectionDescription(candidate, s)
                            },
                )
                Column(
                    modifier = Modifier.weight(1f).clickable(enabled = enabled, onClick = onExpandChange),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        candidate.description ?: s.noDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            candidate.amountCents?.let(::formatBrl),
                            candidate.occurredDate?.toString(),
                            categoryName,
                            candidate.accountOrPaymentMethod
                                ?.takeIf(String::isNotBlank)
                                ?.let { paymentMethodLabel(it, s) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (errors.isNotEmpty()) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = s.imageImportNeedsReview, tint = MaterialTheme.colorScheme.error)
                } else if (warnings.isNotEmpty() || duplicate) {
                    Icon(Icons.Default.WarningAmber, contentDescription = s.imageImportWarning, tint = MaterialTheme.colorScheme.tertiary)
                }
                IconCompactButton(
                    onClick = onExpandChange,
                    icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = s.imageImportEditRow,
                    compact = true,
                )
            }
            CandidateSignals(s = s, reviewable = reviewable)
            AnimatedVisibility(visible = expanded) {
                CandidateEditor(
                    s = s,
                    candidate = candidate,
                    issues = reviewable.issues,
                    categories = categories,
                    enabled = enabled,
                    onSave = onSave,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun CandidateSignals(
    s: AppStrings,
    reviewable: ReviewableTransactionCandidate,
) {
    val candidate = reviewable.candidate
    val duplicate = candidate.duplicateCandidateId != null || candidate.duplicateExpenseId != null
    val lowConfidence = candidate.lowConfidenceFields.isNotEmpty() || (candidate.confidence != null && candidate.confidence < 0.65)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (lowConfidence) {
            val fields =
                buildSet {
                    addAll(candidate.lowConfidenceFields)
                    if (candidate.confidence != null && candidate.confidence < 0.65) add("confidence")
                }
            SignalLine(
                icon = Icons.Default.WarningAmber,
                text =
                    tokenized(
                        s.imageImportLowConfidenceFields,
                        "fields" to imageImportLowConfidenceFieldLabels(fields, s).joinToString(", "),
                    ),
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            candidate.confidence?.let {
                Text(
                    tokenized(s.imageImportConfidence, "value" to (it * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (duplicate) {
            SignalLine(
                icon = Icons.Default.WarningAmber,
                text = s.imageImportDuplicateWarning,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        reviewable.issues
            .distinctBy { it.code }
            .filterNot { issue ->
                (lowConfidence && issue.code == CandidateIssueCode.LOW_CONFIDENCE) ||
                    (duplicate && issue.code == CandidateIssueCode.POSSIBLE_DUPLICATE)
            }.forEach { issue ->
                SignalLine(
                    icon = if (issue.severity == CandidateIssueSeverity.ERROR) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                    text = imageImportIssueLabel(issue.code, s),
                    color =
                        if (issue.severity == CandidateIssueSeverity.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                )
            }
        candidate.warnings
            .filter { it.isNotBlank() }
            .filterNot { duplicate && it.trim().equals("possible-duplicate", ignoreCase = true) }
            .distinct()
            .forEach { warning ->
                SignalLine(
                    icon = Icons.Default.WarningAmber,
                    text = imageImportWarningLabel(warning, s),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
    }
}

@Composable
private fun SignalLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(13.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CandidateEditor(
    s: AppStrings,
    candidate: TransactionCandidate,
    issues: List<com.samluiz.gyst.domain.model.CandidateValidationIssue>,
    categories: List<Category>,
    enabled: Boolean,
    onSave: (TransactionCandidateEdit) -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember { mutableStateOf(candidate.description.orEmpty()) }
    var centsDigits by remember { mutableStateOf(candidate.amountCents?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(candidate.currency.orEmpty()) }
    var date by remember { mutableStateOf(candidate.occurredDate?.toString().orEmpty()) }
    var time by remember { mutableStateOf(candidate.occurredTime.orEmpty()) }
    var type by remember { mutableStateOf(candidate.transactionType) }
    var categoryId by remember { mutableStateOf(candidate.suggestedCategoryId) }
    var payment by remember { mutableStateOf(candidate.accountOrPaymentMethod) }
    var installmentIndex by remember { mutableStateOf(candidate.installmentIndex?.toString().orEmpty()) }
    var installmentTotal by remember { mutableStateOf(candidate.installmentTotal?.toString().orEmpty()) }
    var note by remember { mutableStateOf(candidate.note.orEmpty()) }

    fun hasError(field: String): Boolean = issues.any { it.severity == CandidateIssueSeverity.ERROR && it.field == field }

    fun hasLowConfidence(field: String): Boolean = field in candidate.lowConfidenceFields

    fun lowConfidenceHint(field: String): String? = s.imageImportLowConfidenceFieldHint.takeIf { hasLowConfidence(field) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        CompactInput(
            value = description,
            onValueChange = { description = it },
            label = s.description,
            isError = hasError("description"),
            isWarning = hasLowConfidence("description"),
            supportingText = lowConfidenceHint("description"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactMoneyInput(
                centsDigits = centsDigits,
                onCentsDigitsChange = { centsDigits = it },
                label = s.amount,
                isError = hasError("amount"),
                isWarning = hasLowConfidence("amount"),
                supportingText = lowConfidenceHint("amount"),
                modifier = Modifier.weight(1f),
            )
            CompactInput(
                value = currency,
                onValueChange = { currency = it },
                label = s.imageImportCurrency,
                isError = hasError("currency"),
                isWarning = hasLowConfidence("currency"),
                supportingText = lowConfidenceHint("currency"),
                modifier = Modifier.width(86.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactInput(
                value = date,
                onValueChange = { date = it },
                label = s.imageImportDate,
                isError = hasError("date"),
                keyboardType = KeyboardType.Number,
                isWarning = hasLowConfidence("date"),
                supportingText = lowConfidenceHint("date"),
                modifier = Modifier.weight(1f),
            )
            CompactInput(
                value = time,
                onValueChange = { time = it },
                label = s.imageImportTime,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.width(90.dp),
            )
        }
        Text(s.imageImportTransactionType, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CandidateTransactionType.entries.forEach { option ->
                AppToggleChip(
                    selected = type == option,
                    onClick = { type = option },
                    text = candidateTypeLabel(option, s),
                )
            }
        }
        if (hasLowConfidence("transactionType")) {
            FieldReviewHint(s.imageImportLowConfidenceFieldHint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPicker(
                label = s.category,
                currentLabel =
                    categories.firstOrNull { it.id == categoryId }?.name
                        ?: candidate.suggestedCategoryLabel?.takeIf(String::isNotBlank)?.let {
                            tokenized(s.imageImportUnmatchedCategory, "category" to it)
                        }
                        ?: s.selectCategory,
                options = categories.map { it.id to it.name },
                isError = hasError("category"),
                isWarning = hasLowConfidence("category"),
                supportingText = lowConfidenceHint("category"),
                modifier = Modifier.weight(1f),
                onSelected = { categoryId = it },
            )
            OptionPicker(
                label = s.imageImportPaymentMethod,
                currentLabel = paymentMethodLabel(payment, s),
                options = paymentMethodOptions(s),
                isError = hasError("paymentMethod"),
                isWarning = hasLowConfidence("paymentMethod") || hasLowConfidence("accountOrPaymentMethod"),
                supportingText =
                    lowConfidenceHint("paymentMethod")
                        ?: lowConfidenceHint("accountOrPaymentMethod"),
                modifier = Modifier.weight(1f),
                onSelected = { payment = it },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactInput(
                value = installmentIndex,
                onValueChange = { installmentIndex = it.filter(Char::isDigit) },
                label = s.imageImportInstallmentCurrent,
                keyboardType = KeyboardType.Number,
                isError = hasError("installment"),
                isWarning = hasLowConfidence("installmentIndex"),
                supportingText = lowConfidenceHint("installmentIndex"),
                modifier = Modifier.weight(1f),
            )
            CompactInput(
                value = installmentTotal,
                onValueChange = { installmentTotal = it.filter(Char::isDigit) },
                label = s.imageImportInstallmentTotal,
                keyboardType = KeyboardType.Number,
                isError = hasError("installment"),
                isWarning = hasLowConfidence("installmentTotal"),
                supportingText = lowConfidenceHint("installmentTotal"),
                modifier = Modifier.weight(1f),
            )
        }
        CompactInput(
            value = note,
            onValueChange = { note = it },
            label = s.imageImportNote,
            isWarning = hasLowConfidence("note"),
            supportingText = lowConfidenceHint("note"),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompactPrimaryButton(
                text = s.imageImportDeleteRow,
                enabled = enabled,
                danger = true,
                compact = true,
                subtle = true,
                leadingContent = {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                onClick = onDelete,
            )
            CompactPrimaryButton(
                text = s.imageImportSaveRow,
                enabled = enabled,
                compact = true,
                leadingContent = {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                onClick = {
                    onSave(
                        TransactionCandidateEdit(
                            description = description,
                            amountCents = centsDigits.toLongOrNull(),
                            currency = currency,
                            occurredDate = parseImportDate(date),
                            occurredTime = time,
                            timeZoneId = candidate.timeZoneId,
                            transactionType = type,
                            suggestedCategoryId = categoryId,
                            accountOrPaymentMethod = payment,
                            installmentIndex = installmentIndex.toIntOrNull(),
                            installmentTotal = installmentTotal.toIntOrNull(),
                            note = note,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun OptionPicker(
    label: String,
    currentLabel: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isWarning: Boolean = false,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = options.isNotEmpty()) { expanded = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                border =
                    BorderStroke(
                        1.dp,
                        if (isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                        } else if (isWarning) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                        },
                    ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        },
                    )
                }
            }
        }
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun FieldReviewHint(text: String) {
    SignalLine(
        icon = Icons.Default.WarningAmber,
        text = text,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun CompletedContent(
    s: AppStrings,
    state: ImageImportState,
    onDone: () -> Unit,
) {
    val summary = state.summary
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 36.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(s.imageImportCompletedTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            s.imageImportCompletedBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summary?.let {
            Text(
                tokenized(s.imageImportImportedCount, "count" to it.importedCount),
                style = MaterialTheme.typography.titleMedium,
            )
            if (it.alreadyImportedCount > 0) {
                Text(
                    tokenized(s.imageImportAlreadyImportedCount, "count" to it.alreadyImportedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        CompactPrimaryButton(text = s.imageImportDone, modifier = Modifier.fillMaxWidth(), onClick = onDone)
    }
}

@Composable
private fun ImportFailureNotice(
    s: AppStrings,
    failure: ImageImportFailure,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = s.errorTitle,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Text(
            imageImportFailureLabel(failure, s),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun imageImportFailureLabel(
    failure: ImageImportFailure,
    s: AppStrings,
): String {
    val base =
        when (failure.code) {
            ImageImportFailureCode.NO_IMAGES -> s.imageImportFailureNoImages
            ImageImportFailureCode.IMAGE_SOURCE -> s.imageImportSourceUnavailable
            ImageImportFailureCode.PROVIDER_NOT_FOUND,
            ImageImportFailureCode.PROVIDER_NOT_CONFIGURED,
            ImageImportFailureCode.UNSUPPORTED_PROVIDER_CAPABILITY,
            -> s.imageImportFailureProvider
            ImageImportFailureCode.AUTHENTICATION -> s.imageImportFailureAuth
            ImageImportFailureCode.RATE_LIMITED -> s.imageImportFailureRateLimit
            ImageImportFailureCode.NETWORK ->
                if (failure.httpStatusCode != null) {
                    s.imageImportFailureRejected
                } else {
                    s.imageImportFailureNetwork
                }
            ImageImportFailureCode.PROVIDER_UNAVAILABLE -> s.imageImportFailureUnavailable
            ImageImportFailureCode.TIMEOUT,
            -> s.imageImportFailureNetwork
            ImageImportFailureCode.INVALID_STRUCTURED_RESPONSE -> s.imageImportFailureInvalidResponse
            ImageImportFailureCode.VALIDATION -> s.imageImportFailureValidation
            ImageImportFailureCode.DATABASE,
            ImageImportFailureCode.DUPLICATE_OPERATION,
            -> s.imageImportFailureDatabase
            ImageImportFailureCode.INTERRUPTED -> s.imageImportFailureInterrupted
            ImageImportFailureCode.CANCELLED -> s.imageImportFailureCancelled
        }
    if (failure.httpStatusCode == null) return base
    val diagnostic =
        buildList {
            add("HTTP ${failure.httpStatusCode}")
            failure.providerErrorCode?.let(::add)
        }.joinToString(" · ")
    return buildString {
        append(base)
        append(" (")
        append(diagnostic)
        append(")")
        failure.providerMessage?.let {
            append(' ')
            append(it)
        }
    }
}

internal fun imageImportIssueLabel(
    code: CandidateIssueCode,
    s: AppStrings,
): String =
    when (code) {
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
        CandidateIssueCode.POSSIBLE_DUPLICATE -> s.imageImportDuplicateWarning
    }

internal fun imageImportWarningLabel(
    warning: String,
    s: AppStrings,
): String =
    when (warning.trim().lowercase()) {
        "ambiguous-source-image" -> s.imageImportWarningAmbiguousSource
        "source-image-seen-before" -> s.imageImportWarningRepeatedSource
        "possible-duplicate" -> s.imageImportDuplicateWarning
        else -> s.imageImportWarningExtractedValue
    }

internal fun imageImportLowConfidenceFieldLabels(
    fields: Set<String>,
    s: AppStrings,
): List<String> {
    val knownFields =
        listOf(
            "description" to s.imageImportFieldDescription,
            "amount" to s.imageImportFieldAmount,
            "currency" to s.imageImportFieldCurrency,
            "date" to s.imageImportFieldDate,
            "transactionType" to s.imageImportFieldType,
            "confidence" to s.imageImportFieldConfidence,
        )
    val knownKeys = knownFields.mapTo(mutableSetOf(), Pair<String, String>::first)
    return buildList {
        knownFields.filter { (field, _) -> field in fields }.forEach { (_, label) -> add(label) }
        if (fields.any { it !in knownKeys }) {
            add(s.imageImportNeedsReview)
        }
    }
}

internal fun candidateSelectionDescription(
    candidate: TransactionCandidate,
    s: AppStrings,
): String =
    tokenized(
        s.imageImportRowSelection,
        "description" to candidate.description?.takeIf(String::isNotBlank).orEmpty().ifBlank { s.noDescription },
        "amount" to candidate.amountCents?.let(::formatBrl).orEmpty().ifBlank { s.imageImportUnknownValue },
        "date" to candidate.occurredDate?.toString().orEmpty().ifBlank { s.imageImportUnknownValue },
    )

internal fun candidateTypeLabel(
    type: CandidateTransactionType,
    s: AppStrings,
): String =
    when (type) {
        CandidateTransactionType.EXPENSE -> s.imageImportTypeExpense
        CandidateTransactionType.INCOME -> s.imageImportTypeIncome
        CandidateTransactionType.TRANSFER -> s.imageImportTypeTransfer
        CandidateTransactionType.REFUND -> s.imageImportTypeRefund
        CandidateTransactionType.UNKNOWN -> s.imageImportTypeUnknown
    }

internal fun parseImportDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()

internal fun canConfirmImageImport(
    selectedCount: Int,
    selectedHasErrors: Boolean,
    stage: ImageImportStage,
): Boolean = selectedCount > 0 && !selectedHasErrors && stage == ImageImportStage.PREVIEW

private fun blankCandidateEdit(): TransactionCandidateEdit =
    TransactionCandidateEdit(
        description = null,
        amountCents = null,
        currency = "BRL",
        occurredDate = null,
        occurredTime = null,
        timeZoneId = null,
        transactionType = CandidateTransactionType.EXPENSE,
        suggestedCategoryId = null,
        accountOrPaymentMethod = null,
        installmentIndex = null,
        installmentTotal = null,
        note = null,
    )

private fun paymentMethodOptions(s: AppStrings): List<Pair<String, String>> =
    listOf(
        PaymentMethod.PIX.name to s.imageImportPaymentPix,
        PaymentMethod.DEBIT.name to s.imageImportPaymentDebit,
        PaymentMethod.CASH.name to s.imageImportPaymentCash,
        PaymentMethod.TRANSFER.name to s.imageImportPaymentTransfer,
    )

internal fun paymentMethodLabel(
    payment: String?,
    s: AppStrings,
): String {
    val normalized = payment?.trim().orEmpty()
    if (normalized.isEmpty()) return s.imageImportPaymentUnknown
    return paymentMethodOptions(s).firstOrNull { it.first.equals(normalized, ignoreCase = true) }?.second ?: normalized
}

private fun tokenized(
    template: String,
    vararg values: Pair<String, Any>,
): String = values.fold(template) { text, (key, value) -> text.replace("{$key}", value.toString()) }

private fun readableByteSize(bytes: Long): String =
    when {
        bytes <= 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }
