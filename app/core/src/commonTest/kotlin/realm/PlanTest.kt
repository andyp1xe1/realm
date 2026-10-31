package realm

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlanTest {
    private val oldOnly = """[{"uuid":"u1","name":"realm-old"}]"""

    /** An account with R2 bucket "realm", D1 databases whose names only contain "realm", and [script]. */
    private fun account(script: Boolean, info: () -> String? = { null }) = FakeClouds { route, seen ->
        when (route) {
            "GET $CF/accounts/a1/workers/subdomain" -> cf("""{"subdomain":"jo"}""")

            "GET $CF/accounts/a1/workers/domains" -> cf("[]")

            "GET $CF/accounts/a1/workers/scripts" -> cf(if (script) """[{"id":"other"},{"id":"realm"}]""" else """[{"id":"other"}]""")

            "GET $CF/accounts/a1/workers/scripts/realm/subdomain" -> cf("""{"enabled":true,"previews_enabled":false}""")

            "GET $CF/accounts/a1/r2/buckets" -> cf("""{"buckets":[{"name":"photos"},{"name":"realm"}]}""")

            "GET $CF/accounts/a1/d1/database" -> cf(if (seen.url.parameters["name"] == "realm") oldOnly else "[]")

            "GET $CF/zones" -> cf("""[{"id":"z1","name":"jo.dev","status":"active"},{"id":"z2","name":"new.dev","status":"pending"}]""")

            "GET realm.jo.workers.dev/api/info" ->
                info()?.let { json(it) } ?: json("""{"error":"bad token"}""", HttpStatusCode.Unauthorized)

            else -> null
        }
    }

    @Test
    fun anAccountWithoutTheWorkerGetsAFreshDeploy() = runTest {
        val plan = account(script = false).api().plan(ACCOUNT, RELEASE)

        assertIs<Plan.Fresh>(plan)
        // The bucket is there; "realm-old" isn't the database "realm".
        assertEquals(listOf(Resource.Database("realm"), Resource.Script("realm")), plan.create)
        assertEquals(listOf(Resource.Bucket("realm")), plan.reuse)
        assertEquals("jo", plan.found.subdomain)
        assertEquals(listOf("jo.dev"), plan.found.zones.map { it.name })
    }

    @Test
    fun anOlderWorkerOfTheSameForkIsUpdated() = runTest {
        val clouds = account(script = true) { """{"version":2,"scope":"owner"}""" }
        val plan = clouds.api().plan(ACCOUNT, RELEASE)

        assertIs<Plan.Update>(plan)
        assertEquals(2, plan.from.version)
        assertEquals(Resource.Script("realm"), plan.reuse.last())
        // Asked over HTTPS at its address, with the Cloudflare login as the owner's token.
        val asked = clouds.seen.single { it.route == "GET realm.jo.workers.dev/api/info" }
        assertEquals("https", asked.url.protocol.name)
        assertEquals("Bearer cf-token", asked.headers["Authorization"])
    }

    @Test
    fun aWorkerThatCannotSayWhatItRunsIsLeftAlone() = runTest {
        val plan = account(script = true).api().plan(ACCOUNT, RELEASE)
        assertIs<Refusal.Unknown>(assertIs<Plan.Refused>(plan).reason)
    }

    @Test
    fun neverDowngradesOrOverwritesAFork() {
        fun refusal(info: Info) = assertIs<Plan.Refused>(decide(RELEASE, found(Existing.Answered("https://x", info)))).reason

        assertIs<Refusal.Newer>(refusal(Info(version = 4, scope = "owner")))
        assertIs<Refusal.OtherFork>(refusal(Info(fork = "someone/realm", version = 2, scope = "owner")))
        assertIs<Refusal.Current>(refusal(Info(version = 3, scope = "owner")))
        assertIs<Refusal.Unknown>(assertIs<Plan.Refused>(decide(RELEASE, found(Existing.Silent(null, "no address")))).reason)
        assertIs<Plan.Update>(decide(RELEASE, found(Existing.Answered("https://x", Info(version = 0, scope = "owner")))))
    }
}
