package to.wim.timetracker.viewmodel

/** Whether the recent entries list says it couldn't load (same rule as the Windows app's store). */
object RecentEntriesLoad {
    /** Only when a workspace's query failed and nothing is left to show; a partial list is shown as is. */
    fun failed(anyQueryFailed: Boolean, shownCount: Int): Boolean = anyQueryFailed && shownCount == 0
}
