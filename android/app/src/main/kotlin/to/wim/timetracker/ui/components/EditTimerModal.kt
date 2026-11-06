package to.wim.timetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.viewmodel.TimerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTimerModal(
    uiState: TimerViewModel.UiState,
    onDismiss: () -> Unit,
    onSave: (projectId: String?, description: String, extraValues: Map<String, String>) -> Unit
) {
    val timer = uiState.activeTimer ?: return
    var description by remember(timer.notionPageId) { mutableStateOf(timer.description) }
    var selectedProjectId by remember(timer.notionPageId) { mutableStateOf(timer.projectId) }
    var extraValues by remember(timer.notionPageId) { mutableStateOf(uiState.extraValues) }
    // Set as soon as the user changes something here. The 60-second refresh keeps
    // pushing Notion's values into uiState while this modal is open; adopting them
    // after an edit would throw the unsaved change away mid-typing.
    var dirty by remember(timer.notionPageId) { mutableStateOf(false) }
    LaunchedEffect(uiState.extraFieldDefs, timer.projectId, uiState.extraValues, timer.notionPageId) {
        if (dirty) return@LaunchedEffect
        selectedProjectId = timer.projectId
        extraValues = uiState.extraValues
    }

    val scrollState = rememberScrollState()

    Scaffold(
        // Edge-to-edge: the keyboard does not shrink the screen, so the content makes room for it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_timer)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.btn_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(selectedProjectId, description, extraValues) }) {
                        Text(stringResource(R.string.btn_save_changes))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))



            val descriptionRequired = uiState.workspaces
                .firstOrNull { it.id == timer.workspaceId }?.descriptionRequired == true
            OutlinedTextField(
                value = description,
                onValueChange = { description = it; dirty = true },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = descriptionRequired && description.isBlank(),
                label = {
                    Text(stringResource(R.string.placeholder_description) + if (descriptionRequired) " *" else "")
                },
                placeholder = { Text(stringResource(R.string.label_description)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                )
            )

            uiState.extraFieldDefs.forEach { def ->
                ExtraFieldInput(
                    fieldDef = def,
                    value = extraValues[def.propName] ?: "",
                    onValueChange = { extraValues = extraValues + (def.propName to it); dirty = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
