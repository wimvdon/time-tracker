package to.wim.timetracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

// Derives contrast-safe variants of a user-chosen workspace color: one for
// use on the light theme's near-white background, one for the dark theme's
// near-black background. Hue/saturation are preserved; lightness is only
// nudged when the original color wouldn't meet ~3:1 contrast (WCAG's
// non-text/UI-component minimum, enough to stay legible without pulling
// saturated colors like yellow so dark they read as brown).

private const val MIN_CONTRAST = 3.0
private const val LIGHT_BG_LUMINANCE = 1.0 // white: a little stricter than the light background (#FFF8F7)
private const val DARK_BG_LUMINANCE = 0.0065 // dark theme background (#181211)

// Max luminance a color can have and still hit MIN_CONTRAST against a white
// background (also covers white text/icons drawn on top of a solid fill of
// that color, since the contrast formula is symmetric when one side is white).
private val LIGHT_TARGET_LUMINANCE = (LIGHT_BG_LUMINANCE + 0.05) / MIN_CONTRAST - 0.05
// Min luminance a color needs to hit MIN_CONTRAST against the dark background.
private val DARK_TARGET_LUMINANCE = MIN_CONTRAST * (DARK_BG_LUMINANCE + 0.05) - 0.05

fun parseWorkspaceColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    }.getOrNull()
}

// Binary-searches lightness (hue/saturation fixed) for the extreme HSL
// lightness that still satisfies the luminance constraint, so the color
// moves as little as possible off the user's original choice.
private fun solveLightness(h: Float, s: Float, targetLuminance: Double, darken: Boolean): Float {
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        val argb = ColorUtils.HSLToColor(floatArrayOf(h, s, mid))
        val lum = ColorUtils.calculateLuminance(argb)
        val midSatisfies = if (darken) lum <= targetLuminance else lum >= targetLuminance
        if (darken) {
            if (midSatisfies) lo = mid else hi = mid
        } else {
            if (midSatisfies) hi = mid else lo = mid
        }
    }
    return if (darken) lo else hi
}

/**
 * Given a user-chosen color, returns (light, dark) variants that keep the
 * same hue/saturation but guarantee ~3:1 contrast against the app's
 * light-theme and dark-theme backgrounds. Not for a fill behind white text: that needs 4.5:1,
 * see [workspaceSolidFillColor].
 */
fun workspaceColorVariants(color: Color): Pair<Color, Color> {
    val argb = color.toArgb()
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    val (h, s, l) = hsl
    val currentLuminance = ColorUtils.calculateLuminance(argb)

    val lightL = if (currentLuminance <= LIGHT_TARGET_LUMINANCE) l
        else solveLightness(h, s, LIGHT_TARGET_LUMINANCE, darken = true)
    val darkL = if (currentLuminance >= DARK_TARGET_LUMINANCE) l
        else solveLightness(h, s, DARK_TARGET_LUMINANCE, darken = false)

    val light = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, lightL)))
    val dark = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, darkL)))
    return light to dark
}

/** True when the currently active Material color scheme is a dark theme. */
@Composable
fun isDarkColorScheme(): Boolean =
    ColorUtils.calculateLuminance(MaterialTheme.colorScheme.background.toArgb()) < 0.5

/** Resolves a workspace color hex to the variant matching the active theme. */
@Composable
fun workspaceAccentColor(hex: String?): Color? {
    val color = parseWorkspaceColorOrNull(hex) ?: return null
    val (light, dark) = workspaceColorVariants(color)
    return if (isDarkColorScheme()) dark else light
}

/** The workspace colour as a fill behind white text (the Start button): 4.5:1 or more ([WorkspaceFill]). */
fun workspaceSolidFillColor(hex: String?): Color? {
    val color = parseWorkspaceColorOrNull(hex) ?: return null
    return Color(0xFF000000.toInt() or WorkspaceFill.forWhiteText(color.toArgb()))
}
