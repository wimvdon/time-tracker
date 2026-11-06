package to.wim.timetracker.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import to.wim.timetracker.R
import kotlinx.coroutines.delay

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).
// Reordering looks and works the same everywhere: a drag handle at the end of each row, the
// dragged row lifted with a shadow, and Move up / Move down for screen readers.

/**
 * The grip a row is dragged by, at the end of the row (48dp touch target). Decorative on its
 * own: [ReorderableColumn] adds the screen reader actions to it; a list with its own drag code
 * puts Move up / Move down on the row instead.
 */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_drag_handle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a row of a [ReorderableColumn] needs: put [DragHandle] with [handleModifier] at its end. */
class ReorderableItemScope internal constructor(
    /** True while this row is being dragged. */
    val isDragging: Boolean,
    /** Makes a [DragHandle] drag this row (and gives it Move up / Move down for screen readers). */
    val handleModifier: Modifier,
)

/**
 * A short list (settings rows, a dialog's list) whose rows are reordered by dragging their
 * [DragHandle]. The dragged row moves at once and is lifted; [onMove] gets the old and the new
 * position when it is dropped. Rows for which [movable] is false stay where they are: they have
 * no handle to drag, and other rows can't pass them.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    movable: (T) -> Boolean = { true },
    row: @Composable ReorderableItemScope.(item: T) -> Unit,
) {
    // A working copy, so a dragged row moves before the change is stored; it follows [items]
    // again once the stored order comes back (or after a second, if nothing changed).
    val order = remember { mutableStateListOf<T>() }
    var dragged by remember { mutableStateOf<Any?>(null) }
    var draggedFrom by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var waitingFor by remember { mutableStateOf<List<T>?>(null) }
    if (dragged == null && items != waitingFor && order.toList() != items) {
        order.clear()
        order.addAll(items)
    }
    LaunchedEffect(items) { waitingFor = null }
    LaunchedEffect(waitingFor) {
        if (waitingFor != null) {
            delay(1000)
            waitingFor = null
        }
    }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentKey by rememberUpdatedState(key)
    val currentMovable by rememberUpdatedState(movable)
    val haptics = LocalHapticFeedback.current
    val reorder = stringResource(R.string.action_reorder)
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    val lifted = MaterialTheme.colorScheme.surfaceContainerHigh

    fun indexOf(k: Any) = order.indexOfFirst { currentKey(it) == k }

    fun canSwap(to: Int) = to in order.indices && currentMovable(order[to])

    fun heightAt(index: Int) = order.getOrNull(index)?.let { heights[currentKey(it)] }?.toFloat() ?: 0f

    // One step for screen readers: stored at once.
    fun step(k: Any, by: Int): Boolean {
        val from = currentItems.indexOfFirst { currentKey(it) == k }
        val to = from + by
        if (from < 0 || to !in currentItems.indices || !currentMovable(currentItems[to])) return false
        currentOnMove(from, to)
        return true
    }

    Column(modifier) {
        order.forEach { item ->
            val k = key(item)
            key(k) {
                val isDragging = dragged == k
                val canMove = movable(item)
                val handle =
                    if (!canMove) {
                        Modifier
                    } else {
                        Modifier
                            .semantics {
                                contentDescription = reorder
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(moveUp) { step(k, -1) },
                                        CustomAccessibilityAction(moveDown) { step(k, 1) },
                                    )
                            }.pointerInput(k) {
                                detectDragGestures(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragged = k
                                        draggedFrom = indexOf(k)
                                        offset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        // Past half the neighbour: swap with it and keep the row under the finger.
                                        val at = indexOf(k)
                                        val next = heightAt(at + 1)
                                        val previous = heightAt(at - 1)
                                        if (offset > next / 2 && canSwap(at + 1)) {
                                            order.add(at + 1, order.removeAt(at))
                                            offset -= next
                                        } else if (offset < -previous / 2 && canSwap(at - 1)) {
                                            order.add(at - 1, order.removeAt(at))
                                            offset += previous
                                        }
                                    },
                                    onDragEnd = {
                                        val to = indexOf(k)
                                        val from = draggedFrom
                                        dragged = null
                                        offset = 0f
                                        if (from >= 0 && to >= 0 && from != to) {
                                            waitingFor = currentItems
                                            currentOnMove(from, to)
                                        }
                                    },
                                    onDragCancel = {
                                        dragged = null
                                        offset = 0f
                                        order.clear()
                                        order.addAll(currentItems)
                                    },
                                )
                            }
                    }
                Box(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = offset
                                shadowElevation = 8.dp.toPx()
                            }
                        }.then(if (isDragging) Modifier.background(lifted) else Modifier),
                ) {
                    ReorderableItemScope(isDragging, handle).row(item)
                }
            }
        }
    }
}
