package to.wim.timetracker.data.repository

/**
 * What a settings sync should do, given this device's state and the copy on the sync server
 * (null when there is none yet). Same rule as the Windows app (`src/lib/syncDecision.js`).
 */
enum class SyncDecision {
    /** The server has newer settings; take them. */
    Pull,

    /** This device has edits the server doesn't have, or the server is empty and this device has something to share. */
    Push,

    /** Nothing to do. A new install with no settings never pushes, so it can't wipe another device's settings. */
    None,
    ;

    companion object {
        fun of(settingsUpdatedAt: String?, lastSyncedUpdatedAt: String?, hasSettings: Boolean, remoteUpdatedAt: String?): SyncDecision {
            val localAt = settingsUpdatedAt.orEmpty()
            return when {
                remoteUpdatedAt != null && remoteUpdatedAt > localAt -> Pull
                remoteUpdatedAt == null -> if (localAt.isNotEmpty() || hasSettings) Push else None
                localAt.isNotEmpty() && localAt != lastSyncedUpdatedAt -> Push
                else -> None
            }
        }
    }
}
