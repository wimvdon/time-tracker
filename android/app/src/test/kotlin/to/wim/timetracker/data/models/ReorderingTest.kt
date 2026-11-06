package to.wim.timetracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderingTest {
    @Test
    fun `a dragged item lands at its new place and the others shift`() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moved(0, 1))
    }

    @Test
    fun `a move outside the list or onto itself changes nothing`() {
        val list = listOf("a", "b")
        assertEquals(list, list.moved(0, 0))
        assertEquals(list, list.moved(-1, 1))
        assertEquals(list, list.moved(0, 2))
    }
}
