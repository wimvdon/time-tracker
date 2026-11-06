package to.wim.timetracker.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.wim.timetracker.R

// Shared design kit: this file is identical in every app apart from the package and R imports
// (see DESIGN.md). It needs R.drawable.ic_arrow_back, R.drawable.ic_expand_more and the strings
// section_expanded and section_collapsed.

/** Readable width of the settings list on wide windows (tablets, landscape, desktop). */
private val SettingsMaxWidth = 640.dp

/**
 * The settings screen: a large top app bar that collapses on scroll, over one list of
 * [SettingsSection]s in the order DESIGN.md gives, ending with a [SettingsFooter].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Edge-to-edge: the keyboard does not shrink the screen, so the list makes room for it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        snackbarHost = snackbarHost,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backLabel)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .widthIn(max = SettingsMaxWidth)
                        .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                content = content,
            )
        }
    }
}

/**
 * A group of related settings under a section header (Title Small in the primary colour).
 * Its rows are [SwitchSetting], [SettingItem], [ExpandableSetting] and [SettingsBlock].
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                    .semantics { heading() },
        )
        content()
    }
}

/**
 * Content inside a section that is not a list row (a text field, segmented buttons, an
 * explanation, a button), indented to line up with the rows' text.
 */
@Composable
fun SettingsBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A setting with a switch. The whole row is one accessible switch and applies at once. */
@Composable
fun SwitchSetting(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: Painter? = null,
    warning: String? = null,
    onWarningClick: (() -> Unit)? = null,
) {
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
            modifier =
                Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (onWarningClick != null) Modifier.clickable(role = Role.Button, onClick = onWarningClick) else Modifier)
                        .padding(start = if (icon != null) 56.dp else 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * A row that opens something: another page, a dialog or another app. [trailing] can show a
 * chevron or an "opens elsewhere" icon.
 */
@Composable
fun SettingItem(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = trailing,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/**
 * A row that shows or hides more content below it, for longer instructions that most people
 * only read once. Expanded state survives rotation.
 */
@Composable
fun ExpandableSetting(
    key: String,
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val stateText = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.rotate(chevronRotation),
                )
            },
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = { expanded = !expanded })
                    .semantics { stateDescription = stateText },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * The rows of every app's Appearance section: the theme as segmented buttons
 * (System / Light / Dark) and, on Android 12+, the Dynamic color switch.
 */
@Composable
fun AppearanceSettings(
    themeLabel: String,
    themeOptions: List<String>,
    selectedTheme: Int,
    onThemeSelect: (Int) -> Unit,
    dynamicColorTitle: String,
    dynamicColorSummary: String,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    dynamicColorSupported: Boolean,
) {
    SettingsBlock {
        Text(themeLabel, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            themeOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedTheme,
                    onClick = { onThemeSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                ) { Text(label) }
            }
        }
    }
    if (dynamicColorSupported) {
        SwitchSetting(
            title = dynamicColorTitle,
            summary = dynamicColorSummary,
            checked = dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )
    }
}

/** The last item of the settings list: the app name and version, plus optional links. */
@Composable
fun SettingsFooter(
    text: String,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The installed version name, for the [SettingsFooter]. */
@Composable
fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }
}
