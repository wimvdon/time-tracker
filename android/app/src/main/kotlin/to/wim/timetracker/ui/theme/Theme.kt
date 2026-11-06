package to.wim.timetracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import to.wim.timetracker.data.models.ThemeMode

// Fallback palette (no dynamic color, or dynamic color turned off): the red of the app icon, with
// green as the tertiary accent for Start. Material 3 tonal values from seed #F04438.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFFBA1A17),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD5),
        onPrimaryContainer = Color(0xFF930006),
        secondary = Color(0xFF775652),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDAD5),
        onSecondaryContainer = Color(0xFF5D3F3B),
        tertiary = Color(0xFF246B36),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFA9F4B0),
        onTertiaryContainer = Color(0xFF005321),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFFF8F7),
        onBackground = Color(0xFF201A19),
        surface = Color(0xFFFFF8F7),
        onSurface = Color(0xFF201A19),
        surfaceVariant = Color(0xFFF5DDDA),
        onSurfaceVariant = Color(0xFF534341),
        outline = Color(0xFF857370),
        outlineVariant = Color(0xFFD8C2BE),
        inverseSurface = Color(0xFF362F2E),
        inverseOnSurface = Color(0xFFFBEEEC),
        inversePrimary = Color(0xFFFFB4AA),
        surfaceDim = Color(0xFFE4D7D5),
        surfaceBright = Color(0xFFFFF8F7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFEF1EF),
        surfaceContainer = Color(0xFFF8EBE9),
        surfaceContainerHigh = Color(0xFFF3E5E3),
        surfaceContainerHighest = Color(0xFFEDE0DE),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFFB4AA),
        onPrimary = Color(0xFF690003),
        primaryContainer = Color(0xFF930006),
        onPrimaryContainer = Color(0xFFFFDAD5),
        secondary = Color(0xFFE7BDB7),
        onSecondary = Color(0xFF442926),
        secondaryContainer = Color(0xFF5D3F3B),
        onSecondaryContainer = Color(0xFFFFDAD5),
        tertiary = Color(0xFF8ED796),
        onTertiary = Color(0xFF003914),
        tertiaryContainer = Color(0xFF005321),
        onTertiaryContainer = Color(0xFFA9F4B0),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF181211),
        onBackground = Color(0xFFEDE0DE),
        surface = Color(0xFF181211),
        onSurface = Color(0xFFEDE0DE),
        surfaceVariant = Color(0xFF534341),
        onSurfaceVariant = Color(0xFFD8C2BE),
        outline = Color(0xFFA08C8A),
        outlineVariant = Color(0xFF534341),
        inverseSurface = Color(0xFFEDE0DE),
        inverseOnSurface = Color(0xFF362F2E),
        inversePrimary = Color(0xFFBA1A17),
        surfaceDim = Color(0xFF181211),
        surfaceBright = Color(0xFF3F3736),
        surfaceContainerLowest = Color(0xFF120D0C),
        surfaceContainerLow = Color(0xFF201A19),
        surfaceContainer = Color(0xFF251E1D),
        surfaceContainerHigh = Color(0xFF2F2827),
        surfaceContainerHighest = Color(0xFF3B3332),
    )

/** True when the device supports Material You dynamic color (Android 12+). */
val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun TimeTrackerTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && dynamicColorSupported ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }
    SystemBarsFollowTheme(useDark)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
