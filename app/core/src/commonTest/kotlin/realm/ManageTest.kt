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

    /** Jo's account as recorded, with the realm worker on [domains] and on workers.dev if [workersDev]. */
    private fun account(domains: String = "[]", workersDev: Boolean = true) = FakeClouds { route, seen ->
        when {
            route == "GET $CF/accounts/a1/workers/domains" -> cf(domains)
            route == "GET $CF/accounts/a1/workers/scripts/realm/subdomain" -> if (workersDev) null else cf("""{"enabled":false}""")
            route == "DELETE $CF/accounts/a1/workers/domains/d1" -> cf("null")
            route == "POST $CF/accounts/a1/workers/scripts/realm/subdomain" -> cf(seen.text)
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
    fun anAccountIsNamedAsTheLoginSeesIt() = runTest {
        val c = FakeClouds { route, _ -> if (route == "GET $CF/accounts") cf("""[{"id":"a1","name":"Jo"}]""") else null }
        assertEquals(Account("a1", "Jo"), c.api().account("a1"))
        assertEquals(Account("a9"), c.api().account("a9"))
    }
}
