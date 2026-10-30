package realm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import realm.ui.Accent
import realm.ui.App
import java.awt.Dimension

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun main() = application {
    val platform = remember { DesktopPlatform() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "realm",
        icon = DotIcon,
        state = rememberWindowState(width = 880.dp, height = 820.dp),
    ) {
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            platform.window = window
            window.minimumSize = Dimension(360, 480)
        }
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    dragging = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    dragging = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    dragging = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dragging = false
                    return platform.drop(event.awtTransferable)
                }
            }
        }
        Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
            App(platform)
            if (dragging) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xCC0E0E11)).padding(16.dp)
                        .border(2.dp, Accent, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Drop to dump", color = Accent, fontSize = 22.sp)
                }
            }
        }
    }
}

private object DotIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF0E0E11))
        drawCircle(Accent, radius = size.minDimension * 0.2f)
    }
}
