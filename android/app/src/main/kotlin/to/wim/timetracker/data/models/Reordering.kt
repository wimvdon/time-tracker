package to.wim.timetracker.data.models

/**
 * The list with the item at [from] moved to [to] (a workspace or preset dragged by its handle).
 * Positions outside the list leave it unchanged.
 */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
