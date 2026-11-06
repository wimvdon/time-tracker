package to.wim.timetracker.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.ui.theme.readableWidth
import to.wim.timetracker.data.models.ExtraProp
import to.wim.timetracker.data.models.NamedOption
import to.wim.timetracker.data.models.READ_ONLY_TYPES
import to.wim.timetracker.data.models.SchemaProperty
import to.wim.timetracker.data.models.WRITABLE_EXTRA_TYPES
import to.wim.timetracker.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceModal(
    state: SettingsViewModel.WorkspaceEditState,
    allWorkspaces: List<to.wim.timetracker.data.models.Workspace>,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
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
    isValid: Boolean,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true
) {
    val scrollState = rememberScrollState()

    val hasChanges = remember(state, allWorkspaces) {
        if (state.isNew) {
            state.name.isNotBlank() ||
            state.timeEntriesDbId.isNotBlank() ||
            state.color.isNotBlank() ||
            state.extraProps.isNotEmpty() ||
            state.descriptionRequired ||
            state.roundingMinutes != 15
        } else {
            val original = allWorkspaces.find { it.id == state.workspaceId }
            original == null ||
            state.name != original.name ||
            state.color != original.color ||
            state.timeEntriesDbId != original.timeEntriesDbId ||
            state.timeEntryDateProp != original.propertyMap.timeEntryDateProp ||
            state.timeEntryStatusProp != original.propertyMap.timeEntryStatusProp ||
            state.timeEntryStatusRunning != original.propertyMap.timeEntryStatusRunning ||
            state.timeEntryStatusDone != original.propertyMap.timeEntryStatusDone ||
            state.extraProps != original.extraProps ||
            state.descriptionRequired != original.descriptionRequired ||
            state.roundingMinutes != original.roundingMinutes
        }
    }

    var showUnsavedDialog by remember { mutableStateOf(false) }

    val handleDismissRequest = {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onDismiss()
        }
    }

    BackHandler(enabled = true, onBack = handleDismissRequest)

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.dialog_unsaved_changes_title)) },
            text = { Text(stringResource(R.string.dialog_unsaved_changes_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onSave()
                    },
                    enabled = isValid && !state.saving
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.btn_discard))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        )
    }

    Scaffold(
        // Edge-to-edge: the keyboard does not shrink the screen, so the content makes room for it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isNew) stringResource(R.string.modal_title_add_workspace)
                        else stringResource(R.string.modal_title_edit_workspace)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleDismissRequest) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.btn_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = isValid && !state.saving) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.btn_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(8.dp))

            // Name
            SectionLabel(stringResource(R.string.label_workspace_name))
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.placeholder_workspace_name)) }
            )

            Spacer(Modifier.height(16.dp))

            // Time Entries database
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.label_time_entries_db), modifier = Modifier.weight(1f))
                TextButton(onClick = onRefreshDatabases, enabled = !state.databasesLoading) {
                    Text(stringResource(R.string.btn_refresh_databases))
                }
            }
            when {
                state.databasesLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.databasesError != null -> Text(
                    state.databasesError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                state.databases.isEmpty() -> Text(
                    stringResource(R.string.no_databases_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> SearchableDropdown(
                    options = state.databases,
                    selected = state.databases.firstOrNull { it.id == state.timeEntriesDbId },
                    placeholder = stringResource(R.string.placeholder_select_database),
                    onSelect = { onTimeEntriesDbChange(it?.id ?: "") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.hint_database_integration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Schema-based fields (visible once timeEntriesDbId is set)
            if (state.timeEntriesDbId.isNotBlank()) {
                Spacer(Modifier.height(16.dp))

                when {
                    state.schemaLoading -> {
                        Text(stringResource(R.string.loading_properties), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.schemaError != null -> Text(state.schemaError, color = MaterialTheme.colorScheme.error)
                    else -> SchemaFields(
                        schema = state.schema,
                        dateProp = state.timeEntryDateProp,
                        statusProp = state.timeEntryStatusProp,
                        statusRunning = state.timeEntryStatusRunning,
                        statusDone = state.timeEntryStatusDone,
                        onDatePropChange = onDatePropChange,
                        onStatusPropChange = onStatusPropChange,
                        onStatusRunningChange = onStatusRunningChange,
                        onStatusDoneChange = onStatusDoneChange
                    )
                }
            }

            // Extra fields
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.section_extra_fields), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.hint_extra_fields),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // Properties this database offers, plus any already-configured name the
            // schema does not (or could not) report: without those the dropdown of a
            // configured field would render empty, as if nothing were selected.
            val schemaProps = state.schema.entries
                .filter { (_, v) -> v.type in WRITABLE_EXTRA_TYPES && v.type !in READ_ONLY_TYPES }
                .map { NamedOption(it.key, it.key) }
            val configuredOnly = state.extraProps
                .map { it.propName }
                .filter { it.isNotBlank() && schemaProps.none { p -> p.id == it } }
                .distinct()
                .map { NamedOption(it, it) }
            val editableProps = schemaProps + configuredOnly

            state.extraProps.forEachIndexed { index, prop ->
                ExtraPropRow(
                    index = index,
                    prop = prop,
                    availableProps = editableProps,
                    schema = state.schema,
                    onNameChange = { onExtraPropNameChange(index, it) },
                    onRequiredChange = { onExtraPropRequiredChange(index, it) },
                    onDefaultChange = { onExtraPropDefaultChange(index, it) },
                    onRemove = { onRemoveExtraProp(index) }
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.extraProps.size < 5) {
                TextButton(onClick = onAddExtraProp) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.btn_add_extra_field))
                }
            }



            // Description
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.descriptionRequired, onCheckedChange = onDescriptionRequiredChange)
                Text(stringResource(R.string.label_description_required), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (state.descriptionRequired) stringResource(R.string.hint_description_required)
                else stringResource(R.string.hint_description_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Rounding
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.label_workspace_rounding), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.workspace_rounding_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            RoundingSelector(selected = state.roundingMinutes, onSelect = onRoundingChange)

            // Colour
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.label_workspace_color), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ColorPicker(selected = state.color, onSelect = onColorChange)

            if (!isValid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.save_incomplete_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Removing lives here, with the workspace it removes, as for presets.
            if (onRemove != null && !state.isNew) {
                var confirmRemove by remember { mutableStateOf(false) }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = removeEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.title_delete_workspace))
                }
                if (!removeEnabled) {
                    Text(
                        stringResource(R.string.title_delete_workspace_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (confirmRemove) {
                    AlertDialog(
                        onDismissRequest = { confirmRemove = false },
                        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        title = { Text(stringResource(R.string.confirm_remove_workspace_title)) },
                        text = { Text(stringResource(R.string.confirm_remove_workspace_body)) },
                        confirmButton = {
                            TextButton(onClick = { confirmRemove = false; onRemove() }) {
                                Text(stringResource(R.string.btn_remove))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRemove = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SchemaFields(
    schema: Map<String, SchemaProperty>,
    dateProp: String,
    statusProp: String,
    statusRunning: String,
    statusDone: String,
    onDatePropChange: (String) -> Unit,
    onStatusPropChange: (String) -> Unit,
    onStatusRunningChange: (String) -> Unit,
    onStatusDoneChange: (String) -> Unit
) {
    val dateProps = schema.entries.filter { it.value.type == "date" }.map { NamedOption(it.key, it.key) }
    val statusProps = schema.entries.filter { it.value.type == "status" }.map { NamedOption(it.key, it.key) }
    val statusOptions = schema[statusProp]?.statusOptions ?: emptyList()

    SectionLabel(stringResource(R.string.label_time_entry_date_prop))
    SearchableDropdown(
        options = dateProps,
        selected = dateProps.firstOrNull { it.id == dateProp },
        placeholder = stringResource(R.string.placeholder_select),
        onSelect = { onDatePropChange(it?.id ?: "") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    SectionLabel(stringResource(R.string.label_time_entry_status_prop))
    SearchableDropdown(
        options = statusProps,
        selected = statusProps.firstOrNull { it.id == statusProp },
        placeholder = stringResource(R.string.placeholder_select),
        onSelect = { onStatusPropChange(it?.id ?: "") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    if (statusProp.isNotBlank() && statusOptions.isNotEmpty()) {
        val optionItems = statusOptions.map { NamedOption(it.name, it.name) }
        // The running and ended statuses must differ, so each dropdown hides the
        // option already chosen in the other.
        val runningItems = optionItems.filter { it.id != statusDone }
        val doneItems = optionItems.filter { it.id != statusRunning }

        SectionLabel(stringResource(R.string.label_time_entry_status_running))
        SearchableDropdown(
            options = runningItems,
            selected = runningItems.firstOrNull { it.id == statusRunning },
            placeholder = stringResource(R.string.placeholder_select),
            onSelect = { onStatusRunningChange(it?.id ?: "") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        SectionLabel(stringResource(R.string.label_time_entry_status_done))
        SearchableDropdown(
            options = doneItems,
            selected = doneItems.firstOrNull { it.id == statusDone },
            placeholder = stringResource(R.string.placeholder_select),
            onSelect = { onStatusDoneChange(it?.id ?: "") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ExtraPropRow(
    index: Int,
    prop: ExtraProp,
    availableProps: List<NamedOption>,
    schema: Map<String, SchemaProperty>,
    onNameChange: (String) -> Unit,
    onRequiredChange: (Boolean) -> Unit,
    onDefaultChange: (String?) -> Unit,
    onRemove: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchableDropdown(
                    options = availableProps,
                    selected = availableProps.firstOrNull { it.id == prop.propName },
                    placeholder = stringResource(R.string.placeholder_select_field),
                    onSelect = { onNameChange(it?.id ?: "") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.title_remove_extra_field))
                }
            }

            if (prop.propName.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = prop.required, onCheckedChange = onRequiredChange)
                    Text(stringResource(R.string.label_required_field), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}




@Composable
private fun RoundingSelector(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        0 to R.string.rounding_off,
        5 to R.string.rounding_5,
        10 to R.string.rounding_10,
        15 to R.string.rounding_15
    )
    Column {
        options.forEach { (minutes, labelRes) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(selected = selected == minutes, onClick = { onSelect(minutes) })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

val PRESET_PALETTE = listOf(
    "#ef4444", "#f97316", "#eab308", "#22c55e",
    "#06b6d4", "#3b82f6", "#8b5cf6", "#ec4899"
)

fun parseWorkspaceColor(hex: String): Color? = runCatching {
    if (hex.isBlank()) null
    else Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
}.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    var customHex by remember(selected) {
        mutableStateOf(if (selected.isNotBlank() && selected !in PRESET_PALETTE) selected else "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "None" swatch
            val noneSelected = selected.isBlank()
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, if (noneSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .clickable { onSelect("") },
                contentAlignment = Alignment.Center
            ) {
                if (noneSelected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            PRESET_PALETTE.forEach { hex ->
                val color = parseWorkspaceColor(hex) ?: return@forEach
                val isSelected = selected == hex
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .clickable { onSelect(hex); customHex = "" },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customHex,
                onValueChange = { customHex = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("#RRGGBB", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text(stringResource(R.string.label_custom_color), style = MaterialTheme.typography.labelSmall) }
            )
            TextButton(
                onClick = {
                    val hex = customHex.trim().let { if (it.startsWith("#")) it else "#$it" }
                    if (parseWorkspaceColor(hex) != null) onSelect(hex)
                },
                enabled = customHex.isNotBlank()
            ) {
                Text(stringResource(R.string.btn_apply_color))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 4.dp)
    )
}
