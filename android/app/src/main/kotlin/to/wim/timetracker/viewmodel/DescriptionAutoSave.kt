package to.wim.timetracker.viewmodel

/**
 * When the description (page title) of a running timer is saved while the user types.
 * Same rule and delay as the Windows app (`windows/src/lib/descriptionAutoSave.js`).
 */
object DescriptionAutoSave {
    /** Pause after the last keystroke before the description is written to Notion. */
    const val DELAY_MS = 800L

    /**
     * Saves only a real change. An emptied field is not saved while typing (the page would get
     * the date as title until the next word is typed); Done or Stop saves it.
     */
    fun shouldSave(draft: String, saved: String): Boolean {
        val trimmed = draft.trim()
        return trimmed.isNotEmpty() && trimmed != saved.trim()
    }
}
