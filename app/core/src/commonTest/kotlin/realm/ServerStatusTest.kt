package realm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerStatusTest {
    private val v = Api.VERSION

    private fun listed(tag: Semver, api: Int?) =
        Listed(tag, mapOf("worker.js" to "https://github.com/$tag/worker.js"), api?.let { MANIFEST.copy(version = it) })

    private fun releases(vararg list: Pair<Semver, Int?>) = Releases(list.map { (tag, api) -> listed(tag, api) })

    private fun runs(build: String?, version: Int = v) = Info(version = version, scope = "owner", build = build)

    private val v110 = Semver(1, 1, 0)
    private val v100 = Semver(1, 0, 0)

    @Test
    fun aWorkerOnAnOlderReleaseOfTheSameApiIsBehind() {
        assertEquals(ServerStatus.Behind(v110), serverStatus(runs("v1.0.0"), releases(v110 to v, v100 to v)))
        assertEquals(ServerStatus.Current, serverStatus(runs("v1.1.0"), releases(v110 to v, v100 to v)))
    }

    @Test
    fun aWorkerOnACandidateIsBehindTheReleaseItLeadsUpTo() {
        assertEquals(ServerStatus.Behind(v110), serverStatus(runs("v1.1.0-rc.2"), releases(v110 to v)))
        assertEquals(ServerStatus.Current, serverStatus(runs("v1.2.0-rc.1"), releases(v110 to v)))
    }

    @Test
    fun aWorkerThatSaysSomethingOtherThanAReleaseIsBehind() {
        for (build in listOf("v1.1.0-test", "1.1.0", "main")) {
            assertEquals(ServerStatus.Behind(v110), serverStatus(runs(build), releases(v110 to v)), build)
        }
    }

    @Test
    fun aWorkerThatDoesNotSayItsReleaseIsBehind() {
        assertEquals(ServerStatus.Behind(v110), serverStatus(runs(null), releases(v110 to v)))
        assertEquals(ServerStatus.Behind(v110), serverStatus(runs(null, version = v - 1), releases(v110 to v)))
    }

    @Test
    fun aReleaseThatNeedsANewerAppAsksForTheAppFirst() {
        val v200 = Semver(2, 0, 0)
        val list = releases(v200 to v + 1, Semver(1, 5, 0) to null, v110 to v)
        assertEquals(ServerStatus.AppFirst(v200), serverStatus(runs("v1.1.0"), list))
        assertEquals(ServerStatus.Behind(v110), serverStatus(runs("v1.0.0"), list))
        assertEquals(ServerStatus.AppFirst(v200), serverStatus(runs(null), releases(v200 to v + 1)))
    }

    @Test
    fun whatTheAppCannotUpdateSaysWhy() {
        val list = releases(v110 to v)
        assertEquals(ServerStatus.NewerThanApp, serverStatus(runs("v2.0.0", version = v + 1), list))
        assertEquals(
            ServerStatus.OtherFork("someone/realm"),
            serverStatus(Info(fork = "someone/realm", version = v, scope = "owner"), list),
        )
        assertEquals(ServerStatus.NewerThanApp, serverStatus(runs(null, version = v + 1), null))
        assertNull(serverStatus(runs("v1.0.0"), null))
    }

    @Test
    fun aWarningSaysTheServerNeedsUpdatingOnlyWhenAnUpdateIsOut() {
        val older = runs("v1.0.0", version = v - 1)
        assertEquals("That server needs updating: update it in Settings.", warning(older, serverStatus(older, releases(v110 to v))))
        assertEquals(
            "That server is older than this app. Some things may not work.",
            warning(older, serverStatus(older, releases(v100 to v - 1))),
        )
        assertEquals("That server is older than this app. Some things may not work.", warning(older, null))
        assertNull(warning(runs("v1.0.0"), serverStatus(runs("v1.0.0"), releases(v110 to v))))
        assertEquals("That server is newer than this app. Update the app.", warning(runs(null, version = v + 1), null))
    }

    @Test
    fun theWorkerRunsItsReleaseOrItsApiVersionInWords() {
        assertEquals("realm v1.0.0", runs("v1.0.0").running)
        assertEquals("realm v1.1.0-rc.2", runs("v1.1.0-rc.2").running)
        assertEquals("realm, an older release", runs(null).running)
        assertEquals("someone/realm, an older release", Info(fork = "someone/realm", version = 4, scope = "owner").running)
    }
}
