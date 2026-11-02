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

private val InlinePadding = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(LayoutDirection.Ltr)

/** A text button whose label lines up with the text around it, its hover background reaching past it. */
@Composable
fun InlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    TextButton(onClick, modifier.offset(x = -InlinePadding)) { Text(text, color = color) }
}

/** The room an [InlineButton] takes, with a bar [fraction] across for its label. */
@Composable
fun InlineButtonSkeleton(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.minimumInteractiveComponentSize().heightIn(min = ButtonDefaults.MinHeight), contentAlignment = Alignment.CenterStart) {
        SkeletonLine(fraction, style = MaterialTheme.typography.labelLarge)
    }
}
