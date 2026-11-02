package realm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import realm.Home
import realm.isLink as isLinkUrl

@Composable
internal fun AddSheet(home: Home, onClose: () -> Unit) {
    Column {
        if (home.server == null) {
            Text("Add a share link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "It shows up here next to your other realms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SignInForm(home, linksOnly = true, onDone = onClose)
            return
        }
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val isLink = isLinkUrl(text)

        fun go() {
            val t = text.trim()
            if (t.isEmpty() || busy) return
            if (!isLink) {
                home.createRealm(t)
                return onClose()
            }
            busy = true
            scope.launch {
                error = home.connect(t)
                busy = false
                if (error == null) onClose()
            }
        }

        Text("New realm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Name it, or paste a share link someone sent you to keep it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            text,
            {
                text = it
                error = null
            },
            placeholder = { Text("Name or share link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Button(onClick = ::go, enabled = text.isNotBlank() && !busy, shape = MaterialTheme.shapes.small) {
                Text(if (isLink) (if (busy) "Connecting…" else "Connect") else "Create")
            }
        }
    }
}

@Composable
internal fun NameSheet(title: String, initial: String, confirm: String, onClose: () -> Unit, onName: (String) -> Unit) {
    Column {
        var text by remember { mutableStateOf(initial) }
        fun go() {
            if (text.isBlank()) return
            onName(text.trim())
            onClose()
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            text,
            { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { go() }),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Button(onClick = ::go, enabled = text.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(confirm) }
        }
    }
}
