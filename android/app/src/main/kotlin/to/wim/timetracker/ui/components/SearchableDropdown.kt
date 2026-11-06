package to.wim.timetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R
import to.wim.timetracker.data.models.NamedOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableDropdown(
    options: List<NamedOption>,
    selected: NamedOption?,
    placeholder: String,
    onSelect: (NamedOption?) -> Unit,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    searchable: Boolean = true,
    label: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    optionLeadingIcon: (@Composable (NamedOption) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val loadingText = stringResource(R.string.placeholder_loading)
    val searchText = stringResource(R.string.placeholder_search)

    val filtered = remember(query, options) {
        if (query.isBlank()) options
        else options.filter { it.name.contains(query, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { newExpanded ->
            if (enabled && !loading) {
                expanded = newExpanded
                if (!newExpanded) query = ""
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = when {
                loading -> loadingText
                expanded && searchable -> query
                selected != null -> selected.name
                else -> ""
            },
            onValueChange = { if (expanded && searchable) query = it },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
            readOnly = !searchable || !expanded,
            enabled = enabled && !loading,
            label = label?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            placeholder = {
                Text(
                    text = when {
                        loading -> loadingText
                        expanded && searchable -> searchText
                        else -> placeholder
                    }
                )
            },
            trailingIcon = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (allowClear && selected != null && !loading && !expanded) {
                        IconButton(
                            onClick = { onSelect(null) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = stringResource(R.string.btn_clear),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                focusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            ),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; query = "" }
        ) {
            if (allowClear && selected != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.dropdown_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = { onSelect(null); expanded = false; query = "" },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
                HorizontalDivider()
            }

            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.search_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {},
                    enabled = false,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            } else {
                filtered.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.name) },
                        onClick = { onSelect(opt); expanded = false; query = "" },
                        leadingIcon = optionLeadingIcon?.let { icon -> { icon(opt) } },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        colors = MenuDefaults.itemColors(
                            textColor = if (opt.id == selected?.id)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}
