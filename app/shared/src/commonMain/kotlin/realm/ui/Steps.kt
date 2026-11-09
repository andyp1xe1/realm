package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val STEP_MILLIS = 300

/**
 * [content] for each step of [state], as [step] names them: the step shown fades out, and only then the next fades
 * in, the room between them resizing. A step come back to waits its turn too.
 */
@Composable
internal fun <T> Steps(
    state: T,
    modifier: Modifier = Modifier,
    step: (T) -> Any? = { it?.let { s -> s::class } },
    content: @Composable (T) -> Unit,
) {
    val steps = remember { Any() }
    val indicators = LocalIndicators.current
    AnimatedContent(
        state,
        modifier,
        transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = true)) },
        contentKey = step,
    ) { s ->
        val alpha by transition.animateFloat(
            {
                if (targetState == EnterExitState.Visible) {
                    tween(STEP_MILLIS * 2 / 3, delayMillis = STEP_MILLIS / 3)
                } else {
                    tween(STEP_MILLIS / 3)
                }
            },
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        if (indicators != null) {
            DisposableEffect(indicators) {
                val faded = { alpha }
                indicators.addStep(steps, faded)
                onDispose { indicators.removeStep(steps, faded) }
            }
        }
        Box(Modifier.graphicsLayer { this.alpha = alpha }) { content(s) }
    }
}
