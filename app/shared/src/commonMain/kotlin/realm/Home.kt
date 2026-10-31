package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    /** Logging in with Cloudflare is under way: the browser is open, or the worker is being looked for. */
    var loggingIn by mutableStateOf(false)
        private set

    /** Logging in found a realm worker on more than one Cloudflare account: one is to be [pick]ed. */
    var workerChoice by mutableStateOf<List<Worker>?>(null)
        private set

    private val prefs = platform.prefs
    private val apis = mutableMapOf<String, Api>()
    private val models = mutableMapOf<String, Model>()

    /** The latest few items of each space, by key, as last fetched for the tiles. */
    val previews = mutableStateMapOf<String, List<Item>>()
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val thumbLoading = mutableSetOf<String>()
    private val thumbOrder = ArrayDeque<String>()
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    /** The Cloudflare login the server is signed in with, if it is. */
    private var session: CloudflareSession? = null
    private var loginJob: Job? = null
    private var picked: CompletableDeferred<Worker?>? = null

    val spaces: List<Space> get() = realms.map { own(it) } + shared
    val open: Space? get() = openKey?.let { k -> spaces.firstOrNull { it.key == k } }
    val model: Model? get() = open?.let(::modelOf)
    val isEmpty get() = server == null && shared.isEmpty()

    init {
        migratePrefs()
        val origin = prefs.get("server")
        val token = prefs.get("token")
        val login = prefs.get("cloudflare")?.let { runCatching { JSON.decodeFromString<CloudflareLogin>(it) }.getOrNull() }
        val bearer = when {
            token != null -> Bearer { token }
            login != null -> cloudflare(login).also { session = it }
            else -> null
        }
        if (origin != null && bearer != null) {
            server = Api.server(origin, bearer, platform.name)
            // Last known realms, so the overview shows straight away and works offline.
            realms.addAll(prefs.get("realms")?.let { runCatching { JSON.decodeFromString<List<Realm>>(it) }.getOrNull() }.orEmpty())
        }
        prefs.get("links")?.let { runCatching { JSON.decodeFromString<List<String>>(it) }.getOrNull() }?.forEach { url ->
            shared += Space.Shared(url, null, null, linkApi(url))
        }
        openKey = prefs.get("open")?.takeIf { k -> spaces.any { it.key == k } } ?: spaces.firstOrNull()?.key
        refresh()
    }

    /** Before realms, the app kept one url: a server (with a token) or a share link. */
    private fun migratePrefs() {
        val url = prefs.get("url") ?: return
        if ("/s/" in url) {
            prefs.set("links", JSON.encodeToString(listOf(url)))
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
            prefs.set("realms", JSON.encodeToString(list))
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
        if (item.id in thumbs || item.id in thumbLoading || (item.size ?: 0) > Model.MAX_INLINE_IMAGE) return
        thumbLoading += item.id
        try {
            // A use-limited link only shows what's already on disk: a download would spend a use.
            val bytes = imageBytes(platform, space.api, item.id, download = !space.limited) ?: return
            withContext(Dispatchers.Default) { platform.decodeImage(bytes, 480) }?.let { bmp ->
                thumbs[item.id] = bmp
                thumbOrder.addLast(item.id)
                while (thumbOrder.size > 120) thumbs.remove(thumbOrder.removeFirst()) // the disk has the rest
            }
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

    /**
     * Logs in with Cloudflare in the browser and signs in to the realm worker on that account, as `realm login`
     * does: no URL or token to copy, the login is the owner's token. Tapping again starts over.
     */
    fun signInWithCloudflare() {
        val previous = loginJob
        loginJob = scope.launch {
            previous?.cancelAndJoin()
            loggingIn = true
            try {
                val login = Cloudflare.logIn(platform)
                val found = Cloudflare.workers(login.access)
                val worker = when (found.size) {
                    0 -> throw ApiException("There's no realm worker on that Cloudflare account. Deploy it first, then log in.")
                    1 -> found[0]
                    else -> askWhich(found) ?: return@launch
                }
                // The worker takes a Cloudflare login as its owner's once it knows which account it's on.
                Cloudflare.claim(login.access, worker)
                val s = cloudflare(login.copy(account = worker.account))
                val api = Api.server(worker.origin, s, platform.name)
                val (info, list) = try {
                    Cloudflare.checkOwner(api) to api.realms()
                } catch (e: Exception) {
                    api.close()
                    throw e
                }
                signOut()
                session = s
                prefs.set("server", worker.origin)
                prefs.set("cloudflare", JSON.encodeToString(s.login))
                adopt(api, list)
                message = Api.mismatch(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = explain(e)
            } finally {
                if (loginJob === coroutineContext[Job]) loggingIn = false
            }
        }
    }

    /** Answers [workerChoice]; null gives up on logging in. */
    fun pick(worker: Worker?) {
        picked?.complete(worker)
    }

    private suspend fun askWhich(found: List<Worker>): Worker? {
        val d = CompletableDeferred<Worker?>().also { picked = it }
        workerChoice = found
        return try {
            d.await()
        } finally {
            workerChoice = null
            picked = null
        }
    }

    /** [login] as the owner's token, keeping each renewal while it's the one signed in, and signing out once it's turned down. */
    private fun cloudflare(login: CloudflareLogin): CloudflareSession {
        lateinit var s: CloudflareSession
        s = CloudflareSession(login) { next ->
            if (session !== s) return@CloudflareSession
            if (next != null) {
                prefs.set("cloudflare", JSON.encodeToString(next))
            } else {
                scope.launch {
                    if (session !== s) return@launch
                    signOut()
                    message = CloudflareRejected().message
                }
            }
        }
        return s
    }

    /** Signs in to your own server; returns an error message or null. */
    suspend fun signIn(origin: String, token: String): String? {
        val api = Api.server(origin, Bearer { token }, platform.name)
        val (info, list) = try {
            val info = api.info()
            if (info.scope != "owner") error("not an owner token")
            info to api.realms()
        } catch (e: CancellationException) {
            api.close()
            throw e
        } catch (e: Exception) {
            api.close()
            return explain(e)
        }
        signOut()
        prefs.set("server", origin)
        prefs.set("token", token)
        adopt(api, list)
        message = Api.mismatch(info)
        return null
    }

    /**
     * Switches to a server just signed in to, with its realms already fetched: the app goes straight
     * from setup into a realm, instead of to an empty overview that then zooms into the realm as it loads.
     */
    private fun adopt(api: Api, list: List<Realm>) {
        server = api
        realms.addAll(list)
        prefs.set("realms", JSON.encodeToString(list))
        if (open == null) reopen()
        refresh(force = true)
    }

    fun signOut() {
        realms.forEach { forget(ownKey(it.id)) }
        realms.clear()
        server?.close()
        server = null
        session?.let { s -> scope.launch { Cloudflare.revoke(s.login) } }
        session = null
        listOf("server", "token", "cloudflare", "realms").forEach { prefs.set(it, null) }
        if (open == null) reopen()
    }

    /** Adds a share link as a tile and opens it; returns an error message or null. */
    suspend fun connect(url: String): String? {
        val u = url.trim().trimEnd('/')
        if ("/s/" !in u) return "That's not a share link"
        shared.firstOrNull { it.url == u }?.let {
            open(it)
            return null
        }
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
        message = Api.mismatch(info)
        shared += space
        saveLinks()
        arrive(space)
        if (space.canRead) runCatching { api.list(limit = PREVIEW).items }.onSuccess { previews[space.key] = it }
        return null
    }

    fun disconnect(space: Space.Shared) {
        leaving += space.key
        scope.launch {
            delay(TILE_LEAVE_MILLIS) // let the tile fade out first
            shared.removeAll { it.key == space.key }
            leaving -= space.key
            forget(space.key)
            saveLinks()
            if (open == null) reopen()
        }
    }

    private fun saveLinks() = prefs.set("links", JSON.encodeToString(shared.map { it.url }))

    // ---------- realms ----------

    fun createRealm(name: String) {
        val s = server ?: return
        scope.launch {
            try {
                val r = s.createRealm(name)
                realms += r
                prefs.set("realms", JSON.encodeToString(realms.toList()))
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
                    launch { delay(TILE_LEAVE_MILLIS) }
                    s.deleteRealm(realm.id)
                }
                realms.removeAll { it.id == realm.id }
                forget(key)
                prefs.set("realms", JSON.encodeToString(realms.toList()))
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
        const val TILE_LEAVE_MILLIS = 220L

        /** Items fetched per tile: enough to fill a tall one; the oldest clip off the top. */
        private const val PREVIEW = 8
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
