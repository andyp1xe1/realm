// Showing fetched data: the one supported way. While it's unknown, a skeleton holds the room it will take;
// when it arrives, the skeleton fades through to it in place, and a refresh changes it where it stands. Data
// arriving never grows the layout: no expandVertically or animateContentSize for it (.githooks/grows).
package realm.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import realm.Loaded

/** How long the skeleton takes to fade through to what arrived. */
private const val ARRIVE_MILLIS = 300

/**
 * [content] for [state]'s value once it's known; until then [skeleton], which takes the room the content
 * will: build it from the same rows, with [SkeletonLine] for the text that isn't known yet. The one fades
 * through to the other, with no change of size of its own; a refresh, or any change of the value, shows in
 * place. Known from the start, as from a cache, the content shows at once.
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
            Loaded.Unknown -> skeleton()
            is Loaded.Known -> content(s.value)
        }
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

/** A small turning circle where a skeleton makes no sense, keeping [height], the room what's coming takes. */
@Composable
fun Spinner(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
