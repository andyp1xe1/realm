package realm.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import kotlin.time.TimeSource

/** Compact: one row under the feed. Expanded: covers the feed for writing; opened by hand or by long text. */
private enum class Mode { Compact, Auto, Manual }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Composer(model: Model, modifier: Modifier = Modifier, onCompactHeight: (Dp) -> Unit = {}) {
    val platform = model.platform
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var mode by remember { mutableStateOf(Mode.Compact) }
    var lines by remember { mutableIntStateOf(1) }
    val expanded = mode != Mode.Compact && recording == null
    val desktop = platform.name == "desktop"
    val editing = model.editing
    val staged = model.staged

    LaunchedEffect(editing) {
        if (editing != null) {
            val t = editing.text.orEmpty()
            value = TextFieldValue(t, TextRange(t.length))
            focus.requestFocus()
        }
    }
    LaunchedEffect(Unit) { if (desktop) focus.requestFocus() }

    // Shared/dropped text is appended to whatever is being typed, never sent on its own.
    LaunchedEffect(model.incomingText) {
        val t = model.incomingText ?: return@LaunchedEffect
        model.incomingText = null
        val joined = if (value.text.isBlank()) t else value.text.trimEnd() + "\n" + t
        value = TextFieldValue(joined, TextRange(joined.length))
        focus.requestFocus()
    }

    // Long text opens the editor by itself, and closes it again if it shrinks back. A manual open stays open.
    // The expanded field is wider, so it only collapses at ≤2 lines to avoid flapping at the threshold.
    LaunchedEffect(lines, value.text.isEmpty()) {
        when {
            mode == Mode.Compact && lines >= 4 -> mode = Mode.Auto
            mode == Mode.Auto && (value.text.isEmpty() || lines <= 2) -> mode = Mode.Compact
        }
    }

    fun collapse() {
        mode = Mode.Compact
    }

    fun send() {
        if (editing == null && value.text.isBlank() && staged.isEmpty()) return
        model.send(value.text)
        value = TextFieldValue("")
        collapse()
    }

    fun stage(files: List<LocalFile>) {
        if (files.isEmpty()) return
        model.stage(files)
        focus.requestFocus()
    }

    fun cancelEdit() {
        model.editing = null
        value = TextFieldValue("")
        collapse()
    }

    fun record() {
        collapse()
        scope.launch { recording = platform.startRecording() ?: run { model.message = "Can't use the microphone"; null } }
    }

    BackHandler(enabled = expanded) { collapse() }

    val canSend = editing != null || value.text.isNotBlank() || staged.isNotEmpty()
    val canAttach = editing == null

    @Composable
    fun AttachButtons() {
        if (!canAttach) return
        IconButton(onClick = { scope.launch { stage(platform.pickFiles()) } }) {
            Icon(Icons.Outlined.AttachFile, "Attach files")
        }
        if (platform.canTakePhoto) {
            IconButton(onClick = { scope.launch { stage(listOfNotNull(platform.takePhoto())) } }) {
                Icon(Icons.Outlined.PhotoCamera, "Take photo")
            }
        }
    }

    @Composable
    fun SendOrMic() {
        if (!canSend) {
            IconButton(onClick = ::record) { Icon(Icons.Outlined.Mic, "Record voice") }
        } else {
            Box(Modifier.size(ControlHeight), contentAlignment = Alignment.Center) {
                FilledIconButton(onClick = ::send, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(18.dp))
                }
            }
        }
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .animateContentSize()
            .then(
                if (expanded) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().onSizeChanged { onCompactHeight(with(density) { it.height.toDp() }) },
            )
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
    ) {
        if (editing != null && !expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                EditingLabel(editing.name, Modifier.weight(1f))
                IconButton(onClick = ::cancelEdit, Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Cancel edit", Modifier.size(16.dp))
                }
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = if (expanded) Modifier.weight(1f).fillMaxWidth() else Modifier,
        ) {
            val rec = recording
            if (rec != null) {
                RecordingPanel(
                    rec,
                    onCancel = { rec.cancel(); recording = null },
                    onSend = {
                        recording = null
                        scope.launch { rec.stop()?.let { model.sendFiles(listOf(it)) } }
                    },
                )
            } else Column(Modifier.padding(4.dp)) {
                // Compact: attachments sit above the text, like a message being composed.
                if (!expanded && staged.isNotEmpty()) Tray(model)
                // One Row and one Field in both modes, so focus, cursor and the soft keyboard survive the switch.
                Row(
                    if (expanded) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (!expanded) AttachButtons()
                    Field(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = when {
                            staged.isNotEmpty() -> "Add a caption…"
                            expanded -> "Write something…"
                            else -> "Dump something…"
                        },
                        maxLines = if (expanded) Int.MAX_VALUE else 10,
                        alignTop = expanded,
                        onLines = { lines = it },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (expanded) Modifier.fillMaxHeight().padding(horizontal = 8.dp) else Modifier)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val mod = e.isCtrlPressed || e.isMetaPressed
                                when {
                                    // Ctrl+Enter always sends. Plain Enter sends from the compact desktop composer;
                                    // in the expanded editor it's a newline.
                                    e.key == Key.Enter && (mod || (desktop && !expanded && !e.isShiftPressed)) -> { send(); true }
                                    e.key == Key.Escape && expanded -> { collapse(); true }
                                    e.key == Key.Escape && editing != null -> { cancelEdit(); true }
                                    e.key == Key.V && mod && canAttach -> {
                                        val files = platform.clipboardFiles()
                                        if (files.isNotEmpty()) { stage(files); true } else false
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (expanded) {
                        // Top-right corner, level with the first line of text: no header row eating space.
                        IconButton(onClick = ::collapse, Modifier.align(Alignment.Top)) {
                            Icon(Icons.Outlined.FullscreenExit, "Shrink composer")
                        }
                    } else {
                        IconButton(onClick = { mode = Mode.Manual; focus.requestFocus() }) {
                            Icon(Icons.Outlined.Fullscreen, "Expand composer")
                        }
                        SendOrMic()
                    }
                }
                // Expanded: attachments sit with the actions, under the writing area.
                if (expanded && staged.isNotEmpty()) Tray(model)
                if (expanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AttachButtons()
                        // Editing can't attach, so its label and cancel take the attach buttons' spot.
                        if (editing != null) {
                            IconButton(onClick = ::cancelEdit) { Icon(Icons.Outlined.Close, "Cancel edit") }
                            EditingLabel(editing.name, Modifier.weight(1f, fill = false))
                        }
                        Spacer(Modifier.weight(1f))
                        if (desktop) {
                            Text(
                                "Ctrl+Enter to send",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        SendOrMic()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditingLabel(fileName: String?, modifier: Modifier = Modifier) {
    Text(
        "Editing" + (fileName?.let { " caption of $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ---------- staged attachments ----------

private val TileSize = 64.dp

/** Files waiting to be sent: thumbnails for images, type tiles otherwise, each removable. */
@Composable
private fun Tray(model: Model) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    ) {
        items(model.staged, key = { it.hashCode() }) { f -> StagedTile(f, model.platform) { model.staged.remove(f) } }
    }
}

@Composable
private fun StagedTile(file: LocalFile, platform: Platform, onRemove: () -> Unit) {
    val isImage = file.mime?.startsWith("image/") == true
    val thumb by produceState<ImageBitmap?>(null, file) {
        if (isImage && file.size in 1..Model.MAX_INLINE_IMAGE) {
            value = withContext(Dispatchers.IO) {
                runCatching { file.reader().use { it.readAll(file.size.toInt()) } }.getOrNull()?.let { platform.decodeImage(it, 256) }
            }
        }
    }
    Column(Modifier.width(TileSize)) {
        Box(Modifier.size(TileSize)) {
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(bmp, file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    val kind = file.mime?.substringBefore('/')?.takeIf { it in setOf("image", "audio", "video") } ?: "file"
                    KindIcon(kind, file.mime)
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, "Remove ${file.name}", Modifier.size(13.dp), tint = Color.White)
            }
        }
        Text(
            file.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            humanSize(file.size.takeIf { it >= 0 }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---------- voice ----------

private val BarWidth = 3.dp
private val BarGap = 2.dp

/** Semi-tall recording card: a rolling waveform of mic levels, the timer, cancel and send. */
@Composable
fun RecordingPanel(rec: Recording, onCancel: () -> Unit, onSend: () -> Unit) {
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
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    val bar = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline
    Column(Modifier.padding(4.dp)) {
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
