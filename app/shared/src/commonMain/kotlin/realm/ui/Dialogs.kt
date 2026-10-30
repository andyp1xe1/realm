package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import realm.Model

@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(20.dp)) { content() }
        }
    }
}

@Composable
fun SettingsDialog(model: Model, onDismiss: () -> Unit) {
    val api = model.api ?: return
    Sheet(onDismiss) {
        Wordmark()
        Spacer(Modifier.height(16.dp))
        Text("Connected to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(api.base, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.disconnect(); onDismiss() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
