// Filmstrips of settings' server section, signed in with Cloudflare to a stand-in worker: checking for
// updates, adding a domain past a DNS record, removing one, and switching the address the app uses.
package realm.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import realm.AddressTask
import realm.Check
import realm.DomainChoice
import realm.Home
import realm.Loaded
import realm.Zone
import realm.value

/** Signed in with Cloudflare to [cloud]'s worker, with settings open. */
private fun settingsOver(cloud: FakeCloud): Pair<Home, Film> {
    val home = Home(FakePlatform(), CoroutineScope(Dispatchers.Default), cloud.engine)
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
    for ((name, worker) in listOf("manage-uptodate" to 3, "manage-update" to 2)) {
        val (home, film) = settingsOver(FakeCloud(worker = """{"version":$worker,"scope":"owner"}"""))
        val settings = home.serverSettings
        film.roll(name, max = 200) { settings.facts.state is Loaded.Known }
        film.roll(name, max = 24)
        settings.checkForUpdates()
        film.roll(name, max = 300) { settings.check is Check.Done }
        film.roll(name, max = 30)
        film.close()
    }

    val (home, film) = settingsOver(FakeCloud(worker = """{"version":3,"scope":"owner"}"""))
    val settings = home.serverSettings
    fun task() = settings.task
    film.until { settings.facts.state is Loaded.Known }
    val jo = Zone("z1", "jo.dev")

    settings.adding(true)
    film.roll("manage-add", max = 24)
    settings.addDomain(DomainChoice(jo, "realm"))
    film.roll("manage-add", max = 300) { task() is AddressTask.Conflict }
    film.roll("manage-add", max = 30)
    settings.replace(agree = true)
    film.roll("manage-add", max = 300) { task() == null }
    film.roll("manage-add", max = 30)

    val added = settings.facts.state.value!!.addresses!!.domains.single()
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
        val p = settings.facts.state.value
        if (p == null || p.origin != home.server?.base?.removeSuffix("/api")) torn++
    }
    settings.use("https://notes.jo.dev")
    film.roll("manage-switch", max = 400) {
        look()
        settings.facts.state.value?.origin == "https://notes.jo.dev"
    }
    film.roll("manage-switch", max = 30) { false.also { look() } }

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
    check(settings.facts.state.value!!.addresses!!.domains.isEmpty()) { "notes.jo.dev is still there" }
    println("signed in to ${home.server?.base}")

    // manage-token: signed in with a fixed owner token, there's no Cloudflare login to manage the server with.
    val cloud = FakeCloud(worker = """{"version":2,"scope":"owner"}""")
    val token =
        Home(FakePlatform("server" to "https://realm.jo.workers.dev", "token" to "t"), CoroutineScope(Dispatchers.Default), cloud.engine)
    Film(token, settings = true).apply {
        until { token.serverSettings.facts.state is Loaded.Known && token.cloudflare.update != null }
        still("manage-token")
    }.close()
    println("${out.path}/manage-*.png")
}
