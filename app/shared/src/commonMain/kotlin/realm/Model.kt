package realm

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class Filter(val label: String, val kinds: List<String>) {
    All("All", emptyList()),
    Notes("Notes", listOf("note")),
    Links("Links", listOf("link")),
    Images("Images", listOf("image")),
    Media("Audio & video", listOf("audio", "video")),
    Files("Files", listOf("file")),
}

/** Something on its way up: a note or a file. Shown at the bottom of the feed until it lands. */
class Pending(val key: Int, val text: String?, val file: LocalFile?, val caption: String?) {
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
}

/**
 * One realm's feed, as one tile of the [Home] overview sees it through [api]. Without [loadImages],
 * images only download when asked to, for links where every download spends a use.
 */
class Model(val platform: Platform, private val scope: CoroutineScope, val api: Api, private val loadImages: Boolean = true) {
    val items = mutableStateListOf<Item>() // newest first

    /** The feed's scroll position, kept here so it survives a trip through the tab overview. */
    val listState = LazyListState()
    val pending = mutableStateListOf<Pending>()

    /** Files waiting in the composer for a caption and a tap on send. */
    val staged = mutableStateListOf<LocalFile>()

    /** Text from outside (share sheet, drag & drop) for the composer to pick up. */
    var incomingText by mutableStateOf<String?>(null)
    val links = mutableStateListOf<Link>()

    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.All)
    var loading by mutableStateOf(false)
        private set

    /** The server has answered for the first page; until then (or if it can't be reached) an empty feed isn't known to be empty. */
    var loaded by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf<Item?>(null)

    /** The search field is open in the top bar; like a browser's find bar, it stays open through the overview. */
    var searching by mutableStateOf(false)

    /** The share sheet is up. */
    var sharing by mutableStateOf(false)

    /** False when connected through a drop-only share link. */
    var canRead by mutableStateOf(true)
        private set

    val filtering get() = query.isNotBlank() || filter != Filter.All

    fun closeSearch() {
        searching = false
        query = ""
        filter = Filter.All
    }

    /** A realm just made: known to be empty without asking, so it opens straight onto its empty state. */
    fun startEmpty() {
        loaded = true
    }

    /** Known to have nothing in it yet (not loading, not a search, not a drop-only link): the empty state shows. */
    val isEmpty get() = loaded && canRead && !filtering && items.isEmpty() && pending.isEmpty()

    private var next: String? = null
    private var listJob: Job? = null
    private val uploads = Semaphore(2)
    private var pendingKeys = 0

    /** Rows the feed has shown once; only new ones fade in. */
    private val shown = HashSet<String>()

    /** True the first time the feed shows the row with [key]. */
    fun firstShown(key: String): Boolean = shown.add(key)

    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val imageOrder = ArrayDeque<String>()
    private val imageLoading = mutableSetOf<String>()

    val isOwner get() = api.isOwner

    // ---------- feed ----------

    fun refresh() {
        listJob?.cancel()
        listJob = scope.launch {
            loading = true
            try {
                val page = api.list(query, filter.kinds)
                items.clear()
                items.addAll(page.items)
                next = page.next
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if ("can't read" in (e.message ?: "")) {
                    canRead = false
                    loaded = true
                } else {
                    fail(e)
                }
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val before = next ?: return
        if (loading) return
        listJob = scope.launch {
            loading = true
            try {
                val page = api.list(query, filter.kinds, before = before)
                val known = items.mapTo(HashSet()) { it.id }
                items.addAll(page.items.filter { it.id !in known })
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    /** Pull in anything dumped from other devices since the newest item we have. */
    suspend fun poll() {
        if (filtering || loading || !canRead) return
        val newest = items.firstOrNull()
        if (newest == null) {
            // Empty so far: ask quietly, without the loading state, so an empty page doesn't flicker every poll.
            runCatching { api.list() }.onSuccess { page ->
                if (items.isEmpty() && !filtering) {
                    items.addAll(page.items)
                    next = page.next
                    loaded = true
                }
            }
            return
        }
        // Ids are time-prefixed; re-ask for the last minute so items that landed slightly out of order aren't missed.
        val cursor = (newest.created - 60_000).coerceAtLeast(0).toString(36).padStart(9, '0')
        runCatching { api.list(after = cursor, limit = 100) }.onSuccess { page ->
            page.items.forEach(::prepend)
        }
    }

    private fun prepend(item: Item) {
        if (items.any { it.id == item.id }) return
        if (filtering) return
        val i = items.indexOfFirst { it.id < item.id }.let { if (it < 0) items.size else it }
        items.add(i, item)
    }

    // ---------- sending ----------

    fun send(text: String) {
        val edit = editing
        if (edit != null) {
            editing = null
            saveEdit(edit, text)
            return
        }
        if (staged.isNotEmpty()) {
            sendFiles(staged.toList(), text.takeIf { it.isNotBlank() })
            staged.clear()
            return
        }
        if (text.isBlank()) return
        enqueue(Pending(pendingKeys++, text, null, null))
    }

    fun stage(files: List<LocalFile>) {
        staged.addAll(files)
    }

    /** Nothing from outside is sent straight away: it lands in the composer to be captioned or edited. */
    fun receive(incoming: Incoming) {
        when (incoming) {
            is Incoming.Files -> {
                stage(incoming.files)
                incoming.caption?.let { incomingText = it }
            }

            is Incoming.Text -> incomingText = incoming.text
        }
    }

    fun sendFiles(files: List<LocalFile>, caption: String? = null) {
        files.forEachIndexed { i, f -> enqueue(Pending(pendingKeys++, null, f, caption.takeIf { i == 0 })) }
    }

    fun retry(p: Pending) {
        pending.remove(p)
        enqueue(Pending(pendingKeys++, p.text, p.file, p.caption))
    }

    fun dismiss(p: Pending) {
        pending.remove(p)
    }

    private fun enqueue(p: Pending) {
        pending.add(p)
        scope.launch {
            try {
                // Notes go straight out; files share a couple of upload slots so a big one can't block a note.
                val item = if (p.file == null) {
                    api.note(p.text!!)
                } else {
                    uploads.withPermit {
                        api.upload(p.file, p.caption) { f ->
                            val pct = (f * 100).toInt() / 100f // whole percents: one recomposition per step, not per write
                            if (pct != p.progress) p.progress = pct
                        }
                    }
                }
                if (p.file != null && item.kind == "image") seedImage(item, p.file)
                // Swap pending → real item in one snapshot so there's no frame with both or neither.
                shown += item.id // it takes the sending row's place, already on screen: no fade
                Snapshot.withMutableSnapshot {
                    pending.remove(p)
                    prepend(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.error = e.message ?: "failed"
            }
        }
    }

    /** We already have the image locally; decode it instead of downloading it straight back. */
    private suspend fun seedImage(item: Item, file: LocalFile) {
        if (file.size !in 1..MAX_INLINE_IMAGE) return
        val bmp = withContext(Dispatchers.IO) {
            runCatching { file.reader().use { it.readAll(file.size.toInt()) } }.getOrNull()?.let { bytes ->
                platform.cacheImage(api.cacheKey(item.id), bytes) // on disk for next time, no download needed
                platform.decodeImage(bytes, 1280)
            }
        } ?: return
        remember(item.id, bmp)
    }

    private fun saveEdit(item: Item, text: String) {
        scope.launch {
            try {
                val updated = api.edit(item.id, text.ifBlank { null })
                val i = items.indexOfFirst { it.id == item.id }
                if (i >= 0) items[i] = updated
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // ---------- item actions ----------

    fun delete(item: Item) {
        val i = items.indexOf(item)
        items.remove(item)
        scope.launch {
            try {
                api.delete(item.id)
            } catch (e: Exception) {
                if (i >= 0) items.add(minOf(i, items.size), item)
                fail(e)
            }
        }
    }

    fun copy(item: Item) {
        item.text?.let {
            platform.copyText(it)
            message = "Copied"
        }
    }

    fun shareLink(item: Item, ttlSeconds: Long?) {
        scope.launch {
            try {
                val link = api.createLink("read", listOf(item.id), ttlSeconds)
                platform.copyText(link.url)
                message = "Link copied" + (ttlSeconds?.let { " · expires in ${ttlLabel(it)}" } ?: "")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun save(item: Item) {
        scope.launch {
            try {
                message = platform.save(api.rawUrl(item.id, download = true), api.auth(), fileName(item))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun open(item: Item) {
        scope.launch {
            try {
                platform.open(api.rawUrl(item.id), api.auth(), fileName(item))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fileName(item: Item) = item.name ?: "${item.id}.txt"

    fun image(id: String): ImageBitmap? = images[id]

    suspend fun loadImage(item: Item) {
        if (item.id in images || item.id in imageLoading || (item.size ?: 0) > MAX_INLINE_IMAGE) return
        imageLoading += item.id
        try {
            // Without loadImages (every download spends a link's use), only what's already on disk.
            val bytes = imageBytes(platform, api, item.id, download = loadImages) ?: return
            val bmp = withContext(Dispatchers.Default) { platform.decodeImage(bytes, 1280) } ?: return
            remember(item.id, bmp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            imageLoading -= item.id
        }
    }

    private fun remember(id: String, bmp: ImageBitmap) {
        images[id] = bmp
        imageOrder.addLast(id)
        while (imageOrder.size > 80) images.remove(imageOrder.removeFirst())
    }

    // ---------- links ----------

    fun loadLinks() {
        scope.launch {
            try {
                val l = api.links()
                // One swap, so an open share sheet never sees the list empty in between.
                Snapshot.withMutableSnapshot {
                    links.clear()
                    links.addAll(l)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    suspend fun createLink(scope: String, ttlSeconds: Long?, label: String?): Link? = try {
        api.createLink(scope, ttlSeconds = ttlSeconds, label = label).also { links.add(0, it) }
    } catch (e: Exception) {
        fail(e)
        null
    }

    fun revoke(link: Link) {
        links.remove(link)
        scope.launch {
            try {
                api.revoke(link.token)
            } catch (e: Exception) {
                fail(e)
                loadLinks()
            }
        }
    }

    private fun fail(e: Exception) {
        message = e.message ?: "Something went wrong"
    }

    companion object {
        const val MAX_INLINE_IMAGE = 15L * 1024 * 1024
    }
}

/**
 * An item's image bytes: from the disk cache if they're there, else downloaded and kept for next time.
 * With [download] false, only what's already on disk.
 */
internal suspend fun imageBytes(platform: Platform, api: Api, id: String, download: Boolean = true): ByteArray? {
    val key = api.cacheKey(id)
    withContext(Dispatchers.IO) { platform.cachedImage(key) }?.let { return it }
    if (!download) return null
    val bytes = api.bytes(id)
    withContext(Dispatchers.IO) { platform.cacheImage(key, bytes) }
    return bytes
}
