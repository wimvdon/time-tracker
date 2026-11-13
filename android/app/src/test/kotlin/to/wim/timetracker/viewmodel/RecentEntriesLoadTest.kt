package to.wim.timetracker.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentEntriesLoadTest {
    @Test
    fun `a failed query with nothing to show is a failure`() {
        assertTrue(RecentEntriesLoad.failed(anyQueryFailed = true, shownCount = 0))
    }

    @Test
    fun `entries from another workspace are shown instead of the error`() {
        assertFalse(RecentEntriesLoad.failed(anyQueryFailed = true, shownCount = 2))
    }

    @Test
    fun `an empty list that loaded fine is not a failure`() {
        assertFalse(RecentEntriesLoad.failed(anyQueryFailed = false, shownCount = 0))
    }
}
