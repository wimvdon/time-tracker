package to.wim.timetracker.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).

/** The widest a list, a form or settings get: lines of text stay easy to read on wide windows. */
val ReadableWidth: Dp = 640.dp

/**
 * Fills the width up to [max] and centres it when the window is wider, so a list or form
 * keeps a readable width on tablets, foldables and in landscape.
 */
fun Modifier.readableWidth(max: Dp = ReadableWidth): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)
        .fillMaxWidth()
