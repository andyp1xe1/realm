package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import realm.Loaded

/** How long something can take before a skeleton or spinner fades in. */
const val INDICATOR_DELAY_MILLIS = 400

private const val INDICATOR_FADE_MILLIS = 200

private const val ARRIVE_MILLIS = 300

/** The skeletons and spinners on screen, for tests to count. */
class Indicators {
    private val faded = mutableSetOf<() -> Float>()

    /** Whether one shows, however faintly. */
    val showing: Boolean get() = faded.any { it() > 0f }

    internal fun add(alpha: () -> Float) = faded.add(alpha)

    internal fun remove(alpha: () -> Float) = faded.remove(alpha)
}

/** Where [Delayed] reports what's on screen; null in the app. */
val LocalIndicators = staticCompositionLocalOf<Indicators?> { null }

/**
 * [content] once [state]'s value is known; until then [skeleton] holds its room, drawn only after
 * [INDICATOR_DELAY_MILLIS]. Wrap each part a refresh can change in [Changing].
 */
@Composable
fun <T> Loading(state: Loaded<T>, skeleton: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        state,
        modifier,
        transitionSpec = { (fadeIn(tween(ARRIVE_MILLIS)) togetherWith fadeOut(tween(ARRIVE_MILLIS))).using(null) },
        label = "arriving",
        contentKey = { it is Loaded.Known<*> },
    ) { s ->
        when (s) {
            Loaded.Unknown -> Delayed(coming = transition.targetState == EnterExitState.Visible) { skeleton() }
            is Loaded.Known -> content(s.value)
        }
    }
}

/** [content] for [value], fading through to a new one only when it changes. */
@Composable
fun <T> Changing(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    Crossfade(value, modifier, tween(ARRIVE_MILLIS), label = "changing", content = content)
}

/**
 * A skeleton or spinner: blank, or [instead], for [INDICATOR_DELAY_MILLIS], then fading in.
 * While not [coming], it stays as faint as it is.
 */
@Composable
fun Delayed(
    modifier: Modifier = Modifier,
    coming: Boolean = true,
    instead: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(coming) {
        if (coming) alpha.animateTo(1f, tween(INDICATOR_FADE_MILLIS, delayMillis = INDICATOR_DELAY_MILLIS))
    }
    LocalIndicators.current?.let { indicators ->
        DisposableEffect(indicators) {
            val faded = { alpha.value }
            indicators.add(faded)
            onDispose { indicators.remove(faded) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (instead != null) Box(Modifier.graphicsLayer { this.alpha = 1f - alpha.value }) { instead() }
        Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
    }
}

/** A bar one line of [style] tall and [fraction] wide, for text not known yet. */
@Composable
fun SkeletonLine(fraction: Float, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Text(
        "",
        modifier.fillMaxWidth(fraction).drawBehind {
            val inset = size.height * 0.2f
            drawRoundRect(color, Offset(0f, inset), Size(size.width, size.height - 2 * inset), CornerRadius(size.height))
        },
        style = style,
    )
}

@Composable
fun Spinner(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) {
        Delayed { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
    }
}
