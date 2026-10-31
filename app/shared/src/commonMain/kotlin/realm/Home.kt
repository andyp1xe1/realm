package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A tile in the overview: one of your realms, or a share link you connected to. */
sealed class Space(val key: String, val api: Api) {
    abstract val name: String
    abstract val canRead: Boolean
    abstract val canWrite: Boolean
    /** Every download spends one of the link's uses, so nothing is fetched just to show it. */
    open val limited: Boolean get() = false

    class Own(val realm: Realm, api: Api) : Space(ownKey(realm.id), api) {
        override val name get() = realm.name
        override val canRead get() = true
        override val canWrite get() = true
    }

    /** [info] is null until the link has answered once; [error] is why it last didn't. */
    class Shared(val url: String, val info: Info?, val error: String?, api: Api) : Space(sharedKey(url), api) {
        override val name: String
            get() = info?.item?.let { it.name ?: it.text?.lineSequence()?.firstOrNull() }
                ?: info?.realm?.name
                ?: url.substringAfter("://").substringBefore('/')
        override val canRead get() = error == null && info?.scope in setOf("read", "full")
        override val canWrite get() = error == null && info?.scope in setOf("drop", "full")
        override val limited get() = info?.usesLeft != null
    }
}

private fun ownKey(id: String) = "realm:$id"
private fun sharedKey(url: String) = "link:$url"

/**
 * Everything above one feed: the server you're signed in to, its realms, the share links you've connected
 * to, and which of them is open. Each space keeps its [Model] while the app runs, like a browser tab, so
 * leaving a realm mid-upload doesn't stop the upload.
 */
class Home(val platform: Platform, private val scope: CoroutineScope) {
    /** The owner API of the server you're signed in to; null when you only use share links. */
    var server by mutableStateOf<Api?>(null)
        private set
    val realms = mutableStateListOf<Realm>()
    val shared = mutableStateListOf<Space.Shared>()
    /** The current realm, like the current tab; the first one unless another was open last time. */
    var openKey by mutableStateOf<String?>(null)
        private set
    /** The tab overview is up instead of the current realm's feed. */
    var showingTabs by mutableStateOf(false)
        private set
    /** Tiles on their way out: they fade away in the overview while the server deletes them. */
    val leaving = mutableStateListOf<String>()
    /** A tile just added in the overview: it's shown arriving there first, then opened from where it landed. */
    var fresh by mutableStateOf<String?>(null)
        private set
    /** Files or text from outside that arrived with no realm open, waiting for one to be picked. */
    var incoming by mutableStateOf<Incoming?>(null)
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set

    private val prefs = platform.prefs
    private val apis = mutableMapOf<String, Api>()
    private val models = mutableMapOf<String, Model>()
    /** The latest few items of each space, by key, as last fetched for the tiles. */
    val previews = mutableStateMapOf<String, List<Item>>()
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val thumbLoading = mutableSetOf<String>()
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    val spaces: List<Space> get() = realms.map { own(it) } + shared
    val open: Space? get() = openKey?.let { k -> spaces.firstOrNull { it.key == k } }
    val model: Model? get() = open?.let(::modelOf)
    val isEmpty get() = server == null && shared.isEmpty()

    init {
        migratePrefs()
        val origin = prefs.get("server")
        val token = prefs.get("token")
        if (origin != null && token != null) {
            server = Api.server(origin, token, platform.name)
            // Last known realms, so the overview shows straight away and works offline.
            realms.addAll(prefs.get("realms")?.let { runCatching { json.decodeFromString<List<Realm>>(it) }.getOrNull() }.orEmpty())
        }
        prefs.get("links")?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }?.forEach { url ->
            shared += Space.Shared(url, null, null, linkApi(url))
        }
        openKey = prefs.get("open")?.takeIf { k -> spaces.any { it.key == k } } ?: spaces.firstOrNull()?.key
        refresh()
    }

    /** Before realms, the app kept one url: a server (with a token) or a share link. */
    private fun migratePrefs() {
        val url = prefs.get("url") ?: return
        if ("/s/" in url) {
            prefs.set("links", json.encodeToString(listOf(url)))
            prefs.set("token", null)
        } else {
            prefs.set("server", url)
        }
        prefs.set("url", null)
    }

    // ---------- the overview ----------

    /** Re-asks the server for its realms and every link for what it is, then refreshes the tile previews. */
    fun refresh(force: Boolean = false) {
        if (!force && lastRefresh?.let { it.elapsedNow() < 3.seconds } == true) return // the overview and init both ask
        lastRefresh = TimeSource.Monotonic.markNow()
        scope.launch {
            loading = true
            try {
                coroutineScope {
                    server?.let { s -> launch { loadRealms(s) } }
                    shared.toList().forEach { sp -> launch { updateShared(sp.url) } }
                }
            } finally {
                loading = false
            }
            spaces.filter { it.canRead }.forEach { sp ->
                launch { runCatching { sp.api.list(limit = PREVIEW).items }.onSuccess { previews[sp.key] = it } }
            }
        }
    }

    private suspend fun loadRealms(s: Api) {
        try {
            val list = s.realms()
            if (server !== s) return // signed out meanwhile
            realms.clear()
            realms.addAll(list)
            prefs.set("realms", json.encodeToString(list))
            if (open == null) reopen()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = explain(e)
        }
    }

    private suspend fun updateShared(url: String) {
        val api = linkApi(url)
        val next = try {
            Space.Shared(url, api.info(), null, api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Space.Shared(url, shared.firstOrNull { it.url == url }?.info, explain(e), api)
        }
        val i = shared.indexOfFirst { it.url == url }
        if (i >= 0) shared[i] = next
    }

    /** The latest items of a space, for its tile. An open feed knows better than the last fetch. */
    fun preview(space: Space): List<Item> =
        models[space.key]?.items?.takeIf { it.isNotEmpty() }?.take(PREVIEW) ?: previews[space.key].orEmpty()

    /** Known to have nothing in it: fetched and empty, not just not fetched yet. */
    fun isEmpty(space: Space): Boolean =
        preview(space).isEmpty() && (previews[space.key] != null || (space is Space.Own && space.realm.count == 0L))

    fun thumb(id: String): ImageBitmap? = thumbs[id]

    /** An image for a tile: the open feed's copy if it has one, else the tile's own. */
    fun image(space: Space, item: Item): ImageBitmap? = models[space.key]?.image(item.id) ?: thumbs[item.id]

    suspend fun loadThumb(space: Space, item: Item) {
        if (space.limited || item.id in thumbs || item.id in thumbLoading || (item.size ?: 0) > Model.MAX_INLINE_IMAGE) return
        thumbLoading += item.id
        try {
            val bytes = space.api.bytes(item.id)
            withContext(Dispatchers.Default) { platform.decodeImage(bytes, 480) }?.let { thumbs[item.id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            thumbLoading -= item.id
        }
    }

    // ---------- opening ----------

    fun open(space: Space) {
        if (fresh == space.key) fresh = null
        openKey = space.key
        prefs.set("open", space.key)
        showingTabs = false
        val waiting = incoming ?: return
        if (space.canWrite) {
            modelOf(space).receive(waiting)
            incoming = null
        }
    }

    fun showTabs() {
        showingTabs = true
    }

    /** Back to the current realm, if there is one. */
    fun hideTabs() {
        if (open != null) showingTabs = false
    }

    /** After the current realm goes away: the first one left, as when a browser tab closes. */
    private fun reopen() {
        openKey = spaces.firstOrNull()?.key
        prefs.set("open", openKey)
    }

    /** Hands files or text from outside to the open realm, or holds them until one is picked. */
    fun receive(i: Incoming) {
        val m = model
        if (m != null && open?.canWrite == true && !showingTabs) return m.receive(i)
        incoming = i
        spaces.filter { it.canWrite }.singleOrNull()?.let(::open) // only one place it can go
    }

    fun modelOf(space: Space): Model = models.getOrPut(space.key) { Model(platform, scope, space.api, loadImages = !space.limited) }

    // ---------- signing in and connecting ----------

    /** Signs in to your own server; returns an error message or null. */
    suspend fun signIn(origin: String, token: String): String? {
        val api = Api.server(origin, token, platform.name)
        try {
            if (api.info().scope != "owner") error("not an owner token")
        } catch (e: CancellationException) {
            api.close()
            throw e
        } catch (e: Exception) {
            api.close()
            return explain(e)
        }
        signOut()
        server = api
        prefs.set("server", origin)
        prefs.set("token", token)
        refresh(force = true)
        return null
    }

    fun signOut() {
        realms.forEach { forget(ownKey(it.id)) }
        realms.clear()
        server?.close()
        server = null
        listOf("server", "token", "realms").forEach { prefs.set(it, null) }
        if (open == null) reopen()
    }

    /** Adds a share link as a tile and opens it; returns an error message or null. */
    suspend fun connect(url: String): String? {
        val u = url.trim().trimEnd('/')
        if ("/s/" !in u) return "That's not a share link"
        shared.firstOrNull { it.url == u }?.let { open(it); return null }
        val api = Api(u, null, platform.name)
        val info = try {
            api.info()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            api.close()
            return explain(e)
        }
        apis[sharedKey(u)] = api
        val space = Space.Shared(u, info, null, api)
        shared += space
        saveLinks()
        arrive(space)
        if (space.canRead) runCatching { api.list(limit = PREVIEW).items }.onSuccess { previews[space.key] = it }
        return null
    }

    fun disconnect(space: Space.Shared) {
        leaving += space.key
        scope.launch {
            delay(TileLeaveMillis) // let the tile fade out first
            shared.removeAll { it.key == space.key }
            leaving -= space.key
            forget(space.key)
            saveLinks()
            if (open == null) reopen()
        }
    }

    private fun saveLinks() = prefs.set("links", json.encodeToString(shared.map { it.url }))

    // ---------- realms ----------

    fun createRealm(name: String) {
        val s = server ?: return
        scope.launch {
            try {
                val r = s.createRealm(name)
                realms += r
                prefs.set("realms", json.encodeToString(realms.toList()))
                val space = own(r)
                modelOf(space).startEmpty() // brand new: nothing to ask the server for
                arrive(space)
            } catch (e: Exception) {
                message = explain(e)
            }
        }
    }

    fun renameRealm(realm: Realm, name: String) {
        val s = server ?: return
        scope.launch {
            try {
                val r = s.renameRealm(realm.id, name)
                val i = realms.indexOfFirst { it.id == realm.id }
                if (i >= 0) realms[i] = r
            } catch (e: Exception) {
                message = explain(e)
            }
        }
    }

    fun deleteRealm(realm: Realm) {
        val s = server ?: return
        val key = ownKey(realm.id)
        leaving += key
        scope.launch {
            try {
                // The tile fades out meanwhile; it's only removed (and the rest slide over) once both are done.
                coroutineScope {
                    launch { delay(TileLeaveMillis) }
                    s.deleteRealm(realm.id)
                }
                realms.removeAll { it.id == realm.id }
                forget(key)
                prefs.set("realms", json.encodeToString(realms.toList()))
                if (open == null) reopen()
            } catch (e: Exception) {
                message = explain(e) // the tile comes back
            } finally {
                leaving -= key
            }
        }
    }

    /**
     * A new tile: in the overview it arrives first and the overview opens it once it has landed, so the zoom
     * starts from a tile that's really there. Elsewhere (first run) it just opens.
     */
    private fun arrive(space: Space) {
        if (showingTabs) fresh = space.key else open(space)
    }

    private fun own(r: Realm) = Space.Own(r, apis.getOrPut(ownKey(r.id)) { server!!.realm(r.id) })

    private fun linkApi(url: String) = apis.getOrPut(sharedKey(url)) { Api(url, null, platform.name) }

    private fun forget(key: String) {
        apis.remove(key)?.close()
        models.remove(key)
        previews.remove(key)
    }

    /** Error messages that say what to do. */
    private fun explain(e: Exception): String = when (e.message) {
        // A worker from before realms has no /info.
        "not found" -> "That server needs updating: redeploy the worker."
        "bad token" -> "That token doesn't work for this server."
        else -> e.message ?: "Can't reach the server"
    }

    companion object {
        /** How long a tile takes to fade away when deleted or removed. */
        const val TileLeaveMillis = 220L

        /** Items fetched per tile: enough to fill a tall one; the oldest clip off the top. */
        private const val PREVIEW = 8
        private val json = Json { ignoreUnknownKeys = true }
    }
}
