package realm

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequirementsTest {
    private val gh = "api.github.com/repos/andyp1xe1/realm/releases"
    private val dl = "github.com/andyp1xe1/realm/releases/download/v0.1.0"
    private val manifest = """
        {"version":3,"name":"realm","main_module":"worker.js","sha256":"${MANIFEST.sha256}","compatibility_date":"2026-10-01",
         "r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],"d1_databases":[{"binding":"DB","database_name":"realm"}]}
    """

    /**
     * Jo's account as Cloudflare recorded it, but with no realm worker yet unless [deployed], and GitHub's
     * recorded release v0.1.0. [override] answers what a test changes first.
     */
    private fun account(
        deployed: Boolean = false,
        override: MockRequestHandleScope.(route: String, seen: Seen) -> HttpResponseData? = { _, _ -> null },
    ) = FakeClouds { route, seen ->
        override(route, seen) ?: when (route) {
            "GET $CF/accounts/a1/workers/scripts" -> if (deployed) null else cf("""[{"id":"notes"}]""")
            "GET $gh" -> json(RecordedGitHub.RELEASES)
            "GET $dl/worker.json" -> asset(manifest)
            "GET $dl/worker.js" -> asset("abc")
            else -> null
        }
    }

    private fun MockRequestHandleScope.asset(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))

    /** [count] D1 databases, none of them realm's, as the list and the name filter find them. */
    private fun MockRequestHandleScope.databases(route: String, seen: Seen, count: Int) = if (route != "GET $CF/accounts/a1/d1/database") {
        null
    } else if (seen.url.parameters["name"] != null) {
        cf("""[{"uuid":"o","name":"realm-old"}]""") // the filter matches parts of names
    } else {
        cf((1..count).joinToString(",", "[", "]") { """{"uuid":"u$it","name":"db$it"}""" })
    }

    @Test
    fun jossAccountHasEverythingAndDeploysReusingItsStorage() = runTest {
        val r = account().api().requirements(ACCOUNT)

        assertFalse(r.deployed)
        assertEquals(R2Check.On, r.r2)
        assertEquals(SubdomainCheck.Has("jo"), r.subdomain)
        assertEquals(DatabaseCheck.Reuses("realm"), r.database)
        assertEquals(Semver(0, 1, 0), assertIs<ReleaseCheck.Ready>(r.release).release.tag)
        assertEquals(listOf("jo.blog", "jo.dev"), r.zones.map { it.name })

        val d = assertNotNull(r.deployment(Choices()))
        assertEquals(listOf(Resource.Script("realm")), d.plan.create) // the bucket and database are there
        assertEquals("jo", d.plan.found.subdomain)
        val notes = assertNotNull(r.deployment(Choices(domain = DomainChoice(r.zones[1], "notes"))))
        assertEquals("notes.jo.dev", notes.choices.domain?.hostname)
        assertNotNull(r.deployment(Choices(domain = DomainChoice(r.zones[1], " ")))) // blank: jo.dev itself
        assertNull(r.deployment(Choices(domain = DomainChoice(r.zones[1], "my notes"))))
    }

    @Test
    fun anAccountWithRealmOnItAlreadyIsNotDeployedTo() = runTest {
        val r = account(deployed = true).api().requirements(ACCOUNT)
        assertTrue(r.deployed)
        assertNull(r.deployment(Choices()))
    }

    @Test
    fun r2TurnedOffStopsTheDeploy() = runTest {
        val r = account { route, _ ->
            if (route == "GET $CF/accounts/a1/r2/buckets") json(Recorded.R2_OFF, HttpStatusCode.Forbidden) else null
        }.api().requirements(ACCOUNT)

        assertEquals(R2Check.Off, r.r2)
        assertNull(r.deployment(Choices()))
    }

    @Test
    fun tenDatabasesOnTheFreePlanLeaveNoRoom() = runTest {
        val full = account { route, seen -> databases(route, seen, 10) }.api().requirements(ACCOUNT)
        assertEquals(DatabaseCheck.Full(10), full.database)
        assertNull(full.deployment(Choices()))

        val nine = account { route, seen -> databases(route, seen, 9) }.api().requirements(ACCOUNT)
        assertEquals(DatabaseCheck.Room(9), nine.database)
        assertEquals(Resource.Database("realm"), assertNotNull(nine.deployment(Choices())).plan.create.first())
    }

    @Test
    fun anAccountWithoutWorkersDevDeploysOnlyOnceAValidSubdomainIsTyped() = runTest {
        val r = account { route, _ ->
            if (route == "GET $CF/accounts/a1/workers/subdomain") json(Recorded.NO_SUBDOMAIN, HttpStatusCode.NotFound) else null
        }.api().requirements(ACCOUNT)

        assertEquals(SubdomainCheck.Missing, r.subdomain)
        assertNull(r.deployment(Choices()))
        for (bad in listOf("", "-jo", "jo-", "jo.dev", "jo_dev", "a".repeat(64))) assertNull(r.deployment(Choices(subdomain = bad)), bad)
        assertEquals("jo-dev", r.deployment(Choices(subdomain = " Jo-Dev "))?.choices?.subdomain)
    }

    @Test
    fun noReleaseToDeploySaysWhy() = runTest {
        suspend fun release(releases: MockRequestHandleScope.() -> HttpResponseData) = account { route, _ ->
            if (route == "GET $gh") releases() else null
        }.api().requirements(ACCOUNT).also { assertNull(it.deployment(Choices())) }.release

        assertEquals(ReleaseCheck.None, release { json("[]") })
        val v2 = """[{"tag_name":"v2.0.0","assets":[{"name":"worker.json","browser_download_url":"https://$dl/worker.json"}]}]"""
        val newer = manifest.replace("\"version\":3", "\"version\":${Api.VERSION + 1}")
        val needsNewer = account { route, _ ->
            when (route) {
                "GET $gh" -> json(v2)
                "GET $dl/worker.json" -> asset(newer)
                else -> null
            }
        }.api().requirements(ACCOUNT).release
        assertEquals(ReleaseCheck.NeedsNewerApp(Semver(2, 0, 0)), needsNewer)
        assertIs<ReleaseCheck.Failed>(release { json("""{"message":"rate limited"}""", HttpStatusCode.Forbidden) })
    }
}
