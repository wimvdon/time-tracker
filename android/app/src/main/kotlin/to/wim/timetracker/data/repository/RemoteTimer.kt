package to.wim.timetracker.data.repository

import to.wim.timetracker.data.models.ActiveTimer
import to.wim.timetracker.data.models.Workspace

/**
 * A timer change pushed from another device (the Windows app, through the sign-in server's
 * /timer, worker/src/push.js). Read from the push message's data.
 */
sealed interface RemoteTimerEvent {
    data class Active(
        val pageId: String,
        val previousPageId: String,
        val workspaceId: String,
        val rawStart: String,
        val roundedStart: String,
        val description: String,
        val projectId: String,
    ) : RemoteTimerEvent

    data class Stopped(val pageId: String) : RemoteTimerEvent
}

/** What the phone does with a [RemoteTimerEvent]. */
sealed interface RemoteTimerAction {
    /** Make [timer] the active timer here and show its notification. */
    data class Show(val timer: ActiveTimer, val workspace: Workspace) : RemoteTimerAction

    /** The active timer here was stopped elsewhere: forget it and remove its notification. */
    data class Clear(val pageId: String) : RemoteTimerAction

    data object Ignore : RemoteTimerAction
}

object RemoteTimer {
    /** The event in a push message's data, or null when it isn't a timer message. */
    fun parse(data: Map<String, String>): RemoteTimerEvent? {
        if (data["type"] != "timer") return null
        val pageId = data["pageId"].orEmpty()
        if (pageId.isBlank()) return null
        return when (data["event"]) {
            "stopped" -> RemoteTimerEvent.Stopped(pageId)
            "active" -> {
                val workspaceId = data["workspaceId"].orEmpty()
                val rawStart = data["rawStart"].orEmpty()
                val roundedStart = data["roundedStart"].orEmpty()
                if (workspaceId.isBlank() || rawStart.isBlank() || roundedStart.isBlank()) return null
                RemoteTimerEvent.Active(
                    pageId = pageId,
                    previousPageId = data["previousPageId"].orEmpty(),
                    workspaceId = workspaceId,
                    rawStart = rawStart,
                    roundedStart = roundedStart,
                    description = data["description"].orEmpty(),
                    projectId = data["projectId"].orEmpty(),
                )
            }
            else -> null
        }
    }

    /**
     * The phone follows the other device's active timer while it has none of its own, or while
     * it was already showing that one (or the one the other device switched away from). A timer
     * started here is never replaced by one from elsewhere.
     */
    fun decide(local: ActiveTimer?, event: RemoteTimerEvent, workspaces: List<Workspace>): RemoteTimerAction =
        when (event) {
            is RemoteTimerEvent.Stopped ->
                if (local?.notionPageId == event.pageId) RemoteTimerAction.Clear(event.pageId) else RemoteTimerAction.Ignore
            is RemoteTimerEvent.Active -> {
                val workspace = workspaces.firstOrNull { it.id == event.workspaceId }
                val follows = local == null ||
                    local.notionPageId == event.pageId ||
                    (event.previousPageId.isNotBlank() && local.notionPageId == event.previousPageId)
                if (workspace == null || !follows) {
                    RemoteTimerAction.Ignore
                } else {
                    val same = local?.notionPageId == event.pageId
                    RemoteTimerAction.Show(
                        ActiveTimer(
                            notionPageId = event.pageId,
                            roundedStart = event.roundedStart,
                            rawStart = event.rawStart,
                            workspaceId = workspace.id,
                            projectId = event.projectId.ifBlank { null },
                            projectName = if (same) local!!.projectName else "",
                            description = event.description,
                            propertyMap = workspace.propertyMap,
                            extraValues = if (same) local!!.extraValues else emptyMap(),
                        ),
                        workspace,
                    )
                }
            }
        }
}
