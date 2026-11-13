package to.wim.timetracker.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFillTest {
    @Test
    fun `white text reaches 4_5 to 1 on every fill`() {
        for (rgb in listOf(0xFFEB3B, 0x00BCD4, 0x4CAF50, 0xFF9800, 0x0078D4, 0xE91E63, 0xFFFFFF, 0x9E9E9E)) {
            val fill = WorkspaceFill.forWhiteText(rgb)
            assertTrue("%06X -> %06X".format(rgb, fill), WorkspaceFill.contrastWithWhite(fill) >= 4.5)
        }
    }

    @Test
    fun `a colour that already passes is left alone`() {
        assertEquals(0x188038, WorkspaceFill.forWhiteText(0x188038))
        assertEquals(0x000000, WorkspaceFill.forWhiteText(0x000000))
    }

    @Test
    fun `the hue is kept`() {
        val fill = WorkspaceFill.forWhiteText(0x00BCD4)
        // Still a cyan: no red, green and blue both present.
        assertEquals(0, fill shr 16 and 0xFF)
        assertTrue((fill shr 8 and 0xFF) > 0 && (fill and 0xFF) > 0)
    }
}
