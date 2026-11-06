package to.wim.timetracker.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).

/** Corners of a placeholder for a line of text. */
val SkeletonLineShape: Shape = RoundedCornerShape(6.dp)

/**
 * Fills the element with a loading placeholder: a `surfaceContainerHighest` block with a soft
 * highlight sweeping across it. With animations turned off the block stays still.
 */
fun Modifier.skeleton(shape: Shape = SkeletonLineShape): Modifier =
    composed {
        val base = MaterialTheme.colorScheme.surfaceContainerHighest
        val highlight = lerp(base, MaterialTheme.colorScheme.surfaceContainerLow, 0.7f)
        val still = rememberReducedMotion()
        val sweep by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SKELETON_SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "skeletonSweep",
        )
        clip(shape).background(base).drawBehind {
            if (still) return@drawBehind
            // The highlight band is as wide as the element and travels from fully left of it
            // to fully right of it, so each pass starts and ends on the plain base colour.
            val band = size.width.coerceAtLeast(size.height)
            val x = -band + (size.width + band) * sweep
            drawRect(
                Brush.linearGradient(
                    listOf(base, highlight, base),
                    start = Offset(x, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        }
    }

/** A placeholder for one line of text, [height] tall (a body line by default). */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    Box(modifier.height(height).skeleton())
}

/** A full-width placeholder block, such as a card or a text field. */
@Composable
fun SkeletonBlock(
    height: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.Item,
) {
    Box(modifier.fillMaxWidth().height(height).skeleton(shape))
}

private const val SKELETON_SWEEP_MS = 1200
