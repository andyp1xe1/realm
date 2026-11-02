package realm.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import realm.Item
import realm.Model

@Composable
internal fun ItemMenu(item: Item, model: Model, open: Boolean, onDismiss: () -> Unit, onView: () -> Unit) {
    var linkStep by remember(open) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    DropdownMenu(open, onDismiss, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        fun act(f: () -> Unit) = {
            f()
            onDismiss()
        }
        val isFile = item.name != null
        if (linkStep) {
            Ttl.entries.forEach { ttl ->
                DropdownMenuItem(text = { Text(ttl.label) }, onClick = act { model.shareLink(item, ttl.seconds) })
            }
        } else {
            if (isFile) {
                DropdownMenuItem(text = { Text("Open") }, onClick = act { model.open(item) })
                DropdownMenuItem(text = { Text("Save") }, onClick = act { model.save(item) })
            }
            val text = item.text
            if (!text.isNullOrEmpty()) {
                DropdownMenuItem(text = { Text(if (isFile) "Copy caption" else "Copy") }, onClick = act { model.copy(item) })
                if (!isFile && (text.length > 800 || text.count { it == '\n' } > 20)) {
                    DropdownMenuItem(text = { Text("View all") }, onClick = act(onView))
                }
            }
            DropdownMenuItem(text = { Text(if (isFile) "Edit caption" else "Edit") }, onClick = act { model.editing = item })
            if (model.isOwner) DropdownMenuItem(text = { Text("Share link…") }, onClick = { linkStep = true })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = act { deleting = true },
            )
        }
    }
    if (deleting) {
        Confirm(
            item.name?.let { "Delete “$it”?" } ?: if (item.kind == "link") "Delete this link?" else "Delete this note?",
            "It's gone for good, and links that share only it stop working.",
            "Delete",
            onDismiss = { deleting = false },
            onConfirm = { model.delete(item) },
        )
    }
}

/** How long a share link works; [seconds] is null for ever. */
enum class Ttl(val label: String, val seconds: Long?) {
    Hour("1 hour", 3600L),
    Day("1 day", 86_400L),
    Week("7 days", 604_800L),
    Month("30 days", 2_592_000L),
    Forever("Forever", null),
}
