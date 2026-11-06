package to.wim.timetracker.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import to.wim.timetracker.ui.components.parseWorkspaceColor
import to.wim.timetracker.ui.theme.DragHandle
import to.wim.timetracker.ui.theme.readableWidth
import to.wim.timetracker.ui.theme.ReorderableColumn
import to.wim.timetracker.ui.theme.EmptyState
import to.wim.timetracker.ui.theme.Motion
import to.wim.timetracker.ui.theme.SkeletonBlock
import to.wim.timetracker.ui.theme.SkeletonLine
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import to.wim.timetracker.ui.theme.AppShapes
import androidx.compose.ui.semantics.semantics
import to.wim.timetracker.ui.theme.rememberReducedMotion
import to.wim.timetracker.ui.theme.workspaceAccentColor
import to.wim.timetracker.ui.theme.workspaceSolidFillColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import to.wim.timetracker.viewmodel.DescriptionAutoSave
import to.wim.timetracker.viewmodel.SetupStep
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.data.models.Preset
import to.wim.timetracker.data.models.RecentEntryInfo
import to.wim.timetracker.data.models.RunningTimerInfo
import to.wim.timetracker.ui.components.AppTimePickerDialog
import to.wim.timetracker.ui.components.EditTimerModal
import to.wim.timetracker.ui.components.PresetEditorScreen
import androidx.activity.compose.BackHandler
import to.wim.timetracker.ui.components.ExtraFieldInput
import to.wim.timetracker.ui.components.RunningGlow
import to.wim.timetracker.ui.components.TimerHero
import to.wim.timetracker.viewmodel.TimerViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun TimerScreen(
    uiState: TimerViewModel.UiState,
    onWorkspaceSelect: (String) -> Unit,
    onProjectSelect: (String?) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onExtraValueChange: (String, String) -> Unit,
    onStart: (Long?) -> Unit,
    onStop: (Long?, String?) -> Unit,
    onUpdateStartTime: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onClearError: () -> Unit,
    onRetryStop: () -> Unit,
    onLoadRunningTimer: (RunningTimerInfo) -> Unit,
    onStopOtherRunningTimer: (RunningTimerInfo) -> Unit,
    onPlusButtonClicked: () -> Unit,
    onRefreshAll: () -> Unit,
    onOpenEditTimer: () -> Unit,
    onCloseEditTimer: () -> Unit,
    onQuickSaveDescription: (String) -> Unit = {},
    onUpdateTimerDetails: (String?, String, Map<String, String>) -> Unit,
    onConfirmDelete: (String, String, Boolean) -> Unit,
    onCancelDelete: () -> Unit,
    onDeleteEntry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onApplyPreset: (String, Preset) -> Unit = { _, _ -> },
    onStartFromRecentEntry: (RecentEntryInfo) -> Unit = {},
    onDismissDeletedPage: () -> Unit = {},
    onClearInfoToast: () -> Unit = {},
    onConfirmAlreadyRunning: () -> Unit,
    onCancelAlreadyRunning: () -> Unit,
    onNewPreset: () -> Unit,
    onEditPreset: (String, String) -> Unit,
    onPresetEditorWorkspace: (String) -> Unit,
    onPresetEditorName: (String) -> Unit,
    onPresetEditorPageTitle: (String) -> Unit,
    onPresetEditorExtra: (String, String) -> Unit,
    onSavePresetEditor: () -> Unit,
    onClosePresetEditor: () -> Unit,
    onDeletePresetEditor: () -> Unit,
    onMovePreset: (workspaceId: String, from: Int, to: Int) -> Unit
) {
    if (uiState.showAlreadyRunningWarning) {
        AlertDialog(
            onDismissRequest = onCancelAlreadyRunning,
            title = { Text(stringResource(R.string.warn_already_running_title)) },
            text = { Text(stringResource(R.string.warn_already_running_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmAlreadyRunning) {
                    Text(stringResource(R.string.btn_stop_and_start_new))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelAlreadyRunning) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    var showManagePresetsDialog by remember { mutableStateOf(false) }
    // The editor opens over the manage dialog and returns to it when closed.
    if (showManagePresetsDialog && uiState.presetEditor == null) {
        ManagePresetsDialog(
            workspaces = uiState.workspaces,
            onNewPreset = onNewPreset,
            onEditPreset = onEditPreset,
            onMovePreset = onMovePreset,
            onDismiss = { showManagePresetsDialog = false }
        )
    }

    uiState.deleteConfirmInfo?.let { info ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_message, info.title)) },
            confirmButton = {
                TextButton(onClick = onDeleteEntry) {
                    Text(stringResource(R.string.confirm_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (uiState.deletedPageDialogVisible) {
        AlertDialog(
            onDismissRequest = onDismissDeletedPage,
            title = { Text(stringResource(R.string.deleted_page_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.deleted_page_body))
                    uiState.deletedPageDialogTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        Text("“$title”", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.deleted_page_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissDeletedPage) {
                    Text(stringResource(R.string.deleted_page_confirm))
                }
            }
        )
    }

    uiState.infoToast?.let { msg ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val displayMsg = localisedMessage(msg)
        LaunchedEffect(msg) {
            android.widget.Toast.makeText(context, displayMsg, android.widget.Toast.LENGTH_SHORT).show()
            onClearInfoToast()
        }
    }

    uiState.presetEditor?.let { editor ->
        PresetEditorScreen(
            editor = editor,
            workspaces = uiState.workspaces,
            onWorkspaceChange = onPresetEditorWorkspace,
            onNameChange = onPresetEditorName,
            onPageTitleChange = onPresetEditorPageTitle,
            onExtraChange = onPresetEditorExtra,
            onSave = onSavePresetEditor,
            onDismiss = onClosePresetEditor,
            errorText = { localisedMessage(it) },
            onDelete = if (editor.presetId != null) onDeletePresetEditor else null
        )
        BackHandler(onBack = onClosePresetEditor)
        return
    }

    if (uiState.editTimerOpen && uiState.activeTimer != null) {
        EditTimerModal(
            uiState = uiState,
            onDismiss = onCloseEditTimer,
            onSave = onUpdateTimerDetails
        )
        return
    }

    AnimatedContent(
        targetState = uiState.running && uiState.activeTimer != null,
        transitionSpec = {
            fadeIn(Motion.fadeSpec(delayMillis = 90)) togetherWith fadeOut(Motion.fadeSpec(durationMillis = 90))
        },
        label = "timer-state"
    ) { running ->
        if (running) {
            RunningView(
                uiState = uiState,
                onStop = onStop,
                onUpdateStartTime = onUpdateStartTime,
                onNavigateToSettings = onNavigateToSettings,
                onRetryStop = onRetryStop,
                onLoadRunningTimer = onLoadRunningTimer,
                onStopOtherRunningTimer = onStopOtherRunningTimer,
                onPlusButtonClicked = onPlusButtonClicked,
                onRefreshAll = onRefreshAll,
                onQuickSaveDescription = onQuickSaveDescription,
                onUpdateTimerDetails = onUpdateTimerDetails,
                onConfirmDelete = onConfirmDelete,
                onOpenUrl = onOpenUrl,
                onApplyPreset = onApplyPreset,
                onStartFromRecentEntry = onStartFromRecentEntry,
                onNewPresetClick = onNewPreset,
                onManagePresetsClick = { showManagePresetsDialog = true }
            )
        } else {
            IdleView(
                uiState = uiState,
                onWorkspaceSelect = onWorkspaceSelect,
                onDescriptionChange = onDescriptionChange,
                onExtraValueChange = onExtraValueChange,
                onStart = onStart,
                onNavigateToSettings = onNavigateToSettings,
                onClearError = onClearError,
                onLoadRunningTimer = onLoadRunningTimer,
                onStopOtherRunningTimer = onStopOtherRunningTimer,
                onRefreshAll = onRefreshAll,
                onConfirmDelete = onConfirmDelete,
                onOpenUrl = onOpenUrl,
                onApplyPreset = onApplyPreset,
                onStartFromRecentEntry = onStartFromRecentEntry,
                onNewPresetClick = onNewPreset,
                onManagePresetsClick = { showManagePresetsDialog = true }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleView(
    uiState: TimerViewModel.UiState,
    onWorkspaceSelect: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onExtraValueChange: (String, String) -> Unit,
    onStart: (Long?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onClearError: () -> Unit,
    onLoadRunningTimer: (RunningTimerInfo) -> Unit,
    onStopOtherRunningTimer: (RunningTimerInfo) -> Unit,
    onRefreshAll: () -> Unit,
    onConfirmDelete: (String, String, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onApplyPreset: (String, Preset) -> Unit = { _, _ -> },
    onStartFromRecentEntry: (RecentEntryInfo) -> Unit = {},
    onNewPresetClick: () -> Unit,
    onManagePresetsClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val is12h = uiState.timeFormat == "12h"
    // A start time picked for a timer the user forgot to start; empty means "now". Only the
    // Start button uses it: presets, shortcuts and recent entries always start now.
    var pickedStartMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var showStartPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // Edge-to-edge: the keyboard does not shrink the screen, so the content makes room for it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TimerTopBar {
                RefreshIconButton(
                    loading = uiState.runningTimersLoading || uiState.recentEntriesLoading,
                    onClick = onRefreshAll
                )
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.title_settings))
                }
            }
        }
    ) { padding ->
        // Until the API key and a workspace are set, the timer can't do anything: the whole
        // screen says what to set up instead (DESIGN.md, "Empty and setup states").
        if (!uiState.settingsLoaded) return@Scaffold
        SetupStep.of(uiState.settingsLoaded, uiState.signedIn, uiState.workspaces.size)?.let { step ->
            SetupNeeded(step, onNavigateToSettings, Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.error?.let { err ->
                val message = localisedMessage(err)
                Card(shape = AppShapes.Group, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = onClearError) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.title_dismiss))
                        }
                    }
                }
            }

            PresetChips(
                workspaces = uiState.workspaces,
                signedIn = uiState.signedIn,
                onApplyPreset = onApplyPreset,
                onManageClick = onManagePresetsClick,
                onNewClick = onNewPresetClick
            )

            val selectedWorkspace = uiState.workspaces.firstOrNull { it.id == uiState.selectedWorkspaceId }

            WorkspaceTitle(
                workspaces = uiState.workspaces,
                selected = selectedWorkspace,
                onSelect = onWorkspaceSelect,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
            )

            // Starting while the fields are still loading would drop every extra
            // value from the new entry, so the button waits for them.
            val canStart = uiState.signedIn &&
                    uiState.selectedWorkspaceId != null &&
                    !uiState.extrasLoading &&
                    !uiState.isStarting
            val buttonColor = workspaceSolidFillColor(selectedWorkspace?.color) ?: TimerColors.Start

            TimerHeroWithAction(
                elapsedText = formatElapsed(0L),
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.outline,
                running = false,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                TimerActionButton(
                    label = stringResource(R.string.btn_start),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { onStart(pickedStartMs) },
                    enabled = canStart,
                    loading = uiState.isStarting,
                    containerColor = buttonColor,
                    contentColor = TimerColors.OnAction
                )
            }

            TimerDetailsCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                // Like required extra fields, a required description is enforced when
                // the timer is stopped — starting stays a single tap.
                val descriptionRequired = selectedWorkspace?.descriptionRequired == true
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = descriptionRequired && uiState.description.isBlank(),
                    label = {
                        Text(stringResource(R.string.placeholder_description) + if (descriptionRequired) " *" else "")
                    },
                    placeholder = { Text(stringResource(R.string.label_description)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { })
                )

                TimeField(
                    label = stringResource(R.string.label_start_time),
                    value = pickedStartMs?.let { formatHHMM(isoAt(it), is12h) } ?: "",
                    placeholder = stringResource(R.string.time_now),
                    pickerDescription = stringResource(R.string.title_select_start_time),
                    onPick = { showStartPicker = true },
                    onClear = pickedStartMs?.let { { pickedStartMs = null } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showStartPicker) {
                    val now = java.util.Calendar.getInstance().apply {
                        pickedStartMs?.let { ms -> timeInMillis = ms }
                    }
                    AppTimePickerDialog(
                        title = stringResource(R.string.title_select_start_time),
                        initialHour = now.get(java.util.Calendar.HOUR_OF_DAY),
                        initialMinute = now.get(java.util.Calendar.MINUTE),
                        is24Hour = !is12h,
                        onConfirm = { hour, minute ->
                            showStartPicker = false
                            pickedStartMs = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, minute)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        },
                        onDismiss = { showStartPicker = false }
                    )
                }

                if (uiState.extrasLoading) {
                    ExtraFieldsSkeleton(selectedWorkspace?.extraProps.orEmpty())
                }

                uiState.extraFieldDefs.forEach { def ->
                    ExtraFieldInput(
                        fieldDef = def,
                        value = uiState.extraValues[def.propName] ?: "",
                        onValueChange = { onExtraValueChange(def.propName, it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Loading the fields can fail (offline, rate limited) or find properties
                // that no longer exist in the database. Both used to just show fewer
                // fields than configured, with no way to tell that from "correct".
                uiState.extrasError?.let { err ->
                    Card(shape = AppShapes.Group, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                localisedMessage(err),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            uiState.selectedWorkspaceId?.let { wsId ->
                                TextButton(onClick = { onWorkspaceSelect(wsId) }) {
                                    Text(stringResource(R.string.btn_retry))
                                }
                            }
                        }
                    }
                }
            }

            OtherRunningTimers(
                timers = uiState.runningTimers,
                loading = uiState.runningTimersLoading,
                error = uiState.runningTimersError,
                activePageId = null,
                onLoad = onLoadRunningTimer,
                onStop = onStopOtherRunningTimer,
                onDelete = { rt -> onConfirmDelete(rt.pageId, rt.title, true) },
                onOpenUrl = onOpenUrl,
                showNotionLink = uiState.showNotionLink,
                showArchiveAction = uiState.showArchiveAction
            )

            RecentEntriesSection(
                entries = uiState.recentEntries,
                loading = uiState.recentEntriesLoading,
                workspaces = uiState.workspaces,
                onDelete = { re -> onConfirmDelete(re.pageId, re.title, false) },
                onOpenUrl = onOpenUrl,
                onPlay = onStartFromRecentEntry,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
                showNotionLink = uiState.showNotionLink,
                showArchiveAction = uiState.showArchiveAction
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RunningView(
    uiState: TimerViewModel.UiState,
    onStop: (Long?, String?) -> Unit,
    onUpdateStartTime: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onRetryStop: () -> Unit,
    onLoadRunningTimer: (RunningTimerInfo) -> Unit,
    onStopOtherRunningTimer: (RunningTimerInfo) -> Unit,
    onPlusButtonClicked: () -> Unit,
    onRefreshAll: () -> Unit,
    onQuickSaveDescription: (String) -> Unit = {},
    onUpdateTimerDetails: (String?, String, Map<String, String>) -> Unit,
    onConfirmDelete: (String, String, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onApplyPreset: (String, Preset) -> Unit = { _, _ -> },
    onStartFromRecentEntry: (RecentEntryInfo) -> Unit = {},
    onNewPresetClick: () -> Unit = {},
    onManagePresetsClick: () -> Unit = {}
) {
    val timer = uiState.activeTimer ?: return
    val workspace = uiState.workspaces.firstOrNull { it.id == timer.workspaceId }
    val is12h = uiState.timeFormat == "12h"

    var startTimeInput by remember(timer.roundedStart, uiState.timeFormat) { mutableStateOf(formatHHMM(timer.roundedStart, is12h)) }
    var endTimeInput by remember { mutableStateOf("") }

    // Declared here rather than next to its text field: stopping has to hand
    // the draft over so a description typed but not yet saved is not lost.
    var titleDraft by remember(timer.notionPageId) { mutableStateOf(timer.description) }
    var titleFocused by remember { mutableStateOf(false) }
    // A save (or a refresh from Notion) updates timer.description. While the user is typing,
    // that must not replace what they typed since; once they leave the field it may.
    LaunchedEffect(timer.description) {
        if (!titleFocused) titleDraft = timer.description
    }
    // The description is saved by itself a moment after the user stops typing. Stopping
    // hands the draft over itself, so no save runs alongside it.
    LaunchedEffect(titleDraft, timer.description, uiState.isStopping) {
        if (uiState.isStopping || !DescriptionAutoSave.shouldSave(titleDraft, timer.description)) return@LaunchedEffect
        kotlinx.coroutines.delay(DescriptionAutoSave.DELAY_MS)
        onQuickSaveDescription(titleDraft.trim())
    }

    val focusManager = LocalFocusManager.current

    var showTimeErrorDialog by remember { mutableStateOf(false) }
    var pendingEndTimeMs by remember { mutableStateOf(0L) }

    if (showTimeErrorDialog) {
        AlertDialog(
            onDismissRequest = { showTimeErrorDialog = false },
            title = { Text(stringResource(R.string.dialog_time_error_title)) },
            text = { Text(stringResource(R.string.dialog_time_error_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTimeErrorDialog = false
                        val nextDayMs = pendingEndTimeMs + 24 * 3600 * 1000L
                        onStop(nextDayMs, titleDraft)
                        endTimeInput = ""
                    }
                ) {
                    Text(stringResource(R.string.btn_next_day))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimeErrorDialog = false
                        endTimeInput = ""
                    }
                ) {
                    Text(stringResource(R.string.btn_edit))
                }
            }
        )
    }

    val context = LocalContext.current

    var pickerOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val showStartTimePicker = { pickerOpen = "start" }
    val showEndTimePicker = { pickerOpen = "end" }
    val onTimePicked: (Int, Int) -> String? = { hour, minute ->
        parseHHMMToISO(String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute), timer.roundedStart)
    }
    when (pickerOpen) {
        "start" -> {
            val odt = runCatching { OffsetDateTime.parse(timer.roundedStart) }.getOrNull() ?: OffsetDateTime.now()
            AppTimePickerDialog(
                title = stringResource(R.string.title_select_start_time),
                initialHour = odt.hour,
                initialMinute = odt.minute,
                is24Hour = !is12h,
                onConfirm = { hour, minute ->
                    pickerOpen = null
                    onTimePicked(hour, minute)?.let { iso ->
                        onUpdateStartTime(iso)
                        startTimeInput = formatHHMM(iso, is12h)
                    }
                },
                onDismiss = { pickerOpen = null }
            )
        }
        "end" -> {
            val cal = java.util.Calendar.getInstance()
            AppTimePickerDialog(
                title = stringResource(R.string.title_select_end_time),
                initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                initialMinute = cal.get(java.util.Calendar.MINUTE),
                is24Hour = !is12h,
                onConfirm = { hour, minute ->
                    pickerOpen = null
                    onTimePicked(hour, minute)?.let { iso -> endTimeInput = formatHHMM(iso, is12h) }
                },
                onDismiss = { pickerOpen = null }
            )
        }
    }

    val handleStopClick = {
        val manualEnd = endTimeInput.takeIf { it.isNotBlank() }?.let {
            runCatching { parseHHMMToMillis(it, timer.roundedStart) }.getOrNull()
        }
        val startMs = runCatching { OffsetDateTime.parse(timer.roundedStart).toInstant().toEpochMilli() }.getOrDefault(0L)
        if (manualEnd != null && startMs > 0 && manualEnd < startMs) {
            pendingEndTimeMs = manualEnd
            showTimeErrorDialog = true
        } else {
            onStop(manualEnd, titleDraft)
        }
    }

    val workspaceColor = workspace?.color?.ifBlank { null }?.let { parseWorkspaceColor(it) }
    val workspaceAccent = workspaceAccentColor(workspace?.color)
    // A running timer tints the screen: the workspace colour, or the app's own red.
    val scaffoldBg = lerp(
        MaterialTheme.colorScheme.background,
        workspaceColor ?: MaterialTheme.colorScheme.primaryContainer,
        if (workspaceColor != null) 0.20f else 0.45f
    )
    val scrollState = rememberScrollState()
    var heroCenter by remember { mutableStateOf<Offset?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(scaffoldBg)
            .onGloballyPositioned { screenOrigin = it.positionInRoot() }
    ) {
        RunningGlow(
            primary = workspaceAccent ?: MaterialTheme.colorScheme.primary,
            secondary = MaterialTheme.colorScheme.tertiary,
            center = heroCenter?.minus(screenOrigin),
            animate = !rememberReducedMotion(),
            modifier = Modifier.matchParentSize()
        )
        Scaffold(
            // Edge-to-edge: the keyboard does not shrink the screen, so the content makes room for it.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TimerTopBar {
                    IconButton(onClick = onPlusButtonClicked) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.start_new_timer))
                    }
                    RefreshIconButton(
                        loading = uiState.runningTimersLoading || uiState.recentEntriesLoading,
                        onClick = onRefreshAll
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.title_settings))
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PresetChips(
                    workspaces = uiState.workspaces,
                    signedIn = uiState.signedIn,
                    onApplyPreset = onApplyPreset,
                    onManageClick = onManagePresetsClick,
                    onNewClick = onNewPresetClick
                )

                // A running timer's workspace can't change, so here it is a plain title.
                WorkspaceTitle(
                    workspaces = uiState.workspaces,
                    selected = workspace,
                    onSelect = null,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
                )

                TimerHeroWithAction(
                    elapsedText = formatElapsed(uiState.elapsedSeconds),
                    container = lerp(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        workspaceColor ?: MaterialTheme.colorScheme.primaryContainer,
                        if (workspaceColor != null) 0.35f else 0.6f
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    running = true,
                    heroModifier = Modifier.onGloballyPositioned { heroCenter = it.boundsInRoot().center },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    TimerActionButton(
                        label = stringResource(R.string.btn_stop),
                        icon = Icons.Rounded.Stop,
                        onClick = handleStopClick,
                        enabled = !uiState.isStopping,
                        loading = uiState.isStopping,
                        containerColor = TimerColors.Stop,
                        contentColor = TimerColors.OnAction
                    )
                }

                uiState.stopError?.let { err ->
                    val errMsg = localisedMessage(err)
                    Card(shape = AppShapes.Group, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(errMsg, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!isValidationMessage(err)) {
                                TextButton(onClick = onRetryStop) { Text(stringResource(R.string.toast_stop_retry)) }
                            }
                        }
                    }
                }

                var localProjectId by remember(timer.notionPageId) { mutableStateOf(uiState.selectedProjectId) }
                var localExtras by remember(timer.notionPageId) { mutableStateOf(uiState.extraValues) }
                var pendingSave by remember(timer.notionPageId) { mutableStateOf(false) }
                LaunchedEffect(uiState.extraFieldDefs, uiState.selectedProjectId, uiState.extraValues, timer.notionPageId) {
                    // A refresh from Notion must not overwrite an edit that has not been
                    // saved yet (the save is debounced by 600 ms, the auto-refresh runs
                    // every 60 s — without this the value snapped back while typing).
                    if (pendingSave) return@LaunchedEffect
                    localProjectId = uiState.selectedProjectId
                    localExtras = uiState.extraValues
                }
                LaunchedEffect(localProjectId, localExtras, pendingSave) {
                    if (!pendingSave) return@LaunchedEffect
                    kotlinx.coroutines.delay(600)
                    onUpdateTimerDetails(localProjectId, timer.description, localExtras)
                    pendingSave = false
                }

                // The tinted screen shows through the card a little, so it reads as part of
                // the running timer while its fields stay legible.
                TimerDetailsCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)) {
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { titleFocused = it.isFocused },
                        singleLine = true,
                        isError = workspace?.descriptionRequired == true && titleDraft.isBlank(),
                        label = {
                            Text(stringResource(R.string.placeholder_description) + if (workspace?.descriptionRequired == true) " *" else "")
                        },
                        placeholder = { Text(stringResource(R.string.hint_timer_title)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            // Done saves right away, an emptied description too.
                            if (titleDraft.trim() != timer.description.trim()) onQuickSaveDescription(titleDraft.trim())
                            focusManager.clearFocus()
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = workspaceAccent ?: MaterialTheme.colorScheme.primary,
                            focusedLabelColor = workspaceAccent ?: MaterialTheme.colorScheme.primary
                        )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TimeField(
                                label = stringResource(R.string.label_start_time),
                                value = startTimeInput,
                                placeholder = null,
                                pickerDescription = stringResource(R.string.title_select_start_time),
                                onPick = showStartTimePicker,
                                modifier = Modifier.weight(1f)
                            )
                            TimeField(
                                label = stringResource(R.string.label_end_time),
                                value = endTimeInput,
                                placeholder = null,
                                pickerDescription = stringResource(R.string.title_select_end_time),
                                onPick = showEndTimePicker,
                                onClear = if (endTimeInput.isNotBlank()) ({ endTimeInput = "" }) else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            stringResource(R.string.hint_manual_end),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }

                    uiState.extraFieldDefs.forEach { def ->
                        ExtraFieldInput(
                            fieldDef = def,
                            value = localExtras[def.propName] ?: "",
                            onValueChange = { value ->
                                localExtras = localExtras + (def.propName to value)
                                pendingSave = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                OtherRunningTimers(
                    timers = uiState.runningTimers,
                    loading = uiState.runningTimersLoading,
                    error = uiState.runningTimersError,
                    activePageId = uiState.activeTimer?.notionPageId,
                    onLoad = onLoadRunningTimer,
                    onStop = onStopOtherRunningTimer,
                    onDelete = { rt -> onConfirmDelete(rt.pageId, rt.title, true) },
                    onOpenUrl = onOpenUrl,
                    showNotionLink = uiState.showNotionLink,
                    showArchiveAction = uiState.showArchiveAction
                )

                RecentEntriesSection(
                    entries = uiState.recentEntries,
                    loading = uiState.recentEntriesLoading,
                    workspaces = uiState.workspaces,
                    onDelete = { re -> onConfirmDelete(re.pageId, re.title, false) },
                    onOpenUrl = onOpenUrl,
                    onPlay = onStartFromRecentEntry,
                    dateFormat = uiState.dateFormat,
                    timeFormat = uiState.timeFormat,
                    showNotionLink = uiState.showNotionLink,
                    showArchiveAction = uiState.showArchiveAction
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The workspace as the screen's title: its colour dot and name in Headline Small. Idle
 * ([onSelect] set) it is a button that opens the workspace menu, each workspace with its dot;
 * while a timer runs it is plain text, since a running timer's workspace can't change.
 */
@Composable
private fun WorkspaceTitle(
    workspaces: List<to.wim.timetracker.data.models.Workspace>,
    selected: to.wim.timetracker.data.models.Workspace?,
    onSelect: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val anyColour = workspaces.any { workspaceAccentColor(it.color) != null }
    val dot = workspaceAccentColor(selected?.color)
    val name = selected?.name ?: stringResource(R.string.placeholder_select_workspace)
    val enabled = onSelect != null && workspaces.isNotEmpty()
    val changeLabel = stringResource(R.string.label_workspace)
    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .then(
                    if (enabled) Modifier
                        .clickable(role = Role.DropdownList, onClickLabel = changeLabel) { menuOpen = true }
                        .padding(start = 16.dp, end = 8.dp)
                    else Modifier.padding(horizontal = 16.dp)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (dot != null) WorkspaceSwatch(dot)
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (enabled) {
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onSelect != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                workspaces.forEach { ws ->
                    val wsDot = workspaceAccentColor(ws.color)
                    DropdownMenuItem(
                        text = { Text(ws.name) },
                        onClick = { menuOpen = false; onSelect(ws.id) },
                        // Workspaces without a colour get an empty slot so the names line up.
                        leadingIcon = if (anyColour) ({ WorkspaceSwatch(wsDot ?: Color.Transparent) }) else null,
                        colors = MenuDefaults.itemColors(
                            textColor = if (ws.id == selected?.id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}

/**
 * The cookie with Start or Stop on its bottom edge: the button overlaps the shape by half its
 * height, as a FAB anchored to a surface does, so the action reads as belonging to the timer
 * while keeping its label and full 56dp size.
 */
@Composable
private fun TimerHeroWithAction(
    elapsedText: String,
    container: Color,
    contentColor: Color,
    running: Boolean,
    modifier: Modifier = Modifier,
    heroModifier: Modifier = Modifier,
    action: @Composable () -> Unit
) {
    Box(modifier.width(HeroSize).height(HeroSize + TimerActionButtonHeight / 2)) {
        TimerHero(
            elapsedText = elapsedText,
            container = container,
            contentColor = contentColor,
            running = running,
            modifier = heroModifier.align(Alignment.TopCenter)
        )
        Box(Modifier.align(Alignment.BottomCenter)) { action() }
    }
}

private val HeroSize: Dp = 272.dp

/**
 * Every field of the timer in one card: a Group-shaped surface sets the form apart from the
 * timer above and the lists below. Not collapsible — all fields are always in reach.
 */
@Composable
private fun TimerDetailsCard(containerColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = AppShapes.Group,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

/**
 * A time as a read-only outlined field with its name as the label: tapping it opens the time
 * picker. [onClear] (when set) adds a clear button, which empties it again.
 */
@Composable
private fun TimeField(
    label: String,
    value: String,
    placeholder: String?,
    pickerDescription: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { },
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            // With a clear button on top, the slot stays empty but keeps the text clear of it.
            trailingIcon = {
                if (onClear == null) Icon(Icons.Rounded.AccessTime, contentDescription = null)
                else Spacer(Modifier.size(24.dp))
            },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClickLabel = pickerDescription, onClick = onPick)
        )
        if (onClear != null) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.CenterEnd).padding(top = 8.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.btn_clear))
            }
        }
    }
}

/** An instant as the ISO timestamp the time formatting here reads. */
private fun isoAt(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()

@Composable
private fun OtherRunningTimers(
    timers: List<RunningTimerInfo>,
    loading: Boolean,
    error: String?,
    activePageId: String?,
    onLoad: (RunningTimerInfo) -> Unit,
    onStop: (RunningTimerInfo) -> Unit,
    onDelete: (RunningTimerInfo) -> Unit,
    onOpenUrl: (String) -> Unit,
    showNotionLink: Boolean = true,
    showArchiveAction: Boolean = true
) {
    // A failed query is still worth a line even with nothing to list: an empty
    // list would otherwise read as "nothing is running".
    if (timers.isEmpty() && error == null) return

    // Drives the per-second elapsed counters below. It has to be *read* while
    // composing them — a state that is only written recomposes nothing, which
    // left every timer in this list frozen at the value it had when the list
    // was first drawn.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timers) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            nowMs = System.currentTimeMillis()
        }
    }

    SectionHeader(stringResource(R.string.running_timers_heading), loading = loading)
    error?.let { err ->
        Text(
            localisedMessage(err),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    timers.forEach { rt ->
        val isActive = rt.pageId == activePageId
        val startMs = runCatching { OffsetDateTime.parse(rt.startISO).toInstant().toEpochMilli() }.getOrDefault(0L)
        val elapsedSecs = if (startMs > 0) (nowMs - startMs) / 1000L else 0L
        val elapsedStr = formatElapsed(elapsedSecs.coerceAtLeast(0L))

        // A list row, not a card per timer; tap to show it in the timer above.
        Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.Item)
                    .then(if (!isActive) Modifier.clickable { onLoad(rt) } else Modifier)
                    .heightIn(min = 56.dp)
                    .padding(vertical = 4.dp)
                    .then(if (isActive) Modifier.alpha(0.6f) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rt.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        rt.workspaceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        elapsedStr,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isActive) {
                    IconButton(onClick = { onStop(rt) }) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.btn_stop),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showNotionLink && rt.url.isNotBlank()) {
                    IconButton(onClick = { onOpenUrl(rt.url) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.title_open_in_notion),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showArchiveAction) {
                    IconButton(onClick = { onDelete(rt) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.title_delete_entry),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
    }
}

@Composable
private fun RecentEntriesSection(
    entries: List<RecentEntryInfo>,
    loading: Boolean,
    workspaces: List<to.wim.timetracker.data.models.Workspace> = emptyList(),
    onDelete: (RecentEntryInfo) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPlay: (RecentEntryInfo) -> Unit = {},
    dateFormat: String,
    timeFormat: String,
    showNotionLink: Boolean = true,
    showArchiveAction: Boolean = true
) {
    // The first load shows placeholder cards; a refresh keeps the list and shows the spinner.
    val firstLoad = entries.isEmpty() && loading
    if (entries.isEmpty() && !firstLoad) return

    SectionHeader(stringResource(R.string.recent_entries_heading), loading = loading && !firstLoad)
    if (firstLoad) {
        val loadingText = stringResource(R.string.placeholder_loading)
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = loadingText },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(3) { RecentEntrySkeleton() }
        }
    }
    entries.forEach { re ->
        val timeRange = formatRecentRange(re.startISO, re.endISO, dateFormat, timeFormat == "12h")
        val wsAccent = workspaceAccentColor(workspaces.firstOrNull { it.id == re.workspaceId }?.color)

        // A list row with the workspace's colour as a dot, not a tinted card per entry.
        Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkspaceSwatch(wsAccent ?: MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.weight(1f)) {
                    Text(re.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        re.workspaceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        timeRange,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onPlay(re) }) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.title_start_from_entry),
                        modifier = Modifier.size(18.dp),
                        tint = wsAccent ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showNotionLink && re.url.isNotBlank()) {
                    IconButton(onClick = { onOpenUrl(re.url) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.title_open_in_notion),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showArchiveAction) {
                    IconButton(onClick = { onDelete(re) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.title_delete_entry),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
    }
}

/** Stands in for a recent-entry row (title, workspace, time range) while the list loads. */
@Composable
private fun RecentEntrySkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SkeletonLine(Modifier.fillMaxWidth(0.6f), height = 16.dp)
        SkeletonLine(Modifier.fillMaxWidth(0.35f), height = 12.dp)
        SkeletonLine(Modifier.fillMaxWidth(0.5f), height = 12.dp)
    }
}

/**
 * The selected workspace's extra fields while their options load from Notion: the real labels
 * over placeholder fields, so the form doesn't jump when the inputs arrive.
 */
@Composable
private fun ExtraFieldsSkeleton(props: List<to.wim.timetracker.data.models.ExtraProp>) {
    val loadingText = stringResource(R.string.placeholder_loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = loadingText },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        props.forEach { prop ->
            if (prop.type == "checkbox") {
                SkeletonBlock(height = 32.dp, modifier = Modifier.width(52.dp), shape = CircleShape)
            } else {
                SkeletonBlock(height = 56.dp, shape = MaterialTheme.shapes.extraSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PresetChips(
    workspaces: List<to.wim.timetracker.data.models.Workspace>,
    signedIn: Boolean,
    onApplyPreset: (String, Preset) -> Unit,
    onManageClick: () -> Unit,
    onNewClick: () -> Unit
) {
    val allPresets = workspaces.flatMap { ws -> ws.presets.map { ws to it } }
    if (!signedIn || workspaces.isEmpty()) return
    // Before the first preset, one quiet chip creates it.
    if (allPresets.isEmpty()) {
        AssistChip(
            onClick = onNewClick,
            label = { Text(stringResource(R.string.title_new_preset)) },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
        )
        return
    }
    // Chips keep Material's 48dp touch target: their invisible touch padding already
    // separates wrapped lines, so the rows get no extra spacing of their own.
    run {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            allPresets.forEach { (ws, preset) ->
                val wsColor = workspaceAccentColor(ws.color)
                val chipLabel = "▶ ${preset.name}"
                SuggestionChip(
                    onClick = { onApplyPreset(ws.id, preset) },
                    // One line, ellipsized only when the chip would be wider than
                    // the row — same as the Windows chips.
                    label = {
                        Text(
                            chipLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = if (wsColor != null) SuggestionChipDefaults.suggestionChipColors(
                        containerColor = wsColor.copy(alpha = 0.15f),
                        labelColor = wsColor
                    ) else SuggestionChipDefaults.suggestionChipColors(),
                    border = if (wsColor != null) SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = wsColor.copy(alpha = 0.4f)
                    ) else SuggestionChipDefaults.suggestionChipBorder(enabled = true)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onManageClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.title_manage_presets),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The app's top bar: the title in Material 3's default (regular) style, actions on the right.
 *  Transparent, so a running timer's tinted background runs up behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerTopBar(actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_title)) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

private val TimerActionButtonHeight: Dp = 56.dp

/**
 * Start and Stop keep their meaning whatever the theme or wallpaper colours: green and red, with
 * white labels (a workspace colour, when set, replaces the green).
 */
private object TimerColors {
    val Start = Color(0xFF1E8E3E)
    val Stop = Color(0xFFD93025)
    val OnAction = Color.White
}

/**
 * The Start / Stop button, sized as a Material 3 Expressive medium button: 56dp high, a Title
 * Medium label, 24dp icon. Fully round, tightening to 12dp corners while pressed (the Expressive
 * shape morph); its colour follows the workspace, so switching workspaces animates it.
 */
@Composable
private fun TimerActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val animate = !rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed && animate) 12.dp else TimerActionButtonHeight / 2,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "timer-button-corner"
    )
    val container by animateColorAsState(containerColor, Motion.fadeSpec(), label = "timer-button-color")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(TimerActionButtonHeight),
        shape = RoundedCornerShape(corner),
        interactionSource = interactionSource,
        // The button sits on the cookie's edge, so it never turns see-through: busy, it keeps
        // its colour (only the spinner shows it's working); unavailable, it takes a solid
        // grey instead of Material's translucent disabled fill.
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = if (loading) container
            else lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.12f),
            disabledContentColor = if (loading) contentColor
            else lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = contentColor)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The full-screen state shown instead of the timer while [step] is not set up yet. */
@Composable
private fun SetupNeeded(step: SetupStep, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, title, body) = when (step) {
        SetupStep.SignIn -> Triple(Icons.Rounded.Key, R.string.setup_connect_title, R.string.setup_connect_body)
        SetupStep.Workspace -> Triple(Icons.Rounded.Workspaces, R.string.setup_workspace_title, R.string.setup_workspace_body)
    }
    EmptyState(icon, stringResource(title), modifier, body = stringResource(body)) {
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.setup_open_settings)) }
    }
}

/** Refresh icon button that spins while a refresh is in progress. */
@Composable
private fun RefreshIconButton(loading: Boolean, onClick: () -> Unit) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            rotation.snapTo(0f)
            rotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing))
            )
        } else {
            rotation.snapTo(0f)
        }
    }
    IconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.btn_refresh_all),
            modifier = Modifier.rotate(rotation.value)
        )
    }
}

/**
 * Resolves a message key from the view model (see `UiError`) to text in the
 * language the app is set to. Anything after a `|` is Notion's own message and
 * is appended as detail; an unrecognised key is shown as-is so a message is
 * never swallowed.
 */
@Composable
private fun localisedMessage(raw: String): String {
    val key = raw.substringBefore('|')
    val detail = raw.substringAfter('|', "")
    val resId = when (key) {
        "not_signed_in" -> R.string.error_not_signed_in
        "start_failed" -> R.string.toast_start_error
        "stop_failed" -> R.string.toast_stop_error
        "update_failed" -> R.string.toast_update_error
        "delete_failed" -> R.string.toast_delete_error
        "running_timers_failed" -> R.string.error_running_timers_failed
        "required_fields_missing" -> R.string.toast_required_fields_missing
        "required_fields_before_stopping" -> R.string.toast_required_before_stopping
        "description_before_stopping" -> R.string.toast_description_before_stopping
        "timer_stopped_elsewhere" -> R.string.timer_stopped_elsewhere
        "extras_load_failed" -> R.string.error_extras_load_failed
        "extras_missing_props" -> R.string.error_extras_missing_props
        else -> null
    } ?: return raw
    val text = stringResource(resId)
    return if (detail.isBlank()) text else "$text: $detail"
}

/** True for the validation messages that the user fixes by filling the form in,
 *  rather than by retrying the failed call. */
private fun isValidationMessage(raw: String): Boolean =
    raw.substringBefore('|') in setOf("required_fields_before_stopping", "description_before_stopping")

/**
 * Heads a list section (active timers, recent entries) in Title Medium. Space above it, not a
 * divider, separates the section from what comes before: Material 3 keeps dividers for
 * separating items inside a group, and the header already marks where the group starts.
 */
@Composable
private fun SectionHeader(text: String, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** The selected workspace's colour as a dot at the start of the workspace field. */
@Composable
private fun WorkspaceSwatch(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatHHMM(iso: String, is12h: Boolean = false): String = runCatching {
    val odt = OffsetDateTime.parse(iso)
    if (is12h) {
        val hr = odt.hour
        val ampm = if (hr >= 12) "PM" else "AM"
        val h12 = when {
            hr == 0 -> 12
            hr > 12 -> hr - 12
            else -> hr
        }
        "%d:%02d %s".format(h12, odt.minute, ampm)
    } else {
        "%02d:%02d".format(odt.hour, odt.minute)
    }
}.getOrDefault("")

private fun parseHHMMToISO(hhMM: String, referenceISO: String): String? = runCatching {
    val clean = hhMM.trim().uppercase()
    val isPM = clean.contains("PM")
    val isAM = clean.contains("AM")
    val timePart = clean.replace("AM", "").replace("PM", "").trim()
    val parts = timePart.split(":")
    if (parts.size < 2) return null
    var h = parts[0].toInt()
    val m = parts[1].toInt()
    if (isPM && h < 12) h += 12
    if (isAM && h == 12) h = 0
    val base = OffsetDateTime.parse(referenceISO)
    val target = base.withHour(h).withMinute(m).withSecond(0).withNano(0)
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(target)
}.getOrNull()

private fun parseHHMMToMillis(hhMM: String, referenceISO: String): Long? = runCatching {
    val iso = parseHHMMToISO(hhMM, referenceISO) ?: return null
    OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}.getOrNull()

private fun formatDate(iso: String, format: String = "DD/MM/YYYY"): String = runCatching {
    val odt = OffsetDateTime.parse(iso)
    when (format) {
        "MM/DD/YYYY" -> "%02d/%02d/%04d".format(odt.monthValue, odt.dayOfMonth, odt.year)
        "YYYY-MM-DD" -> "%04d-%02d-%02d".format(odt.year, odt.monthValue, odt.dayOfMonth)
        else -> "%02d/%02d/%04d".format(odt.dayOfMonth, odt.monthValue, odt.year) // default DD/MM/YYYY
    }
}.getOrDefault("")

private fun formatRecentRange(startISO: String?, endISO: String?, dateFormat: String, is12h: Boolean): String {
    if (startISO == null) return ""
    val startDateStr = formatDate(startISO, dateFormat)
    val startTimeStr = formatHHMM(startISO, is12h)
    if (endISO == null || endISO.isBlank()) {
        return "$startDateStr $startTimeStr"
    }
    val endDateStr = formatDate(endISO, dateFormat)
    val endTimeStr = formatHHMM(endISO, is12h)
    return if (startDateStr == endDateStr) {
        "$startDateStr $startTimeStr → $endTimeStr"
    } else {
        "$startDateStr $startTimeStr → $endDateStr $endTimeStr"
    }
}

@Composable
private fun ManagePresetsDialog(
    workspaces: List<to.wim.timetracker.data.models.Workspace>,
    onNewPreset: () -> Unit,
    onEditPreset: (String, String) -> Unit,
    onMovePreset: (workspaceId: String, from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presets_manage_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.title_info), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            text = stringResource(R.string.tooltip_presets_shortcuts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // A list row per preset (tap or pen to edit), dragged within its workspace.
                workspaces.filter { it.presets.isNotEmpty() }.forEach { ws ->
                    ReorderableColumn(
                        items = ws.presets,
                        key = { it.id },
                        onMove = { from, to -> onMovePreset(ws.id, from, to) }
                    ) { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { onEditPreset(ws.id, preset.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The preset's name, with its workspace (and colour) underneath.
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    workspaceAccentColor(ws.color)?.let { dot ->
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                                    }
                                    Text(
                                        ws.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { onEditPreset(ws.id, preset.id) }) {
                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.title_edit_preset))
                            }
                            DragHandle(handleModifier)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.title_dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onNewPreset, enabled = workspaces.isNotEmpty()) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.title_new_preset))
            }
        }
    )
}
