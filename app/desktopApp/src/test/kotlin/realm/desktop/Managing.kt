// Filmstrips of settings' server section, signed in with Cloudflare to a stand-in worker: checking for
// updates, adding a domain past a DNS record, removing one, and switching the address the app uses.
package realm.desktop

import kotlinx.coroutines.CoroutineScope
import realm.AddressTask
import realm.Api
import realm.Check
import realm.DomainChoice
import realm.Home
import realm.Loaded
import realm.ServerStatus
import realm.Zone
import realm.ui.INDICATOR_DELAY_MILLIS
import realm.value

/** Signed in with Cloudflare to [cloud]'s worker, with settings open. */
private fun settingsOver(cloud: FakeCloud): Pair<Home, Film> {
    val home = Home(FakePlatform(), CoroutineScope(Ui), cloud.engine)
    home.cloudflare.logIn()
    Film(home).apply { until { home.server != null } }.close()
    return home to Film(home, settings = true)
}

/**
 * manage-uptodate-N: settings opens, the server section fades in, and checking finds it up to date.
 * manage-update-N: the same on an older worker finds an update. manage-add-N: a domain is added past the
 * A record in its way. manage-remove-N: it's taken off again, after asking. manage-switch-N: another is
 * added, and the app switches to it. manage-workersdev-N: workers.dev is turned off, after asking, and on again.
 * manage-move-N: the address in use is removed, the app moving to workers.dev first. manage-token: the same section signed in with a fixed token.
 */
internal fun managing() {
    for ((name, worker) in listOf(
        "manage-uptodate" to """{"version":${Api.VERSION},"scope":"owner","build":"v0.1.0"}""",
        "manage-update" to """{"version":${Api.VERSION - 1},"scope":"owner"}""",
    )) {
        val (home, film) = settingsOver(FakeCloud(worker = worker))
        val settings = home.serverSettings
        film.roll(name, max = 200) { settings.facts?.state is Loaded.Known }
        film.roll(name, max = 24)
        settings.checkForUpdates()
        film.roll(name, max = 300) { settings.check == Check.Idle }
        film.roll(name, max = 30)
        film.close()
    }

    val (home, film) = settingsOver(FakeCloud(worker = """{"version":${Api.VERSION},"scope":"owner"}"""))
    val settings = home.serverSettings
    fun task() = settings.task
    film.until { settings.facts?.state is Loaded.Known }
    val jo = Zone("z1", "jo.dev")

    settings.adding(true)
    film.roll("manage-add", max = 24)
    settings.addDomain(DomainChoice(jo, "realm"))
    film.roll("manage-add", max = 300) { task() is AddressTask.Conflict }
    film.roll("manage-add", max = 30)
    settings.replace(agree = true)
    film.roll("manage-add", max = 300) { task() == null }
    film.roll("manage-add", max = 30)

    val added = settings.facts!!.state.value!!.addresses!!.domains.single()
    settings.remove("https://${added.hostname}")
    film.roll("manage-remove", max = 30)
    settings.confirmRemove(agree = true)
    film.roll("manage-remove", max = 300) { task() == null }
    film.roll("manage-remove", max = 30)

    settings.adding(true)
    film.until { task() == AddressTask.Adding }
    settings.addDomain(DomainChoice(jo, "notes"))
    film.until { task() == null }
    film.roll("manage-switch", max = 20)
    // Every frame from here shows a whole section, and the address it shows in use is the one the app uses.
    var torn = 0
    fun look() {
        val p = settings.facts?.state?.value
        if (p == null || p.origin != home.server?.base?.removeSuffix("/api")) torn++
    }
    val feed = checkNotNull(home.model) { "no realm open" }
    settings.use("https://notes.jo.dev")
    film.roll("manage-switch", max = 400) {
        look()
        settings.facts?.state?.value?.origin == "https://notes.jo.dev"
    }
    film.roll("manage-switch", max = 30) { false.also { look() } }
    check(home.model === feed) { "switching the address started the open realm's feed over" }

    settings.remove("https://realm.jo.workers.dev")
    film.roll("manage-workersdev", max = 30)
    settings.confirmRemove(agree = true)
    film.roll("manage-workersdev", max = 300) { task() == null }
    film.roll("manage-workersdev", max = 30)
    settings.turnOnWorkersDev()
    film.roll("manage-workersdev", max = 300) { task() == null }
    film.roll("manage-workersdev", max = 30)

    // Taking away the address in use moves the app to another first, in the same frame as the section.
    settings.remove("https://notes.jo.dev")
    film.roll("manage-move", max = 30) { false.also { look() } }
    settings.confirmRemove(agree = true)
    film.roll("manage-move", max = 400) {
        look()
        task() == null
    }
    film.roll("manage-move", max = 30) { false.also { look() } }
    film.close()
    check(torn == 0) { "$torn frames showed no section, or another address in use than the app's" }
    check(home.server?.base == "https://realm.jo.workers.dev/api") { "the app stayed at ${home.server?.base}" }
    check(settings.facts!!.state.value!!.addresses!!.domains.isEmpty()) { "notes.jo.dev is still there" }
    println("signed in to ${home.server?.base}")

    // manage-token: signed in with a fixed owner token, there's no Cloudflare login to manage the server with.
    val cloud = FakeCloud(worker = """{"version":2,"scope":"owner"}""")
    val token =
        Home(FakePlatform("server" to "https://realm.jo.workers.dev", "token" to "t"), CoroutineScope(Ui), cloud.engine)
    Film(token, settings = true).apply {
        until { token.serverSettings.facts?.state is Loaded.Known && token.serverStatus is ServerStatus.Behind }
        still("manage-token")
    }.close()
    println("${out.path}/manage-*.png")
    steadySettings()
}

/**
 * settings-cold-N: the run after signing in, with nothing kept about the server, settings open from the start:
 * blank where the facts go, then what's fetched fading in; it comes within the delay, so no skeleton shows.
 * settings-slow-N: the same over a slow network: the skeleton fades in once the delay is up, then the facts
 * fade in over it. settings-warm-N: the same with what the last run fetched kept: it shows at once, and the
 * refresh changes nothing on screen. settings-first-N and settings-first-slow-N: the first run, with nothing
 * kept about the server or the releases either. settings-token-first-N: the same signed in with a fixed token to
 * a worker an update is out for. Checks settings keeps one height in every frame of each.
 */
private fun steadySettings() {
    val cloud = FakeCloud(worker = """{"version":${Api.VERSION},"scope":"owner"}""")
    val behind = FakeCloud(worker = """{"version":${Api.VERSION - 1},"scope":"owner"}""")
    val first = FakePlatform()
    val home = Home(first, CoroutineScope(Ui), cloud.engine)
    home.cloudflare.logIn()
    Film(home).apply { until { home.serverSettings.facts?.state is Loaded.Known && home.releases.known != null } }.close()
    val kept = first.kept()
    check(kept.any { it.first.startsWith("server-facts/") }) { "signing in kept nothing about the server" }
    check(kept.any { it.first == "releases" }) { "signing in kept no releases" }
    val cold = kept.filter { !it.first.startsWith("server-facts/") }
    val none = cold.filter { it.first != "releases" }
    val token = listOf("server" to "https://realm.jo.workers.dev", "token" to "t")
    for ((name, prefs, slowness, at) in listOf(
        SettingsRun("settings-cold", cold, 0L, cloud),
        SettingsRun("settings-slow", cold, 300L, cloud),
        SettingsRun("settings-warm", kept, 0L, cloud),
        SettingsRun("settings-first", none, 0L, cloud),
        SettingsRun("settings-first-slow", none, 300L, cloud),
        SettingsRun("settings-token-first", token, 0L, behind),
    )) {
        at.slowness = slowness
        val next = Home(FakePlatform(*prefs.toTypedArray()), CoroutineScope(Ui), at.engine)
        val film = Film(next, settings = true)
        film.roll(name, every = 2, max = 200) { next.serverSettings.facts?.state is Loaded.Known && next.serverStatus != null }
        val known = film.indicated.size
        film.roll(name, every = 2, max = 40)
        film.close()
        val heights = film.settingsHeights.filter { it > 0 }
        println("$name: ${heights.size} frames, settings heights ${heights.distinct()} px")
        check(heights.size > 30 && heights.distinct().size == 1) { "$name: settings changed height: $heights" }
        check(next.serverStatus != null) { "$name: the server's update status never arrived" }
        film.checkIndicator(name, 0, known)
        if (slowness == 0L && name != "settings-warm") {
            check(known * 16 < INDICATOR_DELAY_MILLIS) { "$name took ${known * 16}ms: no test of a fast one" }
        }
        if (slowness > 0L) check(film.indicated.any { it }) { "$name: the skeleton never showed" }
        if (name == "settings-warm") check(known == 1 && film.indicated.none { it }) { "settings-warm: wasn't known at once" }
        if (name == "settings-token-first") check(next.serverStatus is ServerStatus.Behind) { "$name: no update was out" }
    }
    cloud.slowness = 0
    println("${out.path}/settings-*.png")
    keeping()
}

private data class SettingsRun(val name: String, val prefs: List<Pair<String, String>>, val slowness: Long, val cloud: FakeCloud)
