package to.wim.timetracker.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionAutoSaveTest {
    @Test
    fun `a changed description is saved`() {
        assertTrue(DescriptionAutoSave.shouldSave("Meeting with Anna", "Meeting"))
        assertTrue(DescriptionAutoSave.shouldSave("Meeting", ""))
    }

    @Test
    fun `an unchanged description is not saved again`() {
        assertFalse(DescriptionAutoSave.shouldSave("Meeting", "Meeting"))
    }

    @Test
    fun `spaces around the text do not count as a change`() {
        assertFalse(DescriptionAutoSave.shouldSave("Meeting ", "Meeting"))
        assertFalse(DescriptionAutoSave.shouldSave("  Meeting", "Meeting "))
    }

    @Test
    fun `an emptied field is not saved while typing`() {
        assertFalse(DescriptionAutoSave.shouldSave("", "Meeting"))
        assertFalse(DescriptionAutoSave.shouldSave("   ", "Meeting"))
    }
}
