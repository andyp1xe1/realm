package realm.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How long a tab takes to zoom in or out; the overview fades over the same time. */
const val TAB_ZOOM_MILLIS = 380

/** Widest a page gets; the feed and the tab overview share it. */
val PageWidth = 820.dp

/** Space between the page edge and its content, on the feed and the tab overview alike. */
val PageGutter = 12.dp

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalTabTransition = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Ties a realm's feed and its tile together, so switching between them zooms one into the other
 * like a browser's tabs. Scales rather than re-lays out, so the feed shrinks as a picture of itself.
 * Without a transition around it (previews), it does nothing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.tabBounds(key: String, shape: Shape): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val visibility = LocalTabTransition.current ?: return this
    return with(shared) {
        this@tabBounds.sharedBounds(
            rememberSharedContentState("tab:$key"),
            visibility,
            boundsTransform = { _, _ -> tween(TAB_ZOOM_MILLIS) },
            // By width, from the bottom: a tile is the bottom of its page (newest items, composer), shrunk.
            resizeMode = scaleToBounds(ContentScale.FillWidth, Alignment.BottomCenter),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/**
 * The top bar stays put while the page under it zooms: the feed's bar and the overview's crossfade in place.
 * Without a transition around it (previews), it does nothing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.topBarBounds(): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val visibility = LocalTabTransition.current ?: return this
    return with(shared) {
        this@topBarBounds.sharedBounds(
            rememberSharedContentState("top-bar"),
            visibility,
            enter = fadeIn(tween(TAB_ZOOM_MILLIS)),
            exit = fadeOut(tween(TAB_ZOOM_MILLIS)),
            boundsTransform = { _, _ -> tween(TAB_ZOOM_MILLIS) },
        )
    }
}

/** A rounded square with the number of tabs in it, in the same corner on both screens. */
@Composable
fun TabsButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Box(
            Modifier.size(20.dp).border(1.8.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) ":D" else "$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
        }
    }
}
