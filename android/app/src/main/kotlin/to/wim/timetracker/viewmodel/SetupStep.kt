package to.wim.timetracker.viewmodel

/**
 * What still has to be set up before the timer can be used. The timer screen shows a
 * full-screen "setup needed" state for it instead of the timer (DESIGN.md, "Empty and setup
 * states"), with a button to Settings. Same rule as Windows (`src/lib/setupStep.js`).
 */
enum class SetupStep {
    /** Not signed in to Notion yet: "Connect to Notion". */
    SignIn,

    /** Signed in but no workspace: "Add a workspace". */
    Workspace,
    ;

    companion object {
        /** Null when the timer is ready to use, or while the settings are still loading. */
        fun of(settingsLoaded: Boolean, signedIn: Boolean, workspaceCount: Int): SetupStep? =
            when {
                !settingsLoaded -> null
                !signedIn -> SignIn
                workspaceCount == 0 -> Workspace
                else -> null
            }
    }
}
