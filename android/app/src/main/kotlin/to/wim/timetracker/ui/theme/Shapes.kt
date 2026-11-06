package to.wim.timetracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).
// Components keep Material 3's own shape scale (MaterialTheme.shapes); these are the app-level
// containers the scale has no role for.

object AppShapes {
    /** Grouped settings, banners and other full-width containers. */
    val Group: Shape = RoundedCornerShape(24.dp)

    /** Rows and cards inside a list. */
    val Item: Shape = RoundedCornerShape(20.dp)
}
