package realm

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DomainsTest {
    private val zone = Zone("z1", "jo.dev")
    private val aRecord = """{"id":"r1","type":"A","name":"notes.jo.dev","content":"192.0.2.1"}"""
    private val attached = """{"id":"d1","hostname":"notes.jo.dev","zone_id":"z1","zone_name":"jo.dev","service":"realm"}"""

    /** A zone where notes.jo.dev has [records] until they're deleted; attaching works once there are none. */
    private fun zone(vararg records: String) = run {
        val left = records.toMutableList()
        FakeClouds { route, _ ->
            when {
                route == "PUT $CF/accounts/a1/workers/domains" ->
                    if (left.isEmpty()) cf(attached) else cfError(HttpStatusCode.Conflict, 100117, "Hostname already has DNS records")

                route == "GET $CF/zones/z1/dns_records" -> cf("[${left.joinToString(",")}]")

                route.startsWith("DELETE $CF/zones/z1/dns_records/") -> {
                    val id = route.substringAfterLast('/')
                    left.removeAll { "\"id\":\"$id\"" in it }
                    cf("""{"id":"$id"}""")
                }

                else -> null
            }
        }
    }

    @Test
    fun recordsInTheWayAreShownAndLeftAloneWithoutConsent() = runTest {
        val c = zone(aRecord)
        val out = c.api().attachDomain(ACCOUNT, DomainChoice(zone, "notes"))

        val conflict = assertIs<DomainOutcome.Conflict>(out)
        assertEquals(listOf(DnsRecord("r1", "A", "notes.jo.dev", "192.0.2.1")), conflict.records)
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
        val body = Json.parseToJsonElement(c.seen.first().text).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("hostname" to "notes.jo.dev", "service" to "realm", "zone_id" to "z1", "environment" to "production"), body)
        assertEquals("notes.jo.dev", c.seen.single { it.route.endsWith("/dns_records") }.url.parameters["name"])
    }

    @Test
    fun withConsentTheSeenRecordsAreReplaced() = runTest {
        val c = zone(aRecord)
        val api = c.api()
        val conflict = api.attachDomain(ACCOUNT, DomainChoice(zone, "notes")) as DomainOutcome.Conflict
        val out = api.attachDomain(ACCOUNT, DomainChoice(zone, "notes"), replace = conflict)

        assertEquals("notes.jo.dev", assertIs<DomainOutcome.Attached>(out).domain.hostname)
        assertEquals(listOf("DELETE cf/zones/z1/dns_records/r1"), c.routes.filter { it.startsWith("DELETE") })
    }

    @Test
    fun consentCoversOnlyTheRecordsTheUserSaw() = runTest {
        val c = zone(aRecord, """{"id":"r2","type":"AAAA","name":"notes.jo.dev","content":"2001:db8::1"}""")
        val seenEarlier = DomainOutcome.Conflict(DomainChoice(zone, "notes"), listOf(DnsRecord("r1", "A", "notes.jo.dev")))
        val out = c.api().attachDomain(ACCOUNT, DomainChoice(zone, "notes"), replace = seenEarlier)

        assertEquals(listOf("r1", "r2"), assertIs<DomainOutcome.Conflict>(out).records.map { it.id })
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
    }

    @Test
    fun aConflictAtDeployLeavesTheWorkerOnWorkersDev() = runTest {
        val c = FakeClouds { route, _ ->
            when (route) {
                "PUT $CF/accounts/a1/workers/domains" -> cfError(HttpStatusCode.Conflict, 100117, "Hostname already has DNS records")
                "GET $CF/zones/z1/dns_records" -> cf("[$aRecord]")
                "GET $CF/accounts/a1/d1/database" -> cf("[]")
                "POST $CF/accounts/a1/d1/database" -> cf("""{"uuid":"u","name":"realm"}""")
                "GET realm.jo.workers.dev/api/info" -> json(OWNER_INFO)
                else -> if (route.startsWith("GET")) null else cf("{}")
            }
        }
        val done = c.api().deploy(Plan.Fresh(RELEASE, found()), Choices(domain = DomainChoice(zone, "notes")))

        assertIs<DomainOutcome.Conflict>(done.domain)
        assertEquals("https://realm.jo.workers.dev", done.origin)
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
    }

    /** Jo's account as recorded, its realm worker on [domains] and its workers.dev address [workersDev]. */
    private fun worker(domains: String, workersDev: Boolean) = FakeClouds { route, _ ->
        when (route) {
            "GET $CF/accounts/a1/workers/domains" -> cf(domains)

            "GET $CF/accounts/a1/workers/scripts/realm/subdomain" ->
                if (workersDev) null else cf("""{"enabled":false,"previews_enabled":true}""")

            else -> null
        }
    }

    @Test
    fun aWorkerIsFoundAtItsCustomDomainFirst() = runTest {
        val c = worker("[$attached]", workersDev = true)
        assertEquals("https://notes.jo.dev", assertIs<RealmOn.Running>(c.api().realmOn(ACCOUNT)).worker.origin)
        assertEquals("realm", c.seen.single { it.route.endsWith("/workers/domains") }.url.parameters["service"])
    }

    @Test
    fun aWorkerWithoutACustomDomainIsFoundOnWorkersDev() = runTest {
        // Cloudflare's own replies throughout, null errors on the custom domains and all.
        val w = assertIs<RealmOn.Running>(FakeClouds { _, _ -> null }.api().realmOn(ACCOUNT)).worker
        assertEquals("https://realm.jo.workers.dev", w.origin)
        assertEquals("Jo's account", w.accountName)
    }

    @Test
    fun aWorkerWithNoAddressSaysSo() = runTest {
        val off = assertIs<RealmOn.NoAddress>(worker("[]", workersDev = false).api().realmOn(ACCOUNT))
        assertTrue("workers.dev" in off.why, off.why)
    }

    @Test
    fun cloudflareRefusalsKeepTheirCodes() = runTest {
        val c = FakeClouds { _, _ -> cfError(HttpStatusCode.Conflict, 10004, "The bucket you tried to create already exists") }
        val e = assertFailsWith<CloudflareError> { c.api().accounts() }
        assertEquals(listOf(10004), e.codes)
        assertEquals(409, e.status)
        assertEquals("The bucket you tried to create already exists", e.message)
    }

    @Test
    fun aReplyThatDoesNotParseKeepsWhatDidNot() = runTest {
        val c = FakeClouds { _, _ -> json("""{"success":true,"errors":"none","messages":[],"result":[]}""") }
        val e = assertFailsWith<ApiException> { c.api().accounts() }
        assertEquals("Cloudflare sent something odd (HTTP 200)", e.message)
        assertTrue("errors" in e.cause?.message.orEmpty(), e.cause?.message)
    }

    @Test
    fun onlyActiveDomainsAreOffered() = runTest {
        val c = FakeClouds { route, _ ->
            val zones = """[{"id":"z1","name":"jo.dev","status":"active"},{"id":"z2","name":"new.dev","status":"pending"}]"""
            if (route == "GET $CF/zones") cf(zones) else null
        }
        assertEquals(listOf("jo.dev"), c.api().zones(ACCOUNT).map { it.name })
    }
}
