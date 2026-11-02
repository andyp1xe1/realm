// Showing fetched data: the one supported way. In order of preference: what was kept from last time, shown at
// once and changed in place only where a refresh found something different; nothing while it's fetched, in the
// room it will take, then the content fading in; and only once that's taken INDICATOR_DELAY_MILLIS, a skeleton
// or spinner fading in, so a fast fetch never flashes one. Data arriving never grows the layout: no
// expandVertically or animateContentSize for it (.githooks/grows).
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

/** How long something can take to arrive before a skeleton or spinner fades in to say it's still coming. */
const val INDICATOR_DELAY_MILLIS = 400

/** How long a skeleton or spinner takes to fade in, once it's waited [INDICATOR_DELAY_MILLIS]. */
private const val INDICATOR_FADE_MILLIS = 200

/** How long content takes to fade in as it arrives, or through to a changed value. */
private const val ARRIVE_MILLIS = 300

/** The skeletons and spinners on screen, for tests and previews to count the frames that show one. */
class Indicators {
    private val faded = mutableSetOf<() -> Float>()

    /** Whether one shows, however faintly. */
    val showing: Boolean get() = faded.any { it() > 0f }

    internal fun add(alpha: () -> Float) = faded.add(alpha)

    internal fun remove(alpha: () -> Float) = faded.remove(alpha)
}

/** Where [Delayed] says it's on screen, when something asks; the app doesn't. */
val LocalIndicators = staticCompositionLocalOf<Indicators?> { null }

/**
 * [content] for [state]'s value once it's known. Until then, [skeleton] holds the room the content will take
 * (build it from the same rows, with [SkeletonLine] for the text that isn't known yet), drawn only once the
 * value has been coming for [INDICATOR_DELAY_MILLIS]: before that the room is blank. The content fades in over
 * it, with no change of size of its own. Known from the start, as from a cache, the content shows at once. A
 * refresh recomposes [content] in place: wrap each part that can change in [Changing], so only what changed
 * fades, never the whole area.
 */
@Composable
fun <T> Loading(state: Loaded<T>, skeleton: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        state,
        modifier,
        // A fade, and no size transform: the skeleton has kept the room already.
        transitionSpec = { (fadeIn(tween(ARRIVE_MILLIS)) togetherWith fadeOut(tween(ARRIVE_MILLIS))).using(null) },
        label = "arriving",
        contentKey = { it is Loaded.Known<*> },
    ) { s ->
        when (s) {
            // Once the value is on its way in, the skeleton stops coming: it never starts to show as it goes.
            Loaded.Unknown -> Delayed(coming = transition.targetState == EnterExitState.Visible) { skeleton() }

            is Loaded.Known -> content(s.value)
        }
    }
}

/**
 * [content] for [value], fading through to the new one when it changes, and only then: a refresh that finds the
 * same leaves it as it is. For each row or text of a [Loading]'s content that a refresh can change.
 */
@Composable
fun <T> Changing(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    Crossfade(value, modifier, tween(ARRIVE_MILLIS), label = "changing", content = content)
}

/**
 * A skeleton or spinner: blank, or [instead] (like the icon it turns in place of), for [INDICATOR_DELAY_MILLIS],
 * then fading in over it, so it shows only for what's slow to come. While not [coming], it stays as faint as it
 * is. The wait runs on the frame clock, as animations do.
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

/**
 * A line of text not known yet: a rounded bar exactly one line of [style] tall, as the text it stands for
 * will be, [fraction] of the width across.
 */
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

/**
 * A small turning circle where a skeleton makes no sense, keeping [height], the room what's coming takes.
 * Like a skeleton, it shows only once what it waits for has taken [INDICATOR_DELAY_MILLIS].
 */
@Composable
fun Spinner(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) {
        Delayed { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
    }
}
