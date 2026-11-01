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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import realm.Model

/** Height of the top bar; its title starts where the content's gutter does, plus a little. */
val TopBarHeight = 56.dp

/**
 * The one bar over the feed and the overview. It stays put while the page under it zooms: going out to
 * the overview, the feed's buttons close up and settings opens out, sliding the others aside, while the
 * tabs button holds its corner. [model] is the open feed's, or null in the overview. [update] puts a dot on
 * settings, where the server update is offered.
 */
@Composable
fun TopBar(
    title: String?,
    model: Model?,
    tabs: Int,
    onTabs: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    update: Boolean = false,
) {
    Row(modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (model?.searching == true) {
            SearchField(model)
        } else {
            BarTitle(title, Modifier.weight(1f))
            // An empty realm offers the agents in its middle instead; they move up here once it's known to have things in it.
            BarItem(model != null && model.loaded && !model.isEmpty) { ShareButton(onClick = { model?.sharing = true }) }
            BarItem(model != null) { IconButton(onClick = { model?.searching = true }) { Icon(Icons.Outlined.Search, "Search") } }
            BarItem(model == null) {
                IconButton(onClick = onSettings) {
                    BadgedBox(badge = { if (update) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                        Icon(Icons.Outlined.Settings, if (update) "Settings, server update" else "Settings")
                    }
                }
            }
            TabsButton(tabs, onTabs)
        }
    }
}

/** A button that opens out into the bar, pushing its neighbours aside, and closes up again; in step with the zoom. */
@Composable
private fun RowScope.BarItem(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(TAB_ZOOM_MILLIS)) +
            expandHorizontally(tween(TAB_ZOOM_MILLIS)), // grows: bar items, with the zoom (approved by PENDING, until 2026-11-15)
        exit = fadeOut(tween(TAB_ZOOM_MILLIS)) + shrinkHorizontally(tween(TAB_ZOOM_MILLIS)),
    ) { content() }
}

/** "realm", and in a feed "/Main" after it, sliding in and out; "realm" mutes while a name follows it. */
@Composable
private fun BarTitle(realm: String?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lead by animateColorAsState(if (realm == null) MaterialTheme.colorScheme.onSurface else muted, tween(TAB_ZOOM_MILLIS))
    Row(modifier.padding(start = PageGutter)) {
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

/** The bar while searching a feed: back, the field, and clear. */
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
