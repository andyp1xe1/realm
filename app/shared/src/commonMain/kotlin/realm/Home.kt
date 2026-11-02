package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Everything above one feed: the server you're signed in to, its realms, the share links, and which is
 * open. Each space keeps its [Model] while the app runs, like a browser tab.
 */
class Home(val platform: Platform, private val scope: CoroutineScope, private val engine: HttpClientEngine? = null) {
    var phase by mutableStateOf<Phase>(Phase.Setup)
        internal set

    /** The owner API of the server you're signed in to; null when you only use share links. */
    val server: Api? get() = (phase as? Phase.SignedIn)?.server

    internal val session: CloudflareSession? get() = (phase as? Phase.SignedIn)?.session

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

    val serverSettings = ServerSettings(this, engine)

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
    private var refreshing: Job? = null

    val spaces: List<Space> get() = realms.map { own(it) } + shared
    val open: Space? get() = openKey?.let { k -> spaces.firstOrNull { it.key == k } }
    val model: Model? get() = open?.let(::modelOf)
    val isEmpty get() = server == null && shared.isEmpty()

    init {
        when (val own = prefs.own()) {
            null -> {}

            is Connection.LoginOnly -> onboarding.start(own.cloudflare, null)

            is Connection.Owner -> {
                val (bearer, session) = when (own) {
                    is Connection.ByToken -> Bearer { own.token.value } to null
                    is Connection.ByCloudflare -> sessionFor(own).let { it to it }
                }
                phase = signedIn(Api.server(own.server, bearer, platform.name, engine), session)
                realms.addAll(knownRealms.get().orEmpty())
            }
        }
        knownLinks.get()?.forEach { url ->
            shared += Space.Shared(url, linkInfo(url).get(), null, linkApi(url))
        }
        tiles.restore(spaces.map { it.key })
        openKey = prefs.get("open")?.takeIf { k -> spaces.any { it.key == k } } ?: spaces.firstOrNull()?.key
        fetchAhead(phase as? Phase.SignedIn, known = null, force = false)
    }

    /** Re-asks the server for its realms and every link for what it is, then refreshes the tile previews, unless that's under way. */
    fun refresh() {
        if (refreshing?.isActive == true) return
        val signedIn = phase as? Phase.SignedIn
        refreshing = scope.launch {
            loading = true
            try {
                coroutineScope {
                    shared.toList().forEach { sp -> launch { updateShared(sp.url) } }
                    signedIn?.run { scope.launch { loadRealms(server) }.join() }
                }
            } finally {
                loading = false
            }
            spaces.filter { it.canRead }.forEach { sp -> scopeOf(sp).launch { tiles.fetch(sp) } }
        }
    }

    /** Fetches the first page of each feed among [keys], the tiles in view, unless fetched in the last minute. */
    fun prefetch(keys: Collection<Any?>) {
        spaces.filter { it.key in keys && it.canRead }.forEach { sp ->
            val m = modelOf(sp)
            scopeOf(sp).launch { prefetching.withPermit { m.prefetch() } }
        }
    }

    private fun scopeOf(space: Space): CoroutineScope = (phase as? Phase.SignedIn)?.takeIf { space is Space.Own }?.scope ?: scope

    private suspend fun loadRealms(s: Api) {
        try {
            val list = s.realms()
            if (realms.toList() == list) return
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
        Model(platform, scopeOf(space), space.api, loadImages = !space.limited, FeedCache(platform.cache, space.key))
    }

    internal fun sessionFor(owner: Connection.ByCloudflare): CloudflareSession =
        sessionFor(owner.login) { Connection.ByCloudflare(server?.base?.removeSuffix("/api") ?: owner.server, it) }

    internal fun sessionFor(login: CloudflareLogin, kept: (CloudflareLogin) -> Connection.Own) = CloudflareSession(login) { next ->
        if (next != null) {
            prefs.saveOwn(kept(next))
        } else {
            scope.launch {
                signOut()
                message = CloudflareRejected().message
            }
        }
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
        endSession()
        val p = signedIn(s.api, s.session)
        phase = p
        prefs.saveOwn(s.connection)
        realms.addAll(s.realms)
        knownRealms.set(s.realms)
        if (open == null) reopen()
        message = Api.mismatch(s.info)
        fetchAhead(p, s.info, force = true)
    }

    private fun fetchAhead(p: Phase.SignedIn?, known: Info?, force: Boolean) {
        p?.let { serverSettings.lookAt(it, known, force) }
        refreshing?.cancel()
        refresh()
        model?.refresh()
    }

    internal fun movedTo(facts: ServerFacts) {
        val p = phase as? Phase.SignedIn ?: return
        val session = p.session ?: return
        p.facts.forget()
        p.server.rebase(facts.origin)
        realms.forEach { apis[ownKey(it.id)]?.rebase(facts.origin) }
        phase = Phase.SignedIn(p.server, session, serverSettings.factsAt(p.server, p.scope).apply { set(facts) }, p.scope)
        prefs.saveOwn(Connection.ByCloudflare(facts.origin, session.login))
    }

    private fun signedIn(api: Api, session: CloudflareSession?): Phase.SignedIn {
        val work = childScope()
        return Phase.SignedIn(api, session, serverSettings.factsAt(api, work), work)
    }

    internal fun childScope(): CoroutineScope {
        val job = Job()
        val ended = scope.coroutineContext.job.invokeOnCompletion { job.cancel() }
        job.invokeOnCompletion { ended.dispose() }
        return CoroutineScope(scope.coroutineContext + job)
    }

    /** Signs out, ending the Cloudflare login there is. */
    fun signOut() {
        endSession()?.let { s -> scope.launch { Cloudflare.revoke(s.login) } }
    }

    /** Leaves the server for the account chooser, with the same Cloudflare login: no new consent. */
    fun useAnotherAccount() {
        val login = session?.login ?: return
        endSession()
        onboarding.start(login.copy(account = null), null)
    }

    private fun endSession(): CloudflareSession? {
        val was = phase
        phase = Phase.Setup
        realms.forEach { forget(ownKey(it.id), drop = true) }
        realms.clear()
        onboarding.forget()
        val login = when (was) {
            is Phase.SignedIn -> was.session.also {
                was.scope.cancel()
                was.server.close()
                was.facts.forget()
            }

            is Phase.Onboarding -> was.at.session.also { was.at.scope.cancel() }

            Phase.Setup -> null
        }
        login?.close()
        cloudflare.signedOut()
        serverSettings.signedOut()
        prefs.saveOwn(null)
        knownRealms.set(null)
        if (open == null) reopen()
        return login
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

    fun createRealm(name: String) = onServer { s ->
        val r = s.createRealm(name)
        realms += r
        knownRealms.set(realms.toList())
        val space = own(r)
        modelOf(space).startEmpty()
        arrive(space)
    }

    fun renameRealm(realm: Realm, name: String) = onServer { s ->
        val r = s.renameRealm(realm.id, name)
        val i = realms.indexOfFirst { it.id == realm.id }
        if (i >= 0) realms[i] = r
    }

    fun deleteRealm(realm: Realm) {
        val key = ownKey(realm.id)
        onServer { s ->
            leaving += key
            try {
                coroutineScope {
                    launch { delay(TILE_LEAVE_MILLIS) }
                    s.deleteRealm(realm.id)
                }
                realms.removeAll { it.id == realm.id }
                forget(key, drop = true)
                knownRealms.set(realms.toList())
                if (open == null) reopen()
            } finally {
                leaving -= key
            }
        }
    }

    private fun onServer(work: suspend (Api) -> Unit) {
        val p = phase as? Phase.SignedIn ?: return
        p.scope.launch {
            try {
                work(p.server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = explain(e)
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
