// Buttons shared across screens.
package realm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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

/** How far a text button's label sits in from its start: its hover and press background reaches that far out. */
private val InlinePadding = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(LayoutDirection.Ltr)

/**
 * A text button in the flow of the text, its label lined up with the text above and below it. It keeps a text
 * button's padding, so hovering or pressing shows a background with room around the label, reaching out past
 * the text's edge. Use this, not contentPadding = PaddingValues(0.dp), which leaves the label touching it.
 */
@Composable
fun InlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    TextButton(onClick, modifier.offset(x = -InlinePadding)) { Text(text, color = color) }
}

/** The room an [InlineButton] takes, for a skeleton, with a bar [fraction] across where its label goes. */
@Composable
fun InlineButtonSkeleton(fraction: Float, modifier: Modifier = Modifier) {
    // A button keeps room to be touched around it, as well as its own height.
    Box(modifier.minimumInteractiveComponentSize().heightIn(min = ButtonDefaults.MinHeight), contentAlignment = Alignment.CenterStart) {
        SkeletonLine(fraction, style = MaterialTheme.typography.labelLarge)
    }
}
