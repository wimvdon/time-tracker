package to.wim.timetracker.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.wim.timetracker.data.models.ActiveTimer
import java.time.OffsetDateTime

class RunningClockTest {
    private val timer = ActiveTimer(
        notionPageId = "page",
        roundedStart = "2026-11-06T09:00:00.000+01:00",
        rawStart = "2026-11-06T09:03:12.000+01:00",
        workspaceId = "ws",
    )

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun `counts whole seconds since the start`() {
        val start = ms("2026-11-06T09:00:00.000+01:00")
        assertEquals(0L, RunningClock.elapsedSeconds(start, start))
        assertEquals(90L, RunningClock.elapsedSeconds(start, start + 90_999L))
    }

    @Test
    fun `a start in the future reads zero`() {
        val start = ms("2026-11-06T09:00:00.000+01:00")
        assertEquals(0L, RunningClock.elapsedSeconds(start, start - 60_000L))
    }

    @Test
    fun `an earlier start makes the running time longer`() {
        val now = ms("2026-11-06T10:00:00.000+01:00")
        val (moved, startMs) = RunningClock.withNewStart(timer, "2026-11-06T08:30:00.000+01:00")!!
        assertEquals("2026-11-06T08:30:00.000+01:00", moved.rawStart)
        assertEquals("2026-11-06T08:30:00.000+01:00", moved.roundedStart)
        assertEquals(90 * 60L, RunningClock.elapsedSeconds(startMs, now))
    }

    @Test
    fun `keeps everything else about the timer`() {
        val (moved, _) = RunningClock.withNewStart(timer, "2026-11-06T08:30:00.000+01:00")!!
        assertEquals(timer.copy(roundedStart = moved.roundedStart, rawStart = moved.rawStart), moved)
    }

    @Test
    fun `an unreadable start changes nothing`() {
        assertNull(RunningClock.withNewStart(timer, "not a time"))
    }
}
