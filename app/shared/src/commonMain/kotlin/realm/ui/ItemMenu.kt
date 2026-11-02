// What can be done to an item in the feed, from its menu.
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

/** An item's menu: open, save, copy, edit, share a link to it, delete; [onView] shows a long note whole. */
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
            TTLS.forEach { (label, ttl) ->
                DropdownMenuItem(text = { Text(label) }, onClick = act { model.shareLink(item, ttl) })
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

val TTLS = listOf("1 hour" to 3600L, "1 day" to 86_400L, "7 days" to 604_800L, "30 days" to 2_592_000L, "Forever" to null)
