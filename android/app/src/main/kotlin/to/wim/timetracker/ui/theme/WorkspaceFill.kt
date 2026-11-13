package to.wim.timetracker.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A workspace colour as a solid fill behind white text (the Start button): darkened, hue and
 * saturation kept, until white text on it reaches 4.5:1 (WCAG AA for normal text). Plain Kotlin
 * on packed 0xRRGGBB ints so it runs in unit tests; the Windows app does the same in color.js.
 */
object WorkspaceFill {
    const val TEXT_CONTRAST = 4.5
    // Aims at 4.6 so rounding to whole RGB values never lands just under 4.5.
    private val TARGET_LUMINANCE = (1.0 + 0.05) / (TEXT_CONTRAST + 0.1) - 0.05

    fun forWhiteText(rgb: Int): Int {
        if (luminance(rgb) <= TARGET_LUMINANCE) return rgb and 0xFFFFFF
        val (h, s, l) = toHsl(rgb)
        var lo = 0.0
        var hi = l
        repeat(24) {
            val mid = (lo + hi) / 2
            if (luminance(fromHsl(h, s, mid)) <= TARGET_LUMINANCE) lo = mid else hi = mid
        }
        return fromHsl(h, s, lo)
    }

    fun contrastWithWhite(rgb: Int): Double = 1.05 / (luminance(rgb) + 0.05)

    fun luminance(rgb: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(rgb shr 16 and 0xFF) + 0.7152 * lin(rgb shr 8 and 0xFF) + 0.0722 * lin(rgb and 0xFF)
    }

    private fun toHsl(rgb: Int): Triple<Double, Double, Double> {
        val r = (rgb shr 16 and 0xFF) / 255.0
        val g = (rgb shr 8 and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        var h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        if (h < 0) h += 360
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = Math.round((v + m) * 255).toInt().coerceIn(0, 255)
        return (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
