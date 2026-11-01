// Filmstrips of onboarding, logged in with Cloudflare and not signed in to a realm: choosing an account,
// checking what deploying needs, deploying again after it failed, a restart into it, and signing out.
package realm.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import realm.Home
import realm.Loaded
import realm.RealmOn
import realm.screen
import realm.value

private fun home(cloud: FakeCloud, platform: FakePlatform = FakePlatform()) =
    Home(platform, CoroutineScope(Dispatchers.Default), cloud.engine)

/** Logged in to [cloud]'s account with no realm on it, its requirements checked; then a still of it as [name]. */
private fun checked(name: String, cloud: FakeCloud): Home {
    val home = home(cloud)
    val film = Film(home)
    home.cloudflare.logIn()
    film.until { home.onboarding.requirements.state is Loaded.Known }
    film.still(name)
    film.close()
    return home
}

/**
 * onboard-cold-N: setup fades through to onboarding, its rows held by a skeleton, then checked. onboard-ready:
 * everything met, Deploy on. onboard-r2-off, onboard-d1-full: Deploy dimmed, with the dashboard to fix it.
 * onboard-subdomain-empty, -typed: no workers.dev subdomain until one is typed. onboard-retry-N: a deploy fails
 * and stays on onboarding, logged in, then Retry deploys and signs in. onboard-restart-N: the next run opens
 * on onboarding. onboard-signout-N: settings from onboarding, then signing out back to setup.
 */
internal fun onboarding() {
    val platform = FakePlatform()
    run {
        val home = home(FakeCloud(), platform)
        val film = Film(home)
        film.roll("onboard-cold", every = 2, max = 6)
        home.cloudflare.logIn()
        val skeleton = mutableListOf<Boolean>()
        film.roll("onboard-cold", every = 2, max = 300) {
            skeleton += home.screen == realm.Screen.Onboarding && home.onboarding.requirements.state == Loaded.Unknown
            home.onboarding.requirements.state is Loaded.Known
        }
        film.roll("onboard-cold", every = 2, max = 30)
        film.still("onboard-ready")
        film.close()
        check(skeleton.count { it } > 2) { "onboard-cold: the skeleton never showed" }
        check(home.onboarding.deployment != null) { "onboard-ready: Deploy isn't on" }
        film.went("onboard-cold", "Setup/-", "Setup/LoggingIn", "Onboarding/-")
    }
    check(checked("onboard-r2-off", FakeCloud(r2 = false)).onboarding.deployment == null) { "Deploy is on with R2 off" }
    check(checked("onboard-d1-full", FakeCloud(databases = 10)).onboarding.deployment == null) { "Deploy is on with no room for D1" }
    run {
        val home = home(FakeCloud(subdomain = null))
        val film = Film(home)
        home.cloudflare.logIn()
        film.until { home.onboarding.requirements.state is Loaded.Known }
        film.still("onboard-subdomain-empty")
        check(home.onboarding.deployment == null) { "Deploy is on with no workers.dev subdomain" }
        home.onboarding.subdomain = "jo"
        film.still("onboard-subdomain-typed")
        check(home.onboarding.deployment?.choices?.subdomain == "jo") { "the subdomain typed isn't deployed" }
        film.close()
    }
    run {
        val fails = FakePlatform()
        val cloud = FakeCloud(uploadFails = true)
        val home = home(cloud, fails)
        val film = Film(home)
        film.frame()
        home.cloudflare.logIn()
        film.until { home.onboarding.deployment != null }
        film.roll("onboard-retry", max = 10)
        home.onboarding.deploy()
        film.roll("onboard-retry", every = 6, max = 600) { home.onboarding.problem != null && !home.onboarding.busy }
        film.roll("onboard-retry", max = 40)
        check(home.onboarding.deployFailed) { "the failure isn't shown as one to retry" }
        check(
            Home(FakePlatform(*fails.kept().toTypedArray()), CoroutineScope(Dispatchers.Default), cloud.engine).onboarding.session != null,
        ) {
            "a failed deploy didn't keep the login"
        }
        cloud.uploadFails = false
        film.until { home.onboarding.deployment != null }
        home.onboarding.deploy()
        film.roll("onboard-retry", every = 6, max = 600) { home.server != null }
        film.roll("onboard-retry", max = 30)
        film.close()
        film.went(
            "onboard-retry",
            "Setup/-",
            "Setup/LoggingIn",
            "Onboarding/-",
            "Onboarding/Deploying",
            "Onboarding/-",
            "Onboarding/Deploying",
            "Tabs/-",
        )
    }
    run {
        // The run after onboard-cold's: the login kept, for Jo's account.
        val kept = FakePlatform(*platform.kept().toTypedArray())
        val own = realm.Remembered(kept.prefs, "cloudflare", realm.CloudflareLogin.serializer()).get()
        check(own?.account == "a1") { "the login wasn't kept for its account: $own" }
        val home = home(FakeCloud(), kept)
        val film = Film(home)
        film.roll("onboard-restart", every = 2, max = 200) { home.onboarding.requirements.state is Loaded.Known }
        film.roll("onboard-restart", every = 2, max = 30)
        film.settings = true
        film.roll("onboard-signout", max = 24)
        // Sign out, as settings' button does: it puts settings away too.
        film.settings = false
        home.signOut()
        film.roll("onboard-signout", max = 30)
        film.close()
        check(realm.Remembered(kept.prefs, "cloudflare", realm.CloudflareLogin.serializer()).get() == null) { "signing out kept the login" }
        film.went("onboard-restart", "Onboarding/-", "Setup/-")
    }
    println("${out.path}/onboard-*.png")
    choosing()
}

/**
 * chooser-N: a login with three accounts, Jo's running realm and Studio's two without, each checked in its
 * row. chooser-pick-N: choosing Studio (a2) opens onboarding for it. chooser-switch-N: signed in to Jo's,
 * settings' "Use another Cloudflare account" goes back to the chooser with the same login.
 */
private fun choosing() {
    val worker = """{"version":3,"scope":"owner"}"""
    run {
        val home = home(FakeCloud(worker = worker, accounts = 3))
        val film = Film(home)
        film.frame()
        home.cloudflare.logIn()
        val o = home.onboarding
        film.roll("chooser", every = 2, max = 300) { o.realms.size == 3 && o.realms.values.all { it.state is Loaded.Known } }
        film.roll("chooser", every = 2, max = 30)
        check(o.realms.getValue("a1").state.value is RealmOn.Running) { "Jo's realm isn't found" }
        o.choose(o.accounts.state.value!!.first { it.id == "a2" })
        film.roll("chooser-pick", every = 2, max = 300) { o.requirements.state is Loaded.Known }
        film.roll("chooser-pick", every = 2, max = 30)
        film.close()
        check(o.account?.id == "a2") { "onboarding isn't for Studio" }
        film.went("chooser", "Setup/-", "Setup/LoggingIn", "Onboarding/-")
    }
    run {
        val platform = FakePlatform()
        val home = home(FakeCloud(worker = worker, accounts = 3), platform)
        val o = home.onboarding
        home.cloudflare.logIn()
        Film(home).apply {
            until { o.realms["a1"]?.state is Loaded.Known }
            o.choose(o.accounts.state.value!!.first { it.id == "a1" })
            until { home.server != null }
        }.close()
        val film = Film(home, settings = true)
        film.roll("chooser-switch", max = 24)
        film.settings = false
        home.useAnotherAccount()
        film.roll("chooser-switch", every = 2, max = 300) { o.realms.size == 3 && o.realms.values.all { it.state is Loaded.Known } }
        film.roll("chooser-switch", every = 2, max = 30)
        film.close()
        check(o.ordered(o.accounts.state.value!!).first().id == "a1") { "the account used last isn't offered first" }
        val kept = realm.Remembered(platform.prefs, "cloudflare", realm.CloudflareLogin.serializer()).get()
        check(kept?.access == "cf" && kept.account == null) { "choosing again didn't keep the same login: $kept" }
        film.went("chooser-switch", "Tabs/-", "Onboarding/-")
    }
    println("${out.path}/chooser-*.png")
    updating()
}
