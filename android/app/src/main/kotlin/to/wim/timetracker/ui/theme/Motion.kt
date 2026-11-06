package to.wim.timetracker.ui.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

// Shared design kit: this file is identical in every app apart from the package (see DESIGN.md).

/** Material 3 motion tokens (easing and duration), shared by the app's transitions. */
object Motion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT_4 = 200
    const val MEDIUM_2 = 300
    const val MEDIUM_4 = 400
    const val LONG_2 = 500

    /** Fade-through for state switches such as loading, content and error. */
    fun <T> fadeSpec(
        durationMillis: Int = MEDIUM_2,
        delayMillis: Int = 0,
    ): FiniteAnimationSpec<T> = tween(durationMillis, delayMillis, Standard)

    /** Progress bars and other values that move to a new state. */
    fun <T> progressSpec(): FiniteAnimationSpec<T> = tween(LONG_2, easing = Emphasized)

    /**
     * Material 3 shared-axis X between screens: both slide [offsetPx] (30dp) along the axis; the
     * old screen fades out quickly, then the new one fades in, so they never overlap at full opacity.
     */
    fun sharedAxisX(
        forward: Boolean,
        offsetPx: Int,
    ): ContentTransform {
        val slide = tween<IntOffset>(MEDIUM_2, easing = Emphasized)
        val enter =
            slideInHorizontally(slide) { if (forward) offsetPx else -offsetPx } +
                fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate))
        val exit =
            slideOutHorizontally(slide) { if (forward) -offsetPx else offsetPx } +
                fadeOut(tween(90, easing = EmphasizedAccelerate))
        return enter togetherWith exit
    }
}

/** True when the user switched off animations ("Remove animations" / animator scale 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
