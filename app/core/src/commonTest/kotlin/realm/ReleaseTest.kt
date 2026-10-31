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

    private fun manifest(version: Int, fork: String? = null, sha: String = MANIFEST.sha256) = """
        {${fork?.let { "\"fork\":\"$it\"," } ?: ""}"version":$version,"name":"realm","main_module":"worker.js","sha256":"$sha",
         "compatibility_date":"2026-10-01","r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],
         "d1_databases":[{"binding":"DB","database_name":"realm"}],"crons":["17 4 * * *"]}
    """

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
                "v1.10.0" to manifest(4),
                "v1.3.0" to manifest(3, fork = "someone/realm"),
                "v1.2.0" to manifest(3),
                "v1.1.0" to manifest(2),
            ),
        )
        val r = latestRelease(gh.engine)

        assertEquals("v1.2.0", r.tag.toString())
        assertEquals(Semver(1, 10, 0), r.newer) // needs API version 4: the app should be updated
        assertEquals(MANIFEST, r.manifest)
        assertContentEquals("abc".encodeToByteArray(), r.code)
        val fetched = gh.routes.filter { "/download/" in it }
        assertTrue(fetched.none { "rc" in it || "v1.4.0" in it || "nightly" in it }, fetched.toString())
    }

    @Test
    fun onlyReleasesNeedingANewerAppSaySo() = runTest {
        val gh = github(listOf(release("v2.0.0")), mapOf("v2.0.0" to manifest(4)))
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
    fun readsOnlyPlainVersionTags() {
        assertEquals(Semver(1, 2, 3), Semver.parse("v1.2.3"))
        for (bad in listOf("1.2.3", "v1.2", "v1.2.3-rc.1", "v1.2.3-test", "release")) assertNull(Semver.parse(bad), bad)
    }
}
