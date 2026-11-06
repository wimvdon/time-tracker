package to.wim.timetracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One full, very slow drift of the glow. Long on purpose: a running timer should feel calm. */
private const val DRIFT_CYCLE_MS = 24_000
private const val TWO_PI = (2 * PI).toFloat()

/**
 * Soft, slowly breathing pools of colour behind the running timer, the same glow as Snoozer's
 * snoozing screen. No lines or rings, just light that drifts and swells over ~24 seconds. With
 * animations disabled system-wide it is drawn once, standing still.
 */
@Composable
fun RunningGlow(
    primary: Color,
    secondary: Color,
    center: Offset?,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    // Read inside Canvas (draw phase) so the animation only redraws, never recomposes.
    val animatedPhase =
        if (animate) {
            rememberInfiniteTransition(label = "glow").animateFloat(
                initialValue = 0f,
                targetValue = TWO_PI,
                animationSpec = infiniteRepeatable(tween(DRIFT_CYCLE_MS, easing = LinearEasing)),
                label = "glow-phase",
            )
        } else {
            null
        }

    Canvas(modifier) {
        val phase = animatedPhase?.value ?: 0f
        val origin = center ?: this.center
        val maxDimension = maxOf(size.width, size.height)
        val drift = 36.dp.toPx()

        // Main pool: centred on the timer, slowly swelling and fading like breathing.
        drawPool(
            color = primary,
            alpha = 0.30f + 0.08f * sin(phase * 2),
            center = origin + Offset(cos(phase) * drift * 0.5f, sin(phase) * drift * 0.5f),
            radius = maxDimension * (0.55f + 0.06f * sin(phase * 2)),
        )
        // Two companions drifting around it in opposite directions for depth.
        drawPool(
            color = secondary,
            alpha = 0.22f + 0.06f * cos(phase),
            center = origin + Offset(cos(phase + PI.toFloat()) * drift * 3, sin(phase) * drift * 2),
            radius = maxDimension * (0.42f + 0.05f * cos(phase)),
        )
        drawPool(
            color = primary,
            alpha = 0.14f + 0.05f * sin(phase),
            center = origin + Offset(sin(-phase) * drift * 2.5f, cos(-phase) * drift * 3.5f),
            radius = maxDimension * 0.38f,
        )
    }
}

private fun DrawScope.drawPool(
    color: Color,
    alpha: Float,
    center: Offset,
    radius: Float,
) {
    drawCircle(
        brush =
            Brush.radialGradient(
                colorStops =
                    arrayOf(
                        0f to color.copy(alpha = alpha),
                        0.55f to color.copy(alpha = alpha * 0.35f),
                        1f to Color.Transparent,
                    ),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )
}
