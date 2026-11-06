package to.wim.timetracker.ui.components

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import to.wim.timetracker.ui.theme.rememberReducedMotion

/** One full turn of the hero shape while a timer runs. */
private const val ROTATION_PERIOD_MS = 30_000

/**
 * The nine-lobed "cookie" of the Material 3 Expressive shape set (the same shape as Snoozer's
 * toggle), built with androidx.graphics.shapes and scaled to the layout bounds.
 */
private object CookieShape : Shape {
    private val polygon =
        RoundedPolygon.star(
            numVerticesPerRadius = 9,
            radius = 1f,
            innerRadius = 0.86f,
            rounding = CornerRounding(radius = 0.32f, smoothing = 0.6f),
        )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath()
        path.transform(
            Matrix().apply {
                setScale(size.width / 2f, size.height / 2f)
                postTranslate(size.width / 2f, size.height / 2f)
            },
        )
        return Outline.Generic(path.asComposePath())
    }
}

/**
 * The timer: the elapsed time on a cookie shape. While [running] the cookie turns slowly and
 * springs in when the timer starts; idle, it stands still. There is no progress ring: a running
 * timer has no target to progress towards. With animations switched off on the device, the shape
 * stands still.
 */
@Composable
fun TimerHero(
    elapsedText: String,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    val animate = running && !rememberReducedMotion()

    val appear = remember { Animatable(if (animate) 0.7f else 1f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }

    val rotation = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            rotation.animateTo(rotation.value + 360f, tween(ROTATION_PERIOD_MS, easing = LinearEasing))
            rotation.snapTo(rotation.value % 360f)
        }
    }

    Box(
        modifier =
            modifier
                .size(272.dp)
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = rotation.value }
                .background(container, CookieShape),
        )
        // Tabular figures keep the digits from shifting as they tick.
        Text(
            elapsedText,
            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
            color = contentColor,
        )
    }
}
