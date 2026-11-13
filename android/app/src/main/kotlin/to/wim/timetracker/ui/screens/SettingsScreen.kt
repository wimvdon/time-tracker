package to.wim.timetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ListItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.data.models.ThemeMode
import to.wim.timetracker.data.models.Workspace
import androidx.activity.compose.BackHandler
import to.wim.timetracker.ui.components.WorkspaceModal
import to.wim.timetracker.ui.theme.DragHandle
import to.wim.timetracker.ui.theme.ReorderableColumn
import to.wim.timetracker.ui.theme.AppearanceSettings
import to.wim.timetracker.ui.theme.SettingsBlock
import to.wim.timetracker.ui.theme.SettingsSection
import to.wim.timetracker.ui.theme.SettingsFooter
import to.wim.timetracker.ui.theme.SettingsScaffold
import to.wim.timetracker.ui.theme.SwitchSetting
import to.wim.timetracker.ui.theme.rememberAppVersion
import to.wim.timetracker.ui.theme.dynamicColorSupported
import to.wim.timetracker.viewmodel.SettingsViewModel
import to.wim.timetracker.ui.components.SearchableDropdown
import to.wim.timetracker.data.models.NamedOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsViewModel.UiState,
    editState: SettingsViewModel.WorkspaceEditState?,
    isEditValid: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onMoveWorkspace: (from: Int, to: Int) -> Unit,
    onEditWorkspace: (Workspace) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onAddWorkspace: () -> Unit,
    onCloseEdit: () -> Unit,
    onSaveWorkspace: () -> Unit,
    onWorkspaceNameChange: (String) -> Unit,
    onTimeEntriesDbChange: (String) -> Unit,
    onRefreshDatabases: () -> Unit,
    onDatePropChange: (String) -> Unit,
    onStatusPropChange: (String) -> Unit,
    onStatusRunningChange: (String) -> Unit,
    onStatusDoneChange: (String) -> Unit,
    onRoundingChange: (Int) -> Unit,
    onDescriptionRequiredChange: (Boolean) -> Unit = {},
    onColorChange: (String) -> Unit = {},
    onAddExtraProp: () -> Unit,
    onRemoveExtraProp: (Int) -> Unit,
    onExtraPropNameChange: (Int, String) -> Unit,
    onExtraPropRequiredChange: (Int, Boolean) -> Unit,
    onExtraPropDefaultChange: (Int, String?) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSnackbarDismiss: () -> Unit,
    onLocaleChange: (String) -> Unit = {},
    onDateFormatChange: (String) -> Unit = {},
    onTimeFormatChange: (String) -> Unit = {},
    onShowNotionLinkChange: (Boolean) -> Unit = {},
    onShowArchiveActionChange: (Boolean) -> Unit = {},
    onSyncEnabledChange: (Boolean) -> Unit = {},
    onDeleteSyncedSettings: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            val text = when {
                msg.startsWith("signed_in:") ->
                    resources.getString(R.string.toast_signed_in, msg.substringAfter(":").ifBlank { "Notion" })
                msg == "signed_out" -> resources.getString(R.string.toast_signed_out)
                msg == "sync_deleted" -> resources.getString(R.string.toast_sync_deleted)
                msg == "sign_in_expired" -> resources.getString(R.string.toast_sign_in_expired)
                msg.startsWith("sign_in_failed:") ->
                    resources.getString(R.string.toast_sign_in_failed) + ": " + msg.substringAfter(":")
                msg.startsWith("workspace_added:") -> resources.getString(R.string.toast_workspace_added, msg.substringAfter(":"))
                msg.startsWith("workspace_saved:") -> resources.getString(R.string.toast_workspace_saved, msg.substringAfter(":"))
                else -> msg
            }
            // Dismiss the keyboard so the snackbar isn't hidden behind it.
            keyboardController?.hide()
            focusManager.clearFocus()
            snackbarHostState.showSnackbar(text)
            onSnackbarDismiss()
        }
    }

    if (editState != null) {
        WorkspaceModal(
            state = editState,
            allWorkspaces = uiState.workspaces,
            onDismiss = onCloseEdit,
            onSave = onSaveWorkspace,
            onNameChange = onWorkspaceNameChange,
            onTimeEntriesDbChange = onTimeEntriesDbChange,
            onRefreshDatabases = onRefreshDatabases,
            onDatePropChange = onDatePropChange,
            onStatusPropChange = onStatusPropChange,
            onStatusRunningChange = onStatusRunningChange,
            onStatusDoneChange = onStatusDoneChange,
            onRoundingChange = onRoundingChange,
            onDescriptionRequiredChange = onDescriptionRequiredChange,
            onColorChange = onColorChange,
            onAddExtraProp = onAddExtraProp,
            onRemoveExtraProp = onRemoveExtraProp,
            onExtraPropNameChange = onExtraPropNameChange,
            onExtraPropRequiredChange = onExtraPropRequiredChange,
            onExtraPropDefaultChange = onExtraPropDefaultChange,
            isValid = isEditValid,
            onRemove = {
                onDeleteWorkspace(editState.workspaceId)
                onCloseEdit()
            },
            removeEnabled = !uiState.timerRunning
        )
        return
    }

    var confirmDeleteSync by remember { mutableStateOf(false) }
    if (confirmDeleteSync) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSync = false },
            text = { Text(stringResource(R.string.confirm_delete_sync)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSync = false
                    onDeleteSyncedSettings()
                }) { Text(stringResource(R.string.btn_delete_sync), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSync = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    val themeOptions = listOf(
        ThemeMode.AUTO to stringResource(R.string.theme_auto),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark),
    )
    val languageOptions = listOf(
        NamedOption("system", stringResource(R.string.language_system)),
        NamedOption("en", stringResource(R.string.language_en)),
        NamedOption("nl", stringResource(R.string.language_nl)),
        NamedOption("fr", stringResource(R.string.language_fr)),
        NamedOption("de", stringResource(R.string.language_de)),
        NamedOption("es", stringResource(R.string.language_es)),
        NamedOption("it", stringResource(R.string.language_it))
    )
    // A locale pulled in from the Windows app arrives as a full tag ("en-GB", "nl-BE"), so the
    // option is matched on the language subtag; otherwise the picker reads "System" while the
    // app is actually rendering that language.
    val localeLanguage = uiState.locale.lowercase().substringBefore('-').substringBefore('_')
    val selectedLanguage = languageOptions.firstOrNull { it.id == localeLanguage } ?: languageOptions.first()
    val dateFormatOptions = listOf(
        NamedOption("DD/MM/YYYY", "DD/MM/YYYY"),
        NamedOption("MM/DD/YYYY", "MM/DD/YYYY"),
        NamedOption("YYYY-MM-DD", "YYYY-MM-DD")
    )
    val selectedDateFormat = dateFormatOptions.firstOrNull { it.id == uiState.dateFormat } ?: dateFormatOptions.first()
    val timeFormatOptions = listOf(
        NamedOption("24h", "24h"),
        NamedOption("12h", "12h")
    )
    val selectedTimeFormat = timeFormatOptions.firstOrNull { it.id == uiState.timeFormat } ?: timeFormatOptions.first()

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        backLabel = stringResource(R.string.btn_back),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {

        // --- Connection: Notion sign-in and sync ---
        item(key = "notion-account") {
            SettingsSection(title = stringResource(R.string.label_notion_account)) {
                val account = uiState.account
                if (account != null) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.signed_in_to, account.workspaceName.ifBlank { "Notion" }))
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            TextButton(onClick = onSignOut) { Text(stringResource(R.string.btn_sign_out)) }
                        }
                    )
                    SettingsBlock {
                        // Notion's own page is where the shared pages are chosen, so this signs in again.
                        OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.btn_choose_pages))
                        }
                        Text(
                            stringResource(R.string.choose_pages_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else SettingsBlock {
                    Text(
                        stringResource(R.string.sign_in_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_sign_in))
                    }
                }
            }
        }

        item(key = "sync") {
            SettingsSection(title = stringResource(R.string.label_sync)) {
                SwitchSetting(
                    title = stringResource(R.string.label_sync_enabled),
                    summary = stringResource(R.string.sync_hint),
                    checked = uiState.syncEnabled,
                    onCheckedChange = { if (uiState.account != null) onSyncEnabledChange(it) }
                )
                if (uiState.account != null && uiState.syncEnabled && uiState.syncError != null) {
                    SettingsBlock {
                        Text(
                            uiState.syncError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (uiState.account != null) {
                    SettingsBlock {
                        TextButton(onClick = { confirmDeleteSync = true }) {
                            Text(stringResource(R.string.btn_delete_sync), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // --- App features ---
        item(key = "workspaces") {
            SettingsSection(title = stringResource(R.string.label_workspaces)) {
                if (uiState.workspaces.isEmpty()) {
                    SettingsBlock {
                        Text(
                            stringResource(R.string.no_workspaces_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ReorderableColumn(
                    items = uiState.workspaces,
                    key = { it.id },
                    onMove = onMoveWorkspace
                ) { workspace ->
                    WorkspaceListItem(
                        workspace = workspace,
                        onEdit = { onEditWorkspace(workspace) },
                        handle = { DragHandle(handleModifier) }
                    )
                }
                SettingsBlock {
                    OutlinedButton(onClick = onAddWorkspace, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.btn_add_workspace))
                    }
                }
            }
        }

        item(key = "notion-actions") {
            SettingsSection(title = stringResource(R.string.label_notion_actions)) {
                SwitchSetting(
                    title = stringResource(R.string.label_show_notion_link),
                    summary = null,
                    checked = uiState.showNotionLink,
                    onCheckedChange = onShowNotionLinkChange
                )
                SwitchSetting(
                    title = stringResource(R.string.label_show_archive_action),
                    summary = null,
                    checked = uiState.showArchiveAction,
                    onCheckedChange = onShowArchiveActionChange
                )
            }
        }

        // --- Appearance, then language and formats ---
        item(key = "appearance") {
            SettingsSection(title = stringResource(R.string.label_theme)) {
                AppearanceSettings(
                    themeLabel = stringResource(R.string.label_theme_mode),
                    themeOptions = themeOptions.map { it.second },
                    selectedTheme = themeOptions.indexOfFirst { it.first == uiState.themeMode },
                    onThemeSelect = { onThemeModeChange(themeOptions[it].first) },
                    dynamicColorTitle = stringResource(R.string.label_dynamic_color),
                    dynamicColorSummary = stringResource(R.string.dynamic_color_hint),
                    dynamicColor = uiState.dynamicColor,
                    onDynamicColorChange = onDynamicColorChange,
                    dynamicColorSupported = dynamicColorSupported
                )
            }
        }

        item(key = "language") {
            SettingsSection(title = stringResource(R.string.label_language)) {
              SettingsBlock {
                SearchableDropdown(
                    options = languageOptions,
                    selected = selectedLanguage,
                    placeholder = stringResource(R.string.label_language),
                    onSelect = { opt -> if (opt != null) onLocaleChange(opt.id) },
                    searchable = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.label_date_format), style = MaterialTheme.typography.labelLarge)
                SearchableDropdown(
                    options = dateFormatOptions,
                    selected = selectedDateFormat,
                    placeholder = stringResource(R.string.label_date_format),
                    onSelect = { opt -> if (opt != null) onDateFormatChange(opt.id) },
                    searchable = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.label_time_format), style = MaterialTheme.typography.labelLarge)
                SearchableDropdown(
                    options = timeFormatOptions,
                    selected = selectedTimeFormat,
                    placeholder = stringResource(R.string.label_time_format),
                    onSelect = { opt -> if (opt != null) onTimeFormatChange(opt.id) },
                    searchable = false,
                    modifier = Modifier.fillMaxWidth()
                )
              }
            }
        }

        item(key = "footer") {
            SettingsFooter("${stringResource(R.string.app_name)} ${rememberAppVersion()}")
        }
    }
}

@Composable
private fun WorkspaceListItem(
    workspace: Workspace,
    onEdit: () -> Unit,
    handle: @Composable () -> Unit
) {
    // A list row (tap to edit), not a card per workspace.
    ListItem(
        headlineContent = { Text(workspace.name) },
        supportingContent = if (workspace.roundingMinutes > 0) {
            { Text(stringResource(R.string.workspace_rounding_summary, workspace.roundingMinutes)) }
        } else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.modal_title_edit_workspace))
                }
                handle()
            }
        },
        modifier = Modifier.clickable(onClick = onEdit)
    )
}
