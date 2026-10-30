package realm.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.style.TextOverflow
import realm.Link
import realm.Api
import realm.Model

// Brand marks from @lobehub/icons (static-svg), 24x24 viewBox. Claude keeps its colour; the rest follow the theme.
private fun brand(path: String, color: Color = Color.Black) = brand(path to color)

private fun brand(vararg paths: Pair<String, Color>) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .apply { paths.forEach { (d, c) -> addPath(addPathNodes(d), fill = SolidColor(c), pathFillType = PathFillType.EvenOdd) } }
    .build()

private val Claude = brand("M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z", Color(0xFFD97757))
private val OpenAI = brand("M9.205 8.658v-2.26c0-.19.072-.333.238-.428l4.543-2.616c.619-.357 1.356-.523 2.117-.523 2.854 0 4.662 2.212 4.662 4.566 0 .167 0 .357-.024.547l-4.71-2.759a.797.797 0 00-.856 0l-5.97 3.473zm10.609 8.8V12.06c0-.333-.143-.57-.429-.737l-5.97-3.473 1.95-1.118a.433.433 0 01.476 0l4.543 2.617c1.309.76 2.189 2.378 2.189 3.948 0 1.808-1.07 3.473-2.76 4.163zM7.802 12.703l-1.95-1.142c-.167-.095-.239-.238-.239-.428V5.899c0-2.545 1.95-4.472 4.591-4.472 1 0 1.927.333 2.712.928L8.23 5.067c-.285.166-.428.404-.428.737v6.898zM12 15.128l-2.795-1.57v-3.33L12 8.658l2.795 1.57v3.33L12 15.128zm1.796 7.23c-1 0-1.927-.332-2.712-.927l4.686-2.712c.285-.166.428-.404.428-.737v-6.898l1.974 1.142c.167.095.238.238.238.428v5.233c0 2.545-1.974 4.472-4.614 4.472zm-5.637-5.303l-4.544-2.617c-1.308-.761-2.188-2.378-2.188-3.948A4.482 4.482 0 014.21 6.327v5.423c0 .333.143.571.428.738l5.947 3.449-1.95 1.118a.432.432 0 01-.476 0zm-.262 3.9c-2.688 0-4.662-2.021-4.662-4.519 0-.19.024-.38.047-.57l4.686 2.71c.286.167.571.167.856 0l5.97-3.448v2.26c0 .19-.07.333-.237.428l-4.543 2.616c-.619.357-1.356.523-2.117.523zm5.899 2.83a5.947 5.947 0 005.827-4.756C22.287 18.339 24 15.84 24 13.296c0-1.665-.713-3.282-1.998-4.448.119-.5.19-.999.19-1.498 0-3.401-2.759-5.947-5.946-5.947-.642 0-1.26.095-1.88.31A5.962 5.962 0 0010.205 0a5.947 5.947 0 00-5.827 4.757C1.713 5.447 0 7.945 0 10.49c0 1.666.713 3.283 1.998 4.448-.119.5-.19 1-.19 1.499 0 3.401 2.759 5.946 5.946 5.946.642 0 1.26-.095 1.88-.309a5.96 5.96 0 004.162 1.713z")
private val OpenCode = brand("M16 6H8v12h8V6zm4 16H4V2h16v20z")
private val Cursor = brand("M22.106 5.68L12.5.135a.998.998 0 00-.998 0L1.893 5.68a.84.84 0 00-.419.726v11.186c0 .3.16.577.42.727l9.607 5.547a.999.999 0 00.998 0l9.608-5.547a.84.84 0 00.42-.727V6.407a.84.84 0 00-.42-.726zm-.603 1.176L12.228 22.92c-.063.108-.228.064-.228-.061V12.34a.59.59 0 00-.295-.51l-9.11-5.26c-.107-.062-.063-.228.062-.228h18.55c.264 0 .428.286.296.514z")
// Not in the stack yet.
private val Cloudflare = brand("M16.493 17.4c.135-.52.08-.983-.161-1.338-.215-.328-.592-.519-1.05-.519l-8.663-.109a.148.148 0 01-.135-.082c-.027-.054-.027-.109-.027-.163.027-.082.108-.164.189-.164l8.744-.11c1.05-.054 2.153-.9 2.556-1.937l.511-1.31c.027-.055.027-.11.027-.164C17.92 8.91 15.66 7 12.942 7c-2.503 0-4.628 1.638-5.381 3.903a2.432 2.432 0 00-1.803-.491c-1.21.109-2.153 1.092-2.287 2.32-.027.328 0 .628.054.9C1.56 13.688 0 15.326 0 17.319c0 .19.027.355.027.545 0 .082.08.137.161.137h15.983c.08 0 .188-.055.215-.164l.107-.437" to Color(0xFFF38020), "M19.238 11.75h-.242c-.054 0-.108.054-.135.109l-.35 1.2c-.134.52-.08.983.162 1.338.215.328.592.518 1.05.518l1.855.11c.054 0 .108.027.135.082.027.054.027.109.027.163-.027.082-.108.164-.188.164l-1.91.11c-1.05.054-2.153.9-2.557 1.937l-.134.355c-.027.055.026.137.107.137h6.592c.081 0 .162-.055.162-.137.107-.41.188-.846.188-1.31-.027-2.62-2.153-4.777-4.762-4.777" to Color(0xFFFCAD32))
private val GitHub = brand("M12 0c6.63 0 12 5.276 12 11.79-.001 5.067-3.29 9.567-8.175 11.187-.6.118-.825-.25-.825-.56 0-.398.015-1.665.015-3.242 0-1.105-.375-1.813-.81-2.181 2.67-.295 5.475-1.297 5.475-5.822 0-1.297-.465-2.344-1.23-3.169.12-.295.54-1.503-.12-3.125 0 0-1.005-.324-3.3 1.209a11.32 11.32 0 00-3-.398c-1.02 0-2.04.133-3 .398-2.295-1.518-3.3-1.209-3.3-1.209-.66 1.622-.24 2.83-.12 3.125-.765.825-1.23 1.887-1.23 3.169 0 4.51 2.79 5.527 5.46 5.822-.345.294-.66.81-.765 1.577-.69.31-2.415.81-3.495-.973-.225-.354-.9-1.223-1.845-1.209-1.005.015-.405.56.015.781.51.28 1.095 1.327 1.23 1.666.24.663 1.02 1.93 4.035 1.385 0 .988.015 1.916.015 2.196 0 .31-.225.664-.825.56C3.303 21.374-.003 16.867 0 11.791 0 5.276 5.37 0 12 0z")

/** Icon to whether it keeps its own colour. */
private val AgentIcons = listOf(Claude to true, OpenAI to false, OpenCode to false, Cursor to false)

/** A pill of stacked agent badges that fan out on hover (or press, on touch). [expanded] pins them open, for previews. */
@Composable
fun ShareButton(expanded: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val badge = 24.dp
    val step by animateDpAsState(if (expanded || hovered || pressed) badge + 4.dp else badge - 10.dp)

    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .width(badge + step * (AgentIcons.size - 1))
            .height(badge),
    ) {
        AgentIcons.forEachIndexed { i, (icon, branded) ->
            Box(
                Modifier
                    .zIndex((AgentIcons.size - i).toFloat())
                    .offset(x = step * i)
                    .size(badge)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(14.dp), tint = if (branded) Color.Unspecified else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val SCOPES = listOf(
    "read" to "Search and download.",
    "drop" to "Add only.",
    "full" to "Read, add, edit and delete.",
)

private enum class Format(val label: String) { Link("Link"), Prompt("Prompt"), Docs("Prompt + docs") }

/**
 * Create a share link, or pick an active one, and copy it as a bare link or as a prompt for an agent.
 * Connected through a share link, there's nothing to create: that link is handed on as is.
 */
@Composable
fun ShareDialog(model: Model, title: String, onDismiss: () -> Unit) = Sheet(onDismiss) { ShareSheet(model, onDismiss, title = title) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(model: Model, onClose: () -> Unit, initialUrl: String? = null, title: String? = null) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf("read") }
    var ttl by remember { mutableStateOf<Long?>(86_400L) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(if (model.isOwner) initialUrl else api.base) }
    var format by remember { mutableStateOf(Format.Prompt) }
    val docs = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    if (model.isOwner) LaunchedEffect(Unit) { model.loadLinks() }

    val link = url
    val text = when {
        link == null -> null
        format == Format.Link -> link
        format == Format.Prompt -> "Fetch $link with curl and follow the instructions."
        else -> docs[link]
    }

    // The link documents itself; fetching that is what "Prompt + docs" copies.
    LaunchedEffect(link, format) {
        if (link == null || format != Format.Docs || link in docs) return@LaunchedEffect
        val a = Api(link, null, model.platform.name)
        try {
            docs[link] = a.help()
        } catch (e: Exception) {
            model.message = e.message
            format = Format.Prompt
        } finally {
            a.close()
        }
    }

    Column {
        Text(title?.let { "Share “$it”" } ?: "Share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Anyone with the link gets this access to ${title?.let { "“$it”" } ?: "this realm"}, and nothing else. Agents can curl it for instructions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (link == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SCOPES.forEach { (s, _) ->
                    FilterChip(selected = access == s, onClick = { access = s }, label = { Text(s.replaceFirstChar { it.uppercase() }) }, shape = CircleShape)
                }
            }
            Text(
                SCOPES.first { it.first == access }.second,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TTLS.forEach { (l, t) -> FilterChip(selected = ttl == t, onClick = { ttl = t }, label = { Text(l) }, shape = CircleShape) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                label, { label = it },
                placeholder = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Format.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) }, shape = CircleShape)
                }
            }
            Spacer(Modifier.height(8.dp))
            CopyBox(text) { model.platform.copyText(it) }
        }

        if (model.isOwner && model.links.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(model.links, key = { it.token }) { l -> LinkRow(l, model, selected = l.url == link) { url = l.url } }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (link != null && model.isOwner) TextButton(onClick = { url = null }) { Text("New link") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(if (link == null) "Cancel" else "Close") }
            if (link == null) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            model.createLink(access, ttl, label)?.let { url = it.url; label = "" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                ) { Text(if (busy) "Creating…" else "Create") }
            }
        }
    }
}

/** Monospace text with a copy button in its corner; the button turns into a check for a moment after copying. */
@Composable
private fun CopyBox(text: String?, onCopy: (String) -> Unit) {
    var copied by remember(text) { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1500); copied = false }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Box {
            SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 48.dp)) {
                Text(text ?: "Loading…", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            IconButton(
                onClick = { text?.let { onCopy(it); copied = true } },
                enabled = text != null,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            ) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LinkRow(l: Link, model: Model, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildString {
                    append(l.scope)
                    l.label?.let { append(" · ").append(it) }
                    l.items?.let { append(" · ").append(if (it.size == 1) "1 item" else "${it.size} items") }
                },
                style = MaterialTheme.typography.bodyMedium,
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
        IconButton(onClick = { model.revoke(l) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Delete, "Revoke", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
