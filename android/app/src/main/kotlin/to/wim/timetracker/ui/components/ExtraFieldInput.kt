package to.wim.timetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.data.models.ExtraFieldDef
import to.wim.timetracker.data.models.NamedOption

/**
 * One of a workspace's extra Notion properties as a form field. Its name is the field's own
 * Material 3 label (floating inside the field), not a separate line above it; only the
 * multi-select chips, which have no field container, keep a label above.
 */
@Composable
fun ExtraFieldInput(
    fieldDef: ExtraFieldDef,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    // A preset may leave a required field empty (it's only needed when a timer stops), so the
    // preset editor lets a required dropdown be cleared too.
    clearableWhenRequired: Boolean = false
) {
    val label = fieldDef.propName + if (fieldDef.required) " *" else ""

    when (fieldDef.type) {
        "checkbox" -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = value == "true",
                    onCheckedChange = { onValueChange(if (it) "true" else "false") }
                )
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        "select", "status" -> {
            SearchableDropdown(
                options = fieldDef.options,
                selected = fieldDef.options.firstOrNull { it.id == value || it.name == value },
                placeholder = stringResource(R.string.placeholder_select),
                onSelect = { onValueChange(it?.name ?: "") },
                allowClear = !fieldDef.required || clearableWhenRequired,
                searchable = fieldDef.options.size > 5,
                label = label,
                modifier = modifier
            )
        }

        "multi_select" -> {
            Column(modifier = modifier) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                MultiSelectChips(
                    options = fieldDef.options,
                    selected = value.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                    onSelectionChange = { onValueChange(it.joinToString(",")) }
                )
            }
        }

        "people", "relation" -> {
            // A Notion relation/people property can hold several ids, which are
            // stored comma-separated. The dropdown edits one, but it has to show
            // the first stored id rather than reading a multi-value link as empty.
            val firstId = value.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() }
            SearchableDropdown(
                options = fieldDef.options,
                selected = fieldDef.options.firstOrNull { it.id == firstId },
                placeholder = stringResource(R.string.placeholder_select),
                onSelect = { onValueChange(it?.id ?: "") },
                allowClear = !fieldDef.required || clearableWhenRequired,
                searchable = true,
                label = label,
                modifier = modifier
            )
        }

        "number" -> ExtraTextField(
            value = value,
            onValueChange = { if (it.isEmpty() || it.matches(Regex("-?\\d*\\.?\\d*"))) onValueChange(it) },
            label = label,
            placeholder = "0",
            modifier = modifier
        )

        "date" -> ExtraTextField(value, onValueChange, label, "YYYY-MM-DD", modifier)
        "url", "files" -> ExtraTextField(value, onValueChange, label, "https://…", modifier)
        "email" -> ExtraTextField(value, onValueChange, label, "name@example.com", modifier)
        "phone_number" -> ExtraTextField(value, onValueChange, label, "+32…", modifier)
        else -> ExtraTextField(value, onValueChange, label, null, modifier)
    }
}

@Composable
private fun ExtraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let {
            { Text(it, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
        )
    )
}

@Composable
private fun MultiSelectChips(
    options: List<NamedOption>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { opt ->
            val isSelected = opt.name in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    val newSet = if (isSelected) selected - opt.name else selected + opt.name
                    onSelectionChange(newSet)
                },
                label = { Text(opt.name) }
            )
        }
    }
}
