package realm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A realm with nothing in it yet: ghosts of what a feed holds (a note, a file, a picture, a voice note)
 * drifting gently, one short line, and the agents to share it with, which leave the header meanwhile.
 * Only for a realm known to be empty, never while loading. [still] skips the drift, for tile pictures.
 */
@Composable
fun EmptyRealm(modifier: Modifier = Modifier, still: Boolean = false, onShare: () -> Unit = {}) {
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(400)) }
    val drift = if (still) 0f else {
        // Linear: with easing, each cycle would slow to a stop before the next, reading as an end.
        val t by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart))
        t
    }
    Column(modifier.graphicsLayer { alpha = appear.value }, horizontalAlignment = Alignment.CenterHorizontally) {
        GhostFeed(drift)
        Spacer(Modifier.height(20.dp))
        Text(
            "Dump anything, or let an agent in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        ShareButton(expanded = true, onClick = onShare)
    }
}

@Composable
private fun GhostFeed(drift: Float) {
    val card = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val tile = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(240.dp, 200.dp)) {
        val u = size.width / 240f // design units: the art is drawn on a 240×200 grid
        // Each card bobs a few units, a quarter turn apart from the next.
        fun bob(phase: Float) = sin((drift + phase) * 2 * PI).toFloat() * 3f * u

        // A note: two lines of text.
        translate(0f, bob(0f)) {
            card(16f, 8f, 132f, 40f, u, card)
            bar(30f, 20f, 92f, u, line)
            bar(30f, 31f, 60f, u, line)
        }
        // A file: type tile and name.
        translate(0f, bob(0.25f)) {
            card(16f, 58f, 168f, 46f, u, card)
            drawRoundRect(tile, Offset(24f * u, 64f * u), Size(34f * u, 34f * u), CornerRadius(8f * u))
            drawRoundRect(accent.copy(alpha = 0.7f), Offset(35f * u, 73f * u), Size(12f * u, 16f * u), CornerRadius(2f * u), style = Stroke(2f * u))
            bar(68f, 72f, 84f, u, line)
            bar(68f, 84f, 44f, u, line)
        }
        // A picture: hills and a sun.
        translate(0f, bob(0.5f)) {
            card(16f, 114f, 112f, 76f, u, card)
            val hills = Path().apply {
                moveTo(16f * u, 176f * u)
                lineTo(50f * u, 146f * u)
                lineTo(72f * u, 164f * u)
                lineTo(90f * u, 150f * u)
                lineTo(128f * u, 182f * u)
                lineTo(128f * u, 190f * u)
                lineTo(16f * u, 190f * u)
                close()
            }
            val frame = Path().apply {
                addRoundRect(RoundRect(16f * u, 114f * u, 128f * u, 190f * u, CornerRadius(14f * u)))
            }
            clipPath(frame) { drawPath(hills, line) }
            drawCircle(accent.copy(alpha = 0.55f), 8f * u, Offset(104f * u, 134f * u))
        }
        // A voice note: a short waveform.
        translate(0f, bob(0.75f)) {
            card(140f, 124f, 84f, 40f, u, card)
            val heights = listOf(6f, 14f, 22f, 12f, 18f, 8f, 16f, 10f, 5f)
            heights.forEachIndexed { i, h ->
                drawRoundRect(
                    if (i < 4) accent.copy(alpha = 0.6f) else line,
                    Offset((154f + i * 7f) * u, (144f - h / 2) * u),
                    Size(3f * u, h * u),
                    CornerRadius(1.5f * u),
                )
            }
        }
    }
}

/**
 * For the sign-in screen: realms as tiles fanned out like tabs under the Cloudflare cloud they run on, the
 * front one outlined, with a note and a voice note drifting in towards it. Same ghosted style as [EmptyRealm].
 */
@Composable
fun WelcomeArt(modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(500)) }
    val drift by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart))
    val card = MaterialTheme.colorScheme.surfaceContainer
    val back = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val tile = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.primary
    val cloud = rememberVectorPainter(Cloudflare)
    Canvas(modifier.size(260.dp, 180.dp).graphicsLayer { alpha = appear.value }) {
        val u = size.width / 260f // design units: drawn on a 260×180 grid
        fun bob(phase: Float, amp: Float = 3f) = sin((drift + phase) * 2 * PI).toFloat() * amp * u

        // The cloud they live in, behind them all, in Cloudflare's own colours.
        translate(170f * u, -10f * u + bob(0.85f, 2f)) {
            with(cloud) { draw(Size(72f * u, 72f * u)) }
        }

        // Two realms behind, tilted out like tabs in an overview.
        for ((x, turn, phase) in listOf(Triple(28f, -9f, 0.1f), Triple(122f, 9f, 0.6f))) {
            translate(0f, bob(phase, 2f)) {
                rotate(turn, Offset((x + 55f) * u, 95f * u)) {
                    card(x, 40f, 110f, 110f, u, back)
                    bar(x + 14f, 112f, 60f, u, line)
                    bar(x + 14f, 124f, 40f, u, line)
                }
            }
        }
        // The front realm: outlined, a feed inside.
        translate(0f, bob(0.35f, 2f)) {
            card(75f, 22f, 110f, 136f, u, card)
            drawRoundRect(accent, Offset(75f * u, 22f * u), Size(110f * u, 136f * u), CornerRadius(14f * u), style = Stroke(2f * u))
            bar(89f, 38f, 64f, u, line)
            bar(89f, 50f, 44f, u, line)
            drawRoundRect(tile, Offset(89f * u, 66f * u), Size(26f * u, 26f * u), CornerRadius(7f * u))
            drawRoundRect(accent.copy(alpha = 0.7f), Offset(97f * u, 73f * u), Size(10f * u, 12f * u), CornerRadius(2f * u), style = Stroke(1.6f * u))
            bar(122f, 72f, 48f, u, line)
            bar(122f, 82f, 30f, u, line)
            val hills = Path().apply {
                moveTo(89f * u, 146f * u)
                lineTo(110f * u, 124f * u)
                lineTo(124f * u, 136f * u)
                lineTo(136f * u, 126f * u)
                lineTo(171f * u, 146f * u)
                close()
            }
            drawRoundRect(back, Offset(89f * u, 102f * u), Size(82f * u, 44f * u), CornerRadius(8f * u))
            clipPath(Path().apply { addRoundRect(RoundRect(89f * u, 102f * u, 171f * u, 146f * u, CornerRadius(8f * u))) }) { drawPath(hills, line) }
            drawCircle(accent.copy(alpha = 0.55f), 5f * u, Offset(158f * u, 113f * u))
        }
        // Things on their way in.
        translate(bob(0.0f, 2f), bob(0.2f, 4f)) {
            card(6f, 14f, 58f, 22f, u, card)
            bar(14f, 22f, 34f, u, line)
        }
        translate(bob(0.5f, 2f), bob(0.7f, 4f)) {
            card(196f, 138f, 58f, 26f, u, card)
            listOf(5f, 11f, 16f, 9f, 13f, 6f).forEachIndexed { i, h ->
                drawRoundRect(
                    if (i < 3) accent.copy(alpha = 0.6f) else line,
                    Offset((208f + i * 6f) * u, (151f - h / 2) * u),
                    Size(2.6f * u, h * u),
                    CornerRadius(1.3f * u),
                )
            }
        }
    }
}

private fun DrawScope.card(x: Float, y: Float, w: Float, h: Float, u: Float, color: Color) =
    drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(14f * u))

private fun DrawScope.bar(x: Float, y: Float, w: Float, u: Float, color: Color) =
    drawRoundRect(color, Offset(x * u, y * u), Size(w * u, 6f * u), CornerRadius(3f * u))
