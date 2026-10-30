package realm

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

class Model(val platform: Platform, private val scope: CoroutineScope) {
    var api by mutableStateOf<Api?>(null)
        private set
    val items = mutableStateListOf<Item>() // newest first
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
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf<Item?>(null)
    /** False when connected through a drop-only share link. */
    var canRead by mutableStateOf(true)
        private set

    val filtering get() = query.isNotBlank() || filter != Filter.All

    private var next: String? = null
    private var listJob: Job? = null
    private val uploads = Semaphore(2)
    private var pendingKeys = 0

    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val imageOrder = ArrayDeque<String>()
    private val imageLoading = mutableSetOf<String>()

    init {
        platform.prefs.get("url")?.let { connect(it, platform.prefs.get("token")) }
    }

    // ---------- server ----------

    /** Checks the server answers with these credentials; returns an error message or null. */
    suspend fun check(url: String, token: String?): String? {
        val a = Api(url, token, platform.name)
        return try {
            a.list(limit = 1)
            null
        } catch (e: ApiException) {
            // Drop-only links can't list, but they're still valid.
            if ("/s/" in url && "can't read" in (e.message ?: "")) null else e.message
        } catch (e: Exception) {
            e.message ?: "can't reach server"
        } finally {
            a.close()
        }
    }

    fun connect(url: String, token: String?) {
        api?.close()
        platform.prefs.set("url", url)
        platform.prefs.set("token", token)
        api = Api(url, token, platform.name)
        canRead = true
        items.clear() // the feed refreshes itself when it shows up
    }

    fun disconnect() {
        api?.close()
        api = null
        platform.prefs.set("url", null)
        platform.prefs.set("token", null)
        items.clear()
        links.clear()
    }

    val isOwner get() = api?.auth != null

    // ---------- feed ----------

    fun refresh() {
        val a = api ?: return
        listJob?.cancel()
        listJob = scope.launch {
            loading = true
            try {
                val page = a.list(query, filter.kinds)
                items.clear()
                items.addAll(page.items)
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if ("can't read" in (e.message ?: "")) canRead = false else fail(e)
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val a = api ?: return
        val before = next ?: return
        if (loading) return
        listJob = scope.launch {
            loading = true
            try {
                val page = a.list(query, filter.kinds, before = before)
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
        val a = api ?: return
        if (filtering || loading || !canRead) return
        val newest = items.firstOrNull() ?: return refresh()
        // Ids are time-prefixed; re-ask for the last minute so items that landed slightly out of order aren't missed.
        val cursor = (newest.created - 60_000).coerceAtLeast(0).toString(36).padStart(9, '0')
        runCatching { a.list(after = cursor, limit = 100) }.onSuccess { page ->
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
        val a = api ?: return
        pending.add(p)
        scope.launch {
            try {
                // Notes go straight out; files share a couple of upload slots so a big one can't block a note.
                val item = if (p.file == null) a.note(p.text!!) else uploads.withPermit {
                    a.upload(p.file, p.caption) { f ->
                        val pct = (f * 100).toInt() / 100f // whole percents: one recomposition per step, not per write
                        if (pct != p.progress) p.progress = pct
                    }
                }
                if (p.file != null && item.kind == "image") seedImage(item, p.file)
                // Swap pending → real item in one snapshot so there's no frame with both or neither.
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
            runCatching { file.reader().use { it.readAll(file.size.toInt()) } }.getOrNull()?.let { platform.decodeImage(it, 1280) }
        } ?: return
        cacheImage(item.id, bmp)
    }

    private fun saveEdit(item: Item, text: String) {
        val a = api ?: return
        scope.launch {
            try {
                val updated = a.edit(item.id, text.ifBlank { null })
                val i = items.indexOfFirst { it.id == item.id }
                if (i >= 0) items[i] = updated
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // ---------- item actions ----------

    fun delete(item: Item) {
        val a = api ?: return
        val i = items.indexOf(item)
        items.remove(item)
        scope.launch {
            try {
                a.delete(item.id)
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
        val a = api ?: return
        scope.launch {
            try {
                val link = a.createLink("item", item.id, ttlSeconds)
                platform.copyText(link.url)
                message = "Link copied" + (ttlSeconds?.let { " · expires in ${ttlLabel(it)}" } ?: "")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun save(item: Item) {
        val a = api ?: return
        scope.launch {
            try {
                message = platform.save(a.rawUrl(item.id, download = true), a.auth, fileName(item))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun open(item: Item) {
        val a = api ?: return
        scope.launch {
            try {
                platform.open(a.rawUrl(item.id), a.auth, fileName(item))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fileName(item: Item) = item.name ?: "${item.id}.txt"

    fun image(id: String): ImageBitmap? = images[id]

    suspend fun loadImage(item: Item) {
        val a = api ?: return
        if (item.id in images || item.id in imageLoading || (item.size ?: 0) > MAX_INLINE_IMAGE) return
        imageLoading += item.id
        try {
            val bytes = a.bytes(item.id)
            val bmp = withContext(Dispatchers.Default) { platform.decodeImage(bytes, 1280) } ?: return
            cacheImage(item.id, bmp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            imageLoading -= item.id
        }
    }

    private fun cacheImage(id: String, bmp: ImageBitmap) {
        images[id] = bmp
        imageOrder.addLast(id)
        while (imageOrder.size > 80) images.remove(imageOrder.removeFirst())
    }

    // ---------- links ----------

    fun loadLinks() {
        val a = api ?: return
        scope.launch {
            try {
                val l = a.links()
                links.clear()
                links.addAll(l)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun createLink(scope: String, ttlSeconds: Long?, label: String?) {
        val a = api ?: return
        this.scope.launch {
            try {
                val l = a.createLink(scope, ttlSeconds = ttlSeconds, label = label)
                links.add(0, l)
                platform.copyText(l.url)
                message = "Link copied"
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun revoke(link: Link) {
        val a = api ?: return
        links.remove(link)
        scope.launch {
            try {
                a.revoke(link.token)
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

fun ttlLabel(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}

fun humanSize(n: Long?): String {
    if (n == null) return ""
    var f = n.toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (f >= 1024 && i < units.lastIndex) {
        f /= 1024
        i++
    }
    if (i == 0) return "$n B"
    val r = (f * 10).toLong()
    return "${r / 10}.${r % 10} ${units[i]}"
}
