package to.wim.timetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.timetracker.data.models.ActiveTimer
import to.wim.timetracker.data.models.PropertyMap
import to.wim.timetracker.data.models.Workspace

class RemoteTimerTest {
    private val workspace = Workspace(id = "w1", name = "Work", propertyMap = PropertyMap(timeEntryDateProp = "Date"))

    private val message = mapOf(
        "type" to "timer",
        "event" to "active",
        "pageId" to "page-1",
        "previousPageId" to "",
        "workspaceId" to "w1",
        "rawStart" to "2026-11-06T09:03:12.000Z",
        "roundedStart" to "2026-11-06T09:00:00.000Z",
        "description" to "Meeting",
        "projectId" to "p1",
    )

    private fun active(over: Map<String, String> = emptyMap()) = RemoteTimer.parse(message + over)!!

    private fun local(pageId: String) = ActiveTimer(
        notionPageId = pageId,
        roundedStart = "2026-11-06T08:00:00.000Z",
        rawStart = "2026-11-06T08:00:00.000Z",
        workspaceId = "w1",
        projectName = "Project",
        extraValues = mapOf("Tags" to "x"),
    )

    @Test
    fun `reads a started timer`() {
        assertEquals(
            RemoteTimerEvent.Active("page-1", "", "w1", "2026-11-06T09:03:12.000Z", "2026-11-06T09:00:00.000Z", "Meeting", "p1"),
            active(),
        )
    }

    @Test
    fun `reads a stopped timer`() {
        assertEquals(
            RemoteTimerEvent.Stopped("page-1"),
            RemoteTimer.parse(mapOf("type" to "timer", "event" to "stopped", "pageId" to "page-1")),
        )
    }

    @Test
    fun `ignores other or incomplete messages`() {
        assertNull(RemoteTimer.parse(message + ("type" to "other")))
        assertNull(RemoteTimer.parse(message + ("event" to "paused")))
        assertNull(RemoteTimer.parse(message + ("pageId" to "")))
        assertNull(RemoteTimer.parse(message + ("rawStart" to "")))
        assertNull(RemoteTimer.parse(message - "workspaceId"))
    }

    @Test
    fun `with no timer of its own, the phone shows the one from elsewhere`() {
        val action = RemoteTimer.decide(null, active(), listOf(workspace)) as RemoteTimerAction.Show
        assertEquals("page-1", action.timer.notionPageId)
        assertEquals("2026-11-06T09:03:12.000Z", action.timer.rawStart)
        assertEquals("2026-11-06T09:00:00.000Z", action.timer.roundedStart)
        assertEquals("Meeting", action.timer.description)
        assertEquals("p1", action.timer.projectId)
        // Stopping it from the phone writes to the same properties as on Windows.
        assertEquals(workspace.propertyMap, action.timer.propertyMap)
        assertEquals(workspace, action.workspace)
    }

    @Test
    fun `a change to the timer it shows is followed, keeping what only the phone knows`() {
        val action = RemoteTimer.decide(local("page-1"), active(), listOf(workspace)) as RemoteTimerAction.Show
        assertEquals("2026-11-06T09:03:12.000Z", action.timer.rawStart)
        assertEquals("Project", action.timer.projectName)
        assertEquals(mapOf("Tags" to "x"), action.timer.extraValues)
    }

    @Test
    fun `switching timers elsewhere switches here too`() {
        val action = RemoteTimer.decide(local("page-0"), active(mapOf("previousPageId" to "page-0")), listOf(workspace))
        assertTrue(action is RemoteTimerAction.Show)
        assertEquals(emptyMap<String, String>(), (action as RemoteTimerAction.Show).timer.extraValues)
    }

    @Test
    fun `a timer started on the phone is never replaced`() {
        assertEquals(RemoteTimerAction.Ignore, RemoteTimer.decide(local("phone-page"), active(), listOf(workspace)))
    }

    @Test
    fun `a workspace the phone doesn't have is ignored`() {
        assertEquals(RemoteTimerAction.Ignore, RemoteTimer.decide(null, active(mapOf("workspaceId" to "w9")), listOf(workspace)))
    }

    @Test
    fun `no project reads as none`() {
        val action = RemoteTimer.decide(null, active(mapOf("projectId" to "")), listOf(workspace)) as RemoteTimerAction.Show
        assertNull(action.timer.projectId)
    }

    @Test
    fun `stopping elsewhere clears only that timer`() {
        val stopped = RemoteTimerEvent.Stopped("page-1")
        assertEquals(RemoteTimerAction.Clear("page-1"), RemoteTimer.decide(local("page-1"), stopped, listOf(workspace)))
        assertEquals(RemoteTimerAction.Ignore, RemoteTimer.decide(local("page-2"), stopped, listOf(workspace)))
        assertEquals(RemoteTimerAction.Ignore, RemoteTimer.decide(null, stopped, listOf(workspace)))
    }
}
