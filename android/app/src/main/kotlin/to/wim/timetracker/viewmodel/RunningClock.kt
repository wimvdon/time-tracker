package to.wim.timetracker.viewmodel

import to.wim.timetracker.data.models.ActiveTimer
import java.time.OffsetDateTime

/**
 * The running timer's clock: how long it has run, and what changes when its start time is
 * edited. Editing the start must move everything that counts from it (the elapsed time on
 * screen, the notification, and the end time a Stop from the notification writes), not
 * only the time stored in Notion.
 */
object RunningClock {
    /** Whole seconds since [startMs], never negative (a start in the future reads 0). */
    fun elapsedSeconds(startMs: Long, nowMs: Long): Long = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)

    /** The timer with its start moved to [newStartIso], and that start in milliseconds; null if it can't be read. */
    fun withNewStart(timer: ActiveTimer, newStartIso: String): Pair<ActiveTimer, Long>? {
        val startMs = runCatching { OffsetDateTime.parse(newStartIso).toInstant().toEpochMilli() }.getOrNull()
            ?: return null
        return timer.copy(roundedStart = newStartIso, rawStart = newStartIso) to startMs
    }
}
