package to.wim.timetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import to.wim.timetracker.ui.theme.AppShapes
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.ui.theme.readableWidth
import to.wim.timetracker.data.models.NamedOption
import to.wim.timetracker.data.models.Workspace
import to.wim.timetracker.ui.theme.SkeletonBlock
import to.wim.timetracker.ui.theme.workspaceAccentColor
import to.wim.timetracker.viewmodel.TimerViewModel

/**
 * Creates or edits a preset, as a full-screen dialog like Edit timer. Its name comes first, on
 * its own; below it, in a card, what the preset fills in: its workspace (new presets only), the
 * description and the extra field values. The workspace's fields load from Notion when it opens
 * and whenever another workspace is picked. An existing preset can be removed from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditorScreen(
    editor: TimerViewModel.PresetEditor,
    workspaces: List<Workspace>,
    onWorkspaceChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPageTitleChange: (String) -> Unit,
    onExtraChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    errorText: @Composable (String) -> String,
    // Set when editing an existing preset: removes it (after a confirmation).
    onDelete: (() -> Unit)? = null
) {
    val workspace = workspaces.firstOrNull { it.id == editor.workspaceId }

    Scaffold(
        // Edge-to-edge: the keyboard does not shrink the screen, so the content makes room for it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (editor.presetId == null) R.string.title_new_preset else R.string.title_edit_preset))
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.btn_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = editor.name.isNotBlank()) {
                        Text(stringResource(R.string.btn_save))
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // The name stands on its own: it is the preset (what its chip shows) and always
            // needed, so it carries no "required" mark.
            OutlinedTextField(
                value = editor.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.label_preset_name)) },
                placeholder = { Text(stringResource(R.string.placeholder_preset_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            // What the preset fills in when it starts a timer, in a card like the timer's own
            // details: the workspace, the description and the extra fields.
            Card(
                shape = AppShapes.Group,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (editor.presetId == null) {
                        val options = workspaces.map { NamedOption(it.id, it.name) }
                        val anyColour = workspaces.any { workspaceAccentColor(it.color) != null }
                        SearchableDropdown(
                            options = options,
                            selected = options.firstOrNull { it.id == editor.workspaceId },
                            placeholder = stringResource(R.string.placeholder_select_workspace),
                            onSelect = { it?.let { opt -> onWorkspaceChange(opt.id) } },
                            modifier = Modifier.fillMaxWidth(),
                            searchable = options.size > 4,
                            label = stringResource(R.string.label_workspace),
                            leadingIcon = workspaceAccentColor(workspace?.color)?.let { c -> { Dot(c) } },
                            optionLeadingIcon = if (anyColour) {
                                { opt -> Dot(workspaceAccentColor(workspaces.firstOrNull { it.id == opt.id }?.color) ?: Color.Transparent) }
                            } else null
                        )
                    } else {
                        // An existing preset stays in its workspace: its field values belong to
                        // that workspace's fields.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            workspaceAccentColor(workspace?.color)?.let { Dot(it) }
                            Text(workspace?.name ?: "", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    OutlinedTextField(
                        value = editor.pageTitle,
                        onValueChange = onPageTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.placeholder_description)) },
                        placeholder = { Text(stringResource(R.string.label_description)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )

                    if (editor.fieldsLoading) {
                        workspace?.extraProps.orEmpty().forEach { _ ->
                            SkeletonBlock(height = 56.dp, shape = MaterialTheme.shapes.extraSmall)
                        }
                    }

                    editor.fieldDefs.forEach { def ->
                        ExtraFieldInput(
                            fieldDef = def,
                            value = editor.extraValues[def.propName] ?: "",
                            onValueChange = { onExtraChange(def.propName, it) },
                            modifier = Modifier.fillMaxWidth(),
                            clearableWhenRequired = true
                        )
                    }

                    // Required fields are marked but not enforced here: they only have to be
                    // filled in before a timer stops.
                    if (editor.fieldDefs.any { it.required }) {
                        Text(
                            stringResource(R.string.hint_preset_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    editor.fieldsError?.let { err ->
                        Text(
                            errorText(err),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Removing lives here, with the preset it removes, rather than in the list.
            if (onDelete != null) {
                var confirmDelete by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.title_remove_preset))
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        title = { Text(stringResource(R.string.confirm_remove_preset_title)) },
                        text = { Text("“${editor.name}”") },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                                Text(stringResource(R.string.btn_remove))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}
