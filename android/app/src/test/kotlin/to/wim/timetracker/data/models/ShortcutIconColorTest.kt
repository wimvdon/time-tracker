package to.wim.timetracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutIconColorTest {
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a workspace without a colour keeps the app icon`() {
        assertNull(ShortcutIconColor.fillArgb(null))
        assertNull(ShortcutIconColor.fillArgb(""))
        assertNull(ShortcutIconColor.fillArgb("   "))
        assertNull(ShortcutIconColor.fillArgb("not a colour"))
        assertNull(ShortcutIconColor.fillArgb("#12345"))
    }

    @Test
    fun `hex colours are read with or without hash and alpha`() {
        assertEquals(0xFF3B82F6.toInt(), ShortcutIconColor.parseHex("#3b82f6"))
        assertEquals(0xFF3B82F6.toInt(), ShortcutIconColor.parseHex("3B82F6"))
        assertEquals(0xFF3B82F6.toInt(), ShortcutIconColor.parseHex("#803b82f6"))
        assertEquals(0xFF3B82F6.toInt(), ShortcutIconColor.parseHex(" #3b82f6 "))
    }

    @Test
    fun `a dark enough colour is used unchanged`() {
        assertEquals(0xFF1E3A8A.toInt(), ShortcutIconColor.fillArgb("#1e3a8a"))
        assertEquals(0xFF000000.toInt(), ShortcutIconColor.fillArgb("#000000"))
    }

    @Test
    fun `every colour leaves the white clock hand readable`() {
        val colours = listOf(
            "#ef4444", "#f97316", "#eab308", "#22c55e",
            "#06b6d4", "#3b82f6", "#8b5cf6", "#ec4899",
            "#ffffff", "#ffff00", "#00ff00", "#808080"
        )
        for (hex in colours) {
            val fill = ShortcutIconColor.fillArgb(hex)
            assertNotNull(hex, fill)
            val ratio = ShortcutIconColor.contrast(fill!!, white)
            assertTrue("$hex gives $ratio", ratio >= 2.95)
        }
    }

    @Test
    fun `a light colour is darkened but keeps its hue`() {
        val fill = ShortcutIconColor.fillArgb("#eab308")!!
        val r = (fill shr 16) and 0xFF
        val g = (fill shr 8) and 0xFF
        val b = fill and 0xFF
        // Still yellow-ish: red and green well above blue, red the largest.
        assertTrue(r > b && g > b && r >= g)
        assertTrue(fill != 0xFFEAB308.toInt())
        // Darkened only as far as needed, not to black.
        assertTrue(ShortcutIconColor.contrast(fill, white) < 3.5)
    }

    @Test
    fun `grey stays grey`() {
        val fill = ShortcutIconColor.fillArgb("#c0c0c0")!!
        val r = (fill shr 16) and 0xFF
        assertEquals(r, (fill shr 8) and 0xFF)
        assertEquals(r, fill and 0xFF)
    }
}
