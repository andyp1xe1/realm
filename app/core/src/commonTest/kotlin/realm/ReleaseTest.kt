package realm

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private val gh = "api.github.com/repos/andyp1xe1/realm/releases"
    private val dl = "github.com/andyp1xe1/realm/releases/download"

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[
          {"name":"worker.js","browser_download_url":"https://$dl/$tag/worker.js"},
          {"name":"worker.json","browser_download_url":"https://$dl/$tag/worker.json"}]}
    """

    private fun manifest(version: Int, fork: String? = null, sha: String = MANIFEST.sha256, build: String? = null): String {
        val named = listOfNotNull(fork?.let { "\"fork\":\"$it\"," }, build?.let { "\"build\":\"$it\"," }).joinToString("")
        return """
            {$named"version":$version,"name":"realm","main_module":"worker.js","sha256":"$sha",
             "compatibility_date":"2026-10-01","r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],
             "d1_databases":[{"binding":"DB","database_name":"realm"}],"crons":["17 4 * * *"]}
        """
    }

    /** GitHub with [releases], each tag's worker.json from [manifests] and worker.js "abc". */
    private fun github(releases: List<String>, manifests: Map<String, String>) = FakeClouds { route, _ ->
        when {
            route == "GET $gh" -> json("[${releases.joinToString(",")}]")
            route.endsWith("/worker.json") -> manifests[route.split('/').dropLast(1).last()]?.let { asset(it) }
            route.endsWith("/worker.js") -> asset("abc")
            else -> null
        }
    }

    private fun MockRequestHandleScope.asset(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))

    @Test
    fun picksTheNewestReleaseThisAppUnderstands() = runTest {
        val gh = github(
            listOf(
                release("v1.4.0-rc.1", prerelease = true),
                release("v1.4.0", draft = true),
                release("nightly"),
                release("v1.3.0"),
                release("v1.2.0"),
                release("v1.10.0"),
                release("v1.1.0"),
            ),
            mapOf(
                "v1.4.0-rc.1" to manifest(3),
                "v1.4.0" to manifest(3),
                "nightly" to manifest(3),
                "v1.10.0" to manifest(Api.VERSION + 1),
                "v1.3.0" to manifest(3, fork = "someone/realm"),
                "v1.2.0" to manifest(3),
                "v1.1.0" to manifest(2),
            ),
        )
        val r = latestRelease(gh.engine)

        assertEquals("v1.2.0", r.tag.toString())
        assertEquals(Semver(1, 10, 0), r.newer) // needs a newer API version: the app should be updated
        assertEquals(MANIFEST, r.manifest)
        assertContentEquals("abc".encodeToByteArray(), r.code)
        val fetched = gh.routes.filter { "/download/" in it }
        assertTrue(fetched.none { "rc" in it || "v1.4.0" in it || "nightly" in it }, fetched.toString())
    }

    @Test
    fun listsReleasesDownToTheNewestThisAppCanDeploy() = runTest {
        val gh = github(
            listOf(release("v2.0.0"), release("v1.3.0"), release("v1.2.0"), release("v1.1.0")),
            mapOf(
                "v2.0.0" to manifest(Api.VERSION + 1),
                "v1.3.0" to manifest(3, fork = "someone/realm"),
                "v1.2.0" to manifest(3),
                "v1.1.0" to manifest(2),
            ),
        )
        val r = fetchReleases(gh.engine)

        assertEquals(listOf("v2.0.0", "v1.3.0", "v1.2.0"), r.workers.map { "${it.tag}" })
        assertEquals(listOf("v2.0.0", "v1.3.0", "v1.2.0", "v1.1.0"), r.apps.map { "${it.tag}" })
        assertNull(r.workers[1].worker)
        assertEquals(Semver(1, 2, 0), r.deployable?.tag)
        assertEquals(Semver(2, 0, 0), r.needsNewerApp?.tag)
        assertTrue(gh.routes.none { "v1.1.0" in it || it.endsWith("/worker.js") }, gh.routes.toString())
        assertEquals(r, cloudflareJson.decodeFromString(Releases.serializer(), cloudflareJson.encodeToString(Releases.serializer(), r)))
    }

    @Test
    fun aWorkerBuiltForAnotherTagIsNotThatReleases() = runTest {
        val gh = github(
            listOf(release("v1.3.0"), release("v1.2.0")),
            mapOf("v1.3.0" to manifest(3, build = "v1.2.9"), "v1.2.0" to manifest(3, build = "v1.2.0")),
        )
        val r = fetchReleases(gh.engine)

        assertNull(r.workers[0].worker)
        assertEquals(Semver(1, 2, 0), r.deployable?.tag)
        assertEquals("v1.2.0", r.deployable?.manifest?.build)
    }

    @Test
    fun appAndWorkerTagsReleaseOnlyTheirPartAndPlainTagsBoth() = runTest {
        val gh = github(
            listOf(
                release("app/v0.5.0"),
                release("worker/v0.4.1"),
                release("worker/v0.5.0-rc.1", prerelease = true),
                release("app/v0.4.0"),
                release("cli/v1.0.0"),
                release("v0.3.0"),
            ),
            mapOf("v0.4.1" to manifest(3, build = "v0.4.1"), "v0.3.0" to manifest(3, build = "v0.3.0")),
        )
        val r = fetchReleases(gh.engine)

        assertEquals(listOf("app/v0.5.0", "app/v0.4.0", "v0.3.0"), r.apps.map { it.name })
        assertEquals(listOf("worker/v0.4.1"), r.workers.map { it.name })
        assertEquals(Semver(0, 4, 1), r.deployable?.tag)
        assertTrue(gh.routes.none { "v0.3.0/worker" in it || "rc" in it || "cli" in it }, gh.routes.toString())
    }

    @Test
    fun listsReleasesFromTheLatestReleaseRatherThanGitHubsApi() = runTest {
        val gh = FakeClouds { route, _ ->
            when {
                route == "GET github.com/andyp1xe1/realm/releases/latest/download/releases.json" -> asset("[${release("v1.2.0")}]")
                route.endsWith("/worker.json") -> asset(manifest(3))
                else -> null
            }
        }
        assertEquals(Semver(1, 2, 0), fetchReleases(gh.engine).deployable?.tag)
        assertTrue(gh.routes.none { "api.github.com" in it }, gh.routes.toString())
    }

    @Test
    fun gitHubTurningThisNetworkAwaySaysForHowLong() = runTest {
        val until = Cloudflare.now() + 30 * 60
        val limited = FakeClouds { route, _ ->
            if (route == "GET $gh") {
                respond(
                    """{"message":"API rate limit exceeded"}""",
                    HttpStatusCode.Forbidden,
                    headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("$until")),
                )
            } else {
                null
            }
        }
        val e = assertFailsWith<GitHubBusy> { fetchReleases(limited.engine) }
        assertEquals(until, e.until)
        assertTrue("about 30 minutes" in e.message.orEmpty(), e.message)
        assertTrue("about a minute." in GitHubBusy(until, until - 20).message.orEmpty())
    }

    @Test
    fun readsGitHubsOwnReleaseList() = runTest {
        val github = FakeClouds { route, _ ->
            when {
                route == "GET $gh" -> json(RecordedGitHub.RELEASES)
                route == "GET $dl/v0.1.0/worker.json" -> asset(manifest(3))
                route == "GET $dl/v0.1.0/worker.js" -> asset("abc")
                else -> null
            }
        }
        assertEquals(Semver(0, 1, 0), latestRelease(github.engine).tag)
    }

    @Test
    fun onlyReleasesNeedingANewerAppSaySo() = runTest {
        val gh = github(listOf(release("v2.0.0")), mapOf("v2.0.0" to manifest(Api.VERSION + 1)))
        val e = assertFailsWith<NoRelease> { latestRelease(gh.engine) }
        assertEquals(Semver(2, 0, 0), e.newer)
    }

    @Test
    fun noReleasesIsNoRelease() = runTest {
        val e = assertFailsWith<NoRelease> { latestRelease(github(emptyList(), emptyMap()).engine) }
        assertNull(e.newer)
    }

    @Test
    fun aWorkerThatIsNotWhatItsManifestSaysIsRefused() = runTest {
        val gh = github(listOf(release("v1.0.0")), mapOf("v1.0.0" to manifest(3, sha = "00".repeat(32))))
        assertFailsWith<ChecksumMismatch> { latestRelease(gh.engine) }
    }

    @Test
    fun appVersionsComeInReleaseOrderWithACandidateBeforeItsRelease() {
        val order = listOf("1.1.0", "1.2.0-rc.1", "1.2.0-rc.2", "1.2.0", "1.10.0").map { AppVersion.parse(it)!! }
        assertEquals(order, order.shuffled().sorted())
    }

    @Test
    fun readsOnlyPlainVersionTags() {
        assertEquals(Semver(1, 2, 3), Semver.parse("v1.2.3"))
        for (bad in listOf("1.2.3", "v1.2", "v1.2.3-rc.1", "v1.2.3-test", "release")) assertNull(Semver.parse(bad), bad)
    }
}
