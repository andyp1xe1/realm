package realm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF9D8CFF)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0E0E11),
    primaryContainer = Color(0xFF2A2547),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondaryContainer = Color(0xFF24242C),
    onSecondaryContainer = Color(0xFFE7E7EA),
    background = Color(0xFF0E0E11),
    onBackground = Color(0xFFE7E7EA),
    surface = Color(0xFF0E0E11),
    onSurface = Color(0xFFE7E7EA),
    surfaceVariant = Color(0xFF18181D),
    onSurfaceVariant = Color(0xFF8A8A96),
    surfaceContainer = Color(0xFF18181D),
    surfaceContainerHigh = Color(0xFF1F1F26),
    surfaceContainerHighest = Color(0xFF26262E),
    outline = Color(0xFF2A2A33),
    outlineVariant = Color(0xFF1F1F26),
    error = Color(0xFFFF7A85),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B48E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E4FF),
    onPrimaryContainer = Color(0xFF1E1650),
    secondaryContainer = Color(0xFFEDEDF1),
    background = Color(0xFFFAFAFB),
    surface = Color(0xFFFAFAFB),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF6B6B76),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFEAEAEE),
    surfaceContainerHighest = Color(0xFFE2E2E8),
    outline = Color(0xFFDADAE0),
    outlineVariant = Color(0xFFEAEAEE),
)

@Composable
fun RealmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
