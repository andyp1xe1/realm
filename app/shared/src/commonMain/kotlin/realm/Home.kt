package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Everything above one feed: the server you're signed in to, its realms, the share links, and which is
 * open. Each space keeps its [Model] while the app runs, like a browser tab.
 */
class Home(val platform: Platform, private val scope: CoroutineScope, private val engine: HttpClientEngine? = null) {
    /** The owner API of the server you're signed in to; null when you only use share links. */
    var server by mutableStateOf<Api?>(null)
        private set
    val realms = mutableStateListOf<Realm>()
    val shared = mutableStateListOf<Space.Shared>()

    /** The current realm's key, like the current tab. */
    var openKey by mutableStateOf<String?>(null)
        private set

    /** The tab overview is up instead of the current realm's feed. */
    var showingTabs by mutableStateOf(false)
        private set

    /** Keys of tiles fading out while the server deletes them. */
    val leaving = mutableStateListOf<String>()

    /** A tile just added in the overview: shown arriving there, then opened. */
    var fresh by mutableStateOf<String?>(null)
        private set

    /** Files or text from outside waiting for a realm to be picked. */
    var incoming by mutableStateOf<Incoming?>(null)
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set

    /** Logging in with Cloudflare, and deploying or updating the worker with it. */
    val cloudflare = CloudflareSetup(this, scope, engine)

    val serverSettings = ServerSettings(this, scope, engine)

    /** Logged in with Cloudflare with no realm worker yet: choosing an account, and deploying. */
    val onboarding = Onboarding(this, scope, engine)

    private val prefs = platform.prefs

    private val knownRealms = Remembered(prefs, "realms", ListSerializer(Realm.serializer()))
    private val knownLinks = Remembered(prefs, "links", ListSerializer(String.serializer()))
    private val apis = mutableMapOf<String, Api>()
    private val models = mutableMapOf<String, Model>()

    /** What each tile shows: its space's latest items and their pictures. */
    val tiles = Tiles(platform)
    val previews get() = tiles.previews

    private val prefetching = Semaphore(2)
    private var lastRefresh: TimeSource.Monotonic.ValueTimeMark? = null

    internal var session: CloudflareSession? = null
        private set

    val spaces: List<Space> get() = realms.map { own(it) } + shared
    val open: Space? get() = openKey?.let { k -> spaces.firstOrNull { it.key == k } }
    val model: Model? get() = open?.let(::modelOf)
    val isEmpty get() = server == null && shared.isEmpty()

    init {
        when (val own = prefs.own()) {
            null -> {}

            is Connection.LoginOnly -> onboarding.start(own.cloudflare, null)

            is Connection.Owner -> {
                val bearer = when (own) {
                    is Connection.ByToken -> Bearer { own.token }
                    is Connection.ByCloudflare -> sessionFor(own).also { session = it }
                }
                server = Api.server(own.server, bearer, platform.name, engine)
                realms.addAll(knownRealms.get().orEmpty())
                serverSettings.refresh()
            }
        }
        knownLinks.get()?.forEach { url ->
            shared += Space.Shared(url, linkInfo(url).get(), null, linkApi(url))
        }
        tiles.restore(spaces.map { it.key })
        openKey = prefs.get("open")?.takeIf { k -> spaces.any { it.key == k } } ?: spaces.firstOrNull()?.key
        refresh()
        model?.refresh()
    }

    private fun checkForUpdate(force: Boolean, api: Api? = server) {
        api?.let { cloudflare.checkForUpdate(it, canDeploy = session?.login?.account != null, force) }
    }

    /** Re-asks the server for its realms and every link for what it is, then refreshes the tile previews. */
    fun refresh(force: Boolean = false) {
        if (!force && lastRefresh?.let { it.elapsedNow() < 3.seconds } == true) return
        lastRefresh = TimeSource.Monotonic.markNow()
        checkForUpdate(force = false)
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
            spaces.filter { it.canRead }.forEach { sp -> launch { tiles.fetch(sp) } }
        }
    }

    /** Fetches the first page of each feed among [keys], the tiles in view, unless fetched in the last minute. */
    fun prefetch(keys: Collection<Any?>) {
        spaces.filter { it.key in keys && it.canRead }.forEach { sp ->
            val m = modelOf(sp)
            scope.launch { prefetching.withPermit { m.prefetch() } }
        }
    }

    private suspend fun loadRealms(s: Api) {
        try {
            val list = s.realms()
            if (server !== s || realms.toList() == list) return
            realms.clear()
            realms.addAll(list)
            knownRealms.set(list)
            if (open == null) reopen()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = explain(e)
        }
    }

    private suspend fun updateShared(url: String) {
        val api = linkApi(url)
        val was = shared.firstOrNull { it.url == url }
        val (info, error) = try {
            api.info() to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            was?.info to explain(e)
        }
        val i = shared.indexOfFirst { it.url == url }
        if (was == null || i < 0 || (was.info == info && was.error == error)) return
        shared[i] = Space.Shared(url, info, error, api)
        if (error == null) linkInfo(url).set(info)
    }

    /** The latest items of a space, for its tile: the open feed's, if it has them. */
    fun preview(space: Space): List<Item> =
        models[space.key]?.items?.takeIf { it.isNotEmpty() }?.take(Tiles.PREVIEW) ?: previews[space.key].orEmpty()

    /** Known to have nothing in it: fetched and empty, not just not fetched yet. */
    fun isEmpty(space: Space): Boolean =
        preview(space).isEmpty() && (previews[space.key] != null || (space is Space.Own && space.realm.count == 0L))

    /** An image for a tile: the open feed's copy if it has one, else the tile's own. */
    fun image(space: Space, item: Item): ImageBitmap? = models[space.key]?.image(item.id) ?: tiles.thumb(item.id)

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

    private fun reopen() {
        openKey = spaces.firstOrNull()?.key
        prefs.set("open", openKey)
    }

    /** Hands files or text from outside to the open realm, or holds them until one is picked. */
    fun receive(i: Incoming) {
        val m = model
        if (m != null && open?.canWrite == true && !showingTabs) return m.receive(i)
        incoming = i
        spaces.filter { it.canWrite }.singleOrNull()?.let(::open)
    }

    /** [space]'s feed, starting from its first page as kept last time. */
    fun modelOf(space: Space): Model = models.getOrPut(space.key) {
        Model(platform, scope, space.api, loadImages = !space.limited, FeedCache(platform.cache, space.key))
    }

    internal fun sessionFor(owner: Connection.ByCloudflare): CloudflareSession {
        lateinit var s: CloudflareSession
        s = CloudflareSession(owner.login) { next ->
            if (session !== s) return@CloudflareSession
            if (next != null) {
                prefs.saveOwn(owner.copy(login = next))
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

    /** Signs in to your own server at [url]; returns an error message or null. */
    suspend fun signIn(url: String, token: String): String? {
        val s = try {
            signInWithToken(url, token, platform.name, engine)
        } catch (e: ApiException) {
            logFailure("Signing in with a token", e)
            return e.message
        }
        signedIn(s)
        return null
    }

    internal fun signedIn(s: SignedIn) {
        onboarding.handOver()
        signOut()
        adoptOwner(s)
        serverSettings.refresh()
    }

    internal fun switchedTo(s: SignedIn) {
        val was = openKey
        leave(switching = true)
        adoptOwner(s)
        if (spaces.any { it.key == was }) {
            openKey = was
            prefs.set("open", was)
        }
    }

    private fun adoptOwner(s: SignedIn) {
        session = s.session
        prefs.saveOwn(s.connection)
        checkForUpdate(force = true, s.api)
        adopt(s.api, s.realms)
        message = Api.mismatch(s.info)
        model?.refresh()
    }

    private fun adopt(api: Api, list: List<Realm>) {
        server = api
        realms.addAll(list)
        knownRealms.set(list)
        if (open == null) reopen()
        refresh(force = true)
    }

    fun signOut() = leave(switching = false)

    /** Leaves the server for the account chooser, with the same Cloudflare login: no new consent. */
    fun useAnotherAccount() {
        val login = session?.login ?: return
        Snapshot.withMutableSnapshot {
            leave(switching = false, ending = false)
            onboarding.start(login.copy(account = null), null)
        }
    }

    private fun leave(switching: Boolean, ending: Boolean = !switching) {
        realms.forEach { forget(ownKey(it.id), drop = !switching) }
        realms.clear()
        server?.close()
        server = null
        val kept = onboarding.handOver()
        if (ending) (session ?: kept)?.let { s -> scope.launch { Cloudflare.revoke(s.login) } }
        session = null
        cloudflare.signedOut()
        if (!switching) serverSettings.signedOut()
        prefs.saveOwn(null)
        knownRealms.set(null)
        if (open == null) reopen()
    }

    /** Adds a share link as a tile and opens it; returns an error message or null. */
    suspend fun connect(link: String): String? {
        linkUrl(link)?.let { u -> shared.firstOrNull { it.url == u } }?.let {
            open(it)
            return null
        }
        val c = try {
            connectLink(link, platform.name, engine)
        } catch (e: ApiException) {
            return e.message
        }
        apis[sharedKey(c.url)] = c.api
        val space = Space.Shared(c.url, c.info, null, c.api)
        message = Api.mismatch(c.info)
        shared += space
        saveLinks()
        linkInfo(c.url).set(c.info)
        arrive(space)
        if (space.canRead) tiles.fetch(space)
        return null
    }

    fun disconnect(space: Space.Shared) {
        leaving += space.key
        scope.launch {
            delay(TILE_LEAVE_MILLIS)
            shared.removeAll { it.key == space.key }
            leaving -= space.key
            forget(space.key, drop = true)
            linkInfo(space.url).set(null)
            saveLinks()
            if (open == null) reopen()
        }
    }

    private fun saveLinks() = knownLinks.set(shared.map { it.url })

    fun createRealm(name: String) {
        val s = server ?: return
        scope.launch {
            try {
                val r = s.createRealm(name)
                realms += r
                knownRealms.set(realms.toList())
                val space = own(r)
                modelOf(space).startEmpty()
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
                coroutineScope {
                    launch { delay(TILE_LEAVE_MILLIS) }
                    s.deleteRealm(realm.id)
                }
                realms.removeAll { it.id == realm.id }
                forget(key, drop = true)
                knownRealms.set(realms.toList())
                if (open == null) reopen()
            } catch (e: Exception) {
                message = explain(e)
            } finally {
                leaving -= key
            }
        }
    }

    private fun arrive(space: Space) {
        if (showingTabs) fresh = space.key else open(space)
    }

    private fun own(r: Realm) = Space.Own(r, apis.getOrPut(ownKey(r.id)) { server!!.realm(r.id) })

    private fun linkApi(url: String) = apis.getOrPut(sharedKey(url)) { Api(url, null, platform.name, engine) }

    private fun linkInfo(url: String) = Remembered(platform.cache, "link-info/$url", Info.serializer())

    private fun forget(key: String, drop: Boolean) {
        apis.remove(key)?.close()
        models.remove(key)
        tiles.forget(key, drop)
        if (drop) FeedCache(platform.cache, key).forget()
    }

    companion object {
        /** How long a tile takes to fade away when deleted or removed. */
        const val TILE_LEAVE_MILLIS = 220L
    }
}
