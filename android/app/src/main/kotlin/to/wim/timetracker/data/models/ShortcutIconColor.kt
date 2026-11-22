package to.wim.timetracker.data.models

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The colour a launcher shortcut's icon is painted with: its workspace colour,
 * darkened (same hue and saturation) just enough for the white clock hand on
 * top to keep ~3:1 contrast, the same rule as the Start button in the app
 * (`workspaceSolidFillColor`). Plain Kotlin so it runs in unit tests.
 */
object ShortcutIconColor {
    private const val MIN_CONTRAST = 3.0

    // Highest luminance a fill can have and still reach MIN_CONTRAST with white on top.
    private val MAX_FILL_LUMINANCE = (1.0 + 0.05) / MIN_CONTRAST - 0.05

    /** Opaque ARGB fill for a workspace colour, or null when the workspace has
     *  no (valid) colour, in which case the shortcut keeps the app icon. */
    fun fillArgb(hex: String?): Int? {
        val rgb = parseHex(hex) ?: return null
        if (luminance(rgb) <= MAX_FILL_LUMINANCE) return rgb
        val (h, s, _) = toHsl(rgb)
        // Binary search for the lightest lightness that is still dark enough.
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (luminance(fromHsl(h, s, mid)) <= MAX_FILL_LUMINANCE) lo = mid else hi = mid
        }
        return fromHsl(h, s, lo)
    }

    /** Parses `#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB` into an opaque ARGB int. */
    fun parseHex(hex: String?): Int? {
        val digits = hex?.trim()?.removePrefix("#") ?: return null
        if (digits.length != 6 && digits.length != 8) return null
        val value = digits.toLongOrNull(16) ?: return null
        return (0xFF000000L or (value and 0xFFFFFFL)).toInt()
    }

    /** WCAG relative luminance of an ARGB colour (alpha ignored). */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v < 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    /** WCAG contrast ratio between two colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun toHsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val l = (max + min) / 2f
        if (delta == 0f) return Triple(0f, 0f, l)
        var h = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        if (h < 0f) h += 360f
        val s = delta / (1f - abs(2f * l - 1f))
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val m = l - c / 2f
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
