package realm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Height of every single-line control in the app: icon buttons, fields, the composer row. */
val ControlHeight = 48.dp

/**
 * A bare text field: no container, no indicator, text vertically centred in [ControlHeight] so it lines up
 * with 48dp icon buttons beside it. Grows downward for multi-line text.
 */
@Composable
fun Field(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 10,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    alignTop: Boolean = false,
    onLines: (Int) -> Unit = {},
) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = ControlHeight),
        textStyle = style,
        singleLine = singleLine,
        maxLines = maxLines,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        onTextLayout = { onLines(it.lineCount) },
        decorationBox = { inner ->
            // (48dp − 22sp line height) / 2 ≈ 13dp keeps one line dead centre.
            Box(
                Modifier.heightIn(min = ControlHeight).padding(horizontal = 4.dp, vertical = 13.dp),
                contentAlignment = if (alignTop) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                inner()
            }
        },
    )
}

