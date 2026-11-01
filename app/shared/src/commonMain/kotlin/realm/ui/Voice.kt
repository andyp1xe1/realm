package realm.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import realm.LocalFile
import realm.Model
import realm.Platform
import realm.Recording
import realm.humanSize
import realm.readAll
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private val BarWidth = 3.dp
private val BarGap = 2.dp

/** Semi-tall recording card: a rolling waveform of mic levels, the timer, cancel and send. */
@Composable
fun RecordingPanel(rec: Recording, onCancel: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(rec) {
        val start = TimeSource.Monotonic.markNow()
        var smooth = 0f
        while (true) {
            elapsed = start.elapsedNow().inWholeSeconds
            smooth = smooth * 0.35f + rec.level() * 0.65f
            levels.add(smooth.coerceIn(0f, 1f))
            if (levels.size > 400) levels.removeRange(0, levels.size - 400)
            delay(60)
        }
    }
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    val bar = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline
    Column(modifier.padding(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
            val w = BarWidth.toPx()
            val step = w + BarGap.toPx()
            val slots = (size.width / step).toInt()
            val shown = levels.takeLast(slots)
            val mid = size.height / 2
            // Newest bar at the right edge; empty slots on the left are drawn as a quiet baseline.
            for (i in 0 until slots) {
                val level = shown.getOrNull(i - (slots - shown.size))
                val h = if (level == null) w else maxOf(w, level * size.height)
                drawRoundRect(
                    color = if (level == null) idle else bar.copy(alpha = 0.45f + 0.55f * (i.toFloat() / slots)),
                    topLeft = Offset(i * step, mid - h / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2),
                )
            }
        }
        Row(Modifier.height(ControlHeight), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel recording") }
            Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(10.dp))
            Text("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(ControlHeight), contentAlignment = Alignment.Center) {
                FilledIconButton(onClick = onSend, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send recording", Modifier.size(18.dp))
                }
            }
        }
    }
}
