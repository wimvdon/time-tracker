package to.wim.timetracker.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).

/**
 * enableEdgeToEdge() picks the system bar icon colours from the system's dark mode; follow the
 * app's own theme instead, or a forced light/dark theme gets unreadable status bar icons.
 */
@Composable
fun SystemBarsFollowTheme(dark: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(activity, dark) {
        activity ?: return@LaunchedEffect
        val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
        )
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
