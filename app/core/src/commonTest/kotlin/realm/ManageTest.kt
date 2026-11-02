package realm

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManageTest {
    private val notes = """{"id":"d1","hostname":"notes.jo.dev","zone_id":"z1","zone_name":"jo.dev","service":"realm"}"""
    private val dl = "github.com/andyp1xe1/realm/releases/download"

    /**
     * Jo's account as recorded, with the realm worker at version [worker], on [domains] and on workers.dev if [workersDev],
     * and GitHub with releases of the given API versions, newest first.
     */
    private fun account(worker: Int = 2, domains: String = "[]", workersDev: Boolean = true, vararg releases: Pair<String, Int>) =
        FakeClouds { route, seen ->
            when {
                route == "GET $CF/accounts/a1/workers/domains" -> cf(domains)

                route == "GET $CF/accounts/a1/workers/scripts/realm/subdomain" -> if (workersDev) null else cf("""{"enabled":false}""")

                route == "DELETE $CF/accounts/a1/workers/domains/d1" -> cf("null")

                route == "POST $CF/accounts/a1/workers/scripts/realm/subdomain" -> cf(seen.text)

                route.endsWith("/api/info") -> json("""{"version":$worker,"scope":"owner"}""")

                route == "GET api.github.com/repos/andyp1xe1/realm/releases" -> json(
                    releases.joinToString(",", "[", "]") { (tag, _) ->
                        """{"tag_name":"$tag","assets":[{"name":"worker.json","browser_download_url":"https://$dl/$tag/worker.json"},
                           {"name":"worker.js","browser_download_url":"https://$dl/$tag/worker.js"}]}"""
                    },
                )

                route.endsWith("/worker.json") -> {
                    val version = releases.first { it.first == route.split('/').dropLast(1).last() }.second
                    json(cloudflareJson.encodeToString(Manifest.serializer(), MANIFEST.copy(version = version)))
                }

                route.endsWith("/worker.js") -> respond("abc", HttpStatusCode.OK)

                else -> null
            }
        }

    @Test
    fun listsEveryAddressWorkersDevFirst() = runTest {
        val a = account(domains = "[$notes]").api().addresses(ACCOUNT)

        assertEquals(listOf("https://realm.jo.workers.dev", "https://notes.jo.dev"), a.origins)
        assertEquals(listOf("jo.blog", "jo.dev"), a.zones.map { it.name })
    }

    @Test
    fun aDomainComesOffWhileWorkersDevStays() = runTest {
        val c = account(domains = "[$notes]")
        c.api().removeDomain(ACCOUNT, Domain("d1", "notes.jo.dev", "z1"))
        assertTrue("DELETE cf/accounts/a1/workers/domains/d1" in c.routes, c.routes.toString())
    }

    @Test
    fun theLastAddressStays() = runTest {
        val c = account(domains = "[$notes]", workersDev = false)
        val e = assertFailsWith<LastAddress> { c.api().removeDomain(ACCOUNT, Domain("d1", "notes.jo.dev", "z1")) }
        assertEquals("notes.jo.dev", e.hostname)
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
    }

    @Test
    fun workersDevIsKnownWhileItIsOff() = runTest {
        val a = account(domains = "[$notes]", workersDev = false).api().addresses(ACCOUNT)

        assertEquals("https://realm.jo.workers.dev", a.workersDev)
        assertEquals(listOf("https://notes.jo.dev"), a.origins)
    }

    @Test
    fun workersDevTurnsOffWhileADomainStays() = runTest {
        val c = account(domains = "[$notes]")
        val api = c.api()
        api.takeOff(api.addresses(ACCOUNT), "https://realm.jo.workers.dev")

        val off = c.seen.single { it.route == "POST $CF/accounts/a1/workers/scripts/realm/subdomain" }
        assertEquals("""{"enabled":false,"previews_enabled":false}""", off.text)
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
    }

    @Test
    fun workersDevStaysWhenItIsTheLastAddress() = runTest {
        val c = account()
        val e = assertFailsWith<LastAddress> { c.api().setWorkersDev(ACCOUNT, on = false) }
        assertEquals("realm.jo.workers.dev", e.hostname)
        assertTrue(c.routes.none { it.startsWith("POST") }, c.routes.toString())
    }

    @Test
    fun workersDevTurnsOn() = runTest {
        val c = account(domains = "[$notes]", workersDev = false)
        c.api().setWorkersDev(ACCOUNT, on = true)
        assertEquals("""{"enabled":true,"previews_enabled":false}""", c.seen.single { it.route.startsWith("POST") }.text)
    }

    @Test
    fun aDomainIsTakenOffByItsAddress() = runTest {
        val c = account(domains = "[$notes]")
        val api = c.api()
        api.takeOff(api.addresses(ACCOUNT), "https://notes.jo.dev")
        assertEquals(listOf("DELETE cf/accounts/a1/workers/domains/d1"), c.routes.filter { !it.startsWith("GET") })
    }

    @Test
    fun anOlderWorkerHasAnUpdate() = runTest {
        val check = account(worker = 2, releases = arrayOf("v1.2.0" to 3)).api().checkUpdate(ACCOUNT)
        assertEquals(2, assertIs<UpdateCheck.Available>(check).plan.from.version)
    }

    @Test
    fun theSameVersionIsUpToDate() = runTest {
        val check = account(worker = 3, releases = arrayOf("v1.2.0" to 3)).api().checkUpdate(ACCOUNT)
        assertEquals(Semver(1, 2, 0), assertIs<UpdateCheck.UpToDate>(check).release.tag)
    }

    @Test
    fun aNewerReleaseThisAppCannotDeploySaysToUpdateTheApp() = runTest {
        val current = account(worker = 3, releases = arrayOf("v2.0.0" to 4, "v1.2.0" to 3)).api().checkUpdate(ACCOUNT)
        assertEquals(Semver(2, 0, 0), assertIs<UpdateCheck.NeedsNewerApp>(current).newer)

        val none = account(worker = 3, releases = arrayOf("v2.0.0" to 4)).api().checkUpdate(ACCOUNT)
        assertEquals(Semver(2, 0, 0), assertIs<UpdateCheck.NeedsNewerApp>(none).newer)
    }

    @Test
    fun aNewerWorkerIsLeftAlone() = runTest {
        val check = account(worker = 4, releases = arrayOf("v1.2.0" to 3)).api().checkUpdate(ACCOUNT)
        assertIs<Refusal.Newer>(assertIs<UpdateCheck.Refused>(check).plan.reason)
    }

    @Test
    fun anAccountIsNamedAsTheLoginSeesIt() = runTest {
        val c = FakeClouds { route, _ -> if (route == "GET $CF/accounts") cf("""[{"id":"a1","name":"Jo"}]""") else null }
        assertEquals(Account("a1", "Jo"), c.api().account("a1"))
        assertEquals(Account("a9"), c.api().account("a9"))
    }

    @Test
    fun onlyAnOlderWorkerOfThisForkIsOfferedAnUpdate() {
        assertTrue(offersUpdate(Info(version = 2, scope = "owner"), 3))
        assertTrue(!offersUpdate(Info(version = 3, scope = "owner"), 3))
        assertTrue(!offersUpdate(Info(version = 4, scope = "owner"), 3))
        assertTrue(!offersUpdate(Info(fork = "someone/realm", version = 2, scope = "owner"), 3))
    }
}
