package realm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import realm.Model
import realm.SettingsMark

val TopBarHeight = 56.dp

/**
 * The bar over the feed and the overview. [model] is the open feed's, or null in the overview; [mark] shows on settings
 * what runs on or waits there, and brings settings into a feed's bar while there's something to show.
 */
@Composable
fun TopBar(
    title: String?,
    model: Model?,
    tabs: Int,
    onTabs: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    mark: SettingsMark = SettingsMark.None,
) {
    Row(modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (model?.searching == true) {
            SearchField(model)
        } else {
            BarTitle(title, Modifier.weight(1f))
            BarItem(model != null && model.loaded && !model.isEmpty) { ShareButton(onClick = { model?.sharing = true }) }
            BarItem(model != null) { IconButton(onClick = { model?.searching = true }) { Icon(Icons.Outlined.Search, "Search") } }
            BarItem(model == null || mark != SettingsMark.None) {
                SettingsButton(mark, onSettings)
            }
            TabsButton(tabs, onTabs)
        }
    }
}

@Composable
private fun RowScope.BarItem(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(TAB_ZOOM_MILLIS)) +
            expandHorizontally(tween(TAB_ZOOM_MILLIS)), // grows: bar items, with the zoom
        exit = fadeOut(tween(TAB_ZOOM_MILLIS)) + shrinkHorizontally(tween(TAB_ZOOM_MILLIS)),
    ) { content() }
}

/** The settings icon, with a spinner round it while something runs there, or a dot while something waits there. */
@Composable
fun SettingsButton(mark: SettingsMark, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            if (mark == SettingsMark.Busy) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp)
            BadgedBox(badge = { if (mark == SettingsMark.Waiting) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                Icon(
                    Icons.Outlined.Settings,
                    when (mark) {
                        SettingsMark.None -> "Settings"
                        SettingsMark.Busy -> "Settings, something is under way"
                        SettingsMark.Waiting -> "Settings, something is waiting for you"
                    },
                )
            }
        }
    }
}

@Composable
internal fun BarTitle(realm: String?, modifier: Modifier = Modifier, start: Dp = PageGutter) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lead by animateColorAsState(if (realm == null) MaterialTheme.colorScheme.onSurface else muted, tween(TAB_ZOOM_MILLIS))
    Row(modifier.padding(start = start)) {
        Text("realm", color = lead, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        AnimatedContent(
            realm,
            Modifier.weight(1f, fill = false),
            transitionSpec = {
                (fadeIn(tween(TAB_ZOOM_MILLIS)) togetherWith fadeOut(tween(TAB_ZOOM_MILLIS))).using(SizeTransform(clip = true))
            },
        ) { name ->
            if (name != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = muted)) { append("/") }
                        append(name)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SearchField(model: Model) {
    val focus = remember { FocusRequester() }
    var q by remember { mutableStateOf(TextFieldValue(model.query)) }
    IconButton(onClick = model::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
    Field(
        value = q,
        onValueChange = {
            q = it
            model.query = it.text
        },
        placeholder = "Search the dump",
        singleLine = true,
        modifier = Modifier.weight(1f).focusRequester(focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { model.refresh() }),
    )
    if (q.text.isNotEmpty()) {
        IconButton(onClick = {
            q = TextFieldValue("")
            model.query = ""
        }) { Icon(Icons.Outlined.Close, "Clear") }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
