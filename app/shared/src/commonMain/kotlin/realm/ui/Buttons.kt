package realm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val InlinePadding = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(LayoutDirection.Ltr)

/** A text button whose label lines up with the text around it, its hover background reaching past it. */
@Composable
fun InlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    TextButton(onClick, modifier.offset(x = -InlinePadding)) { Text(text, color = color) }
}

/** A text button at a row's end, its label ending where the row does, as an [InlineButton]'s starts where its row does. */
@Composable
fun EndButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    TextButton(onClick, modifier.offset(x = InlinePadding).widthIn(min = 1.dp)) {
        Text(text, color = color, maxLines = 1, softWrap = false)
    }
}

/** The room an [InlineButton] takes, with a bar [fraction] across for its label. */
@Composable
fun InlineButtonSkeleton(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.minimumInteractiveComponentSize().heightIn(min = ButtonDefaults.MinHeight), contentAlignment = Alignment.CenterStart) {
        SkeletonLine(fraction, style = MaterialTheme.typography.labelLarge)
    }
}
