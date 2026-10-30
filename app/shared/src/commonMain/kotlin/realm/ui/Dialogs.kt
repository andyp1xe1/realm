package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import realm.Link
import realm.Model
import realm.ttlLabel

private val SCOPES = listOf(
    "drop" to "Drop — can only add",
    "read" to "Read — search & download",
    "full" to "Full — read, add, edit, delete",
)

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(20.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinksDialog(model: Model, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf("drop") }
    var ttl by remember { mutableStateOf<Long?>(86_400L) }
    var label by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.loadLinks() }

    Sheet(onDismiss) {
        Text("Share links", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Anyone (or any agent) with the link gets this access, no login. Open it without a browser and it explains its own API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SCOPES.forEach { (s, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = scope == s, onClick = { scope = s }, label = { Text(desc) }, shape = CircleShape)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TTLS.forEach { (l, t) -> FilterChip(selected = ttl == t, onClick = { ttl = t }, label = { Text(l) }, shape = CircleShape) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            label, { label = it },
            placeholder = { Text("Label (optional) — who it's for") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { model.createLink(scope, ttl, label); label = "" }, modifier = Modifier.fillMaxWidth().height(FormHeight), shape = MaterialTheme.shapes.small) {
            Text("Create & copy")
        }

        if (model.links.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(model.links, key = { it.token }) { LinkRow(it, model) }
            }
        }
    }
}

@Composable
private fun LinkRow(l: Link, model: Model) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(l.scope)
                    l.label?.let { append(" · ").append(it) }
                    l.item?.let { append(" · ").append(it) }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(l.expires?.let { "expires " + model.platform.formatDay(it) + " " + model.platform.formatTime(it) } ?: "never expires")
                    append(" · ${l.uses}")
                    l.maxUses?.let { append("/$it") }
                    append(" uses")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { model.platform.copyText(l.url); model.message = "Link copied" }) { Icon(Icons.Outlined.ContentCopy, "Copy link") }
        IconButton(onClick = { model.revoke(l) }) { Icon(Icons.Outlined.Delete, "Revoke") }
    }
}

@Composable
fun SettingsDialog(model: Model, onDismiss: () -> Unit) {
    val api = model.api ?: return
    val scope = rememberCoroutineScope()
    Sheet(onDismiss) {
        Wordmark()
        Spacer(Modifier.height(16.dp))
        Text("Connected to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(api.base, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { api.help() }
                        .onSuccess { model.platform.copyText(it); model.message = "API cheat-sheet copied — paste it to an agent" }
                        .onFailure { model.message = it.message }
                }
            },
            modifier = Modifier.fillMaxWidth().height(FormHeight),
            shape = MaterialTheme.shapes.small,
        ) { Text("Copy API cheat-sheet") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tip: tell an agent to `curl` a read or full share link — the response explains how to search, download and upload.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.disconnect(); onDismiss() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
