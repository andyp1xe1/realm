package realm

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandingTest {
    private val zone = Zone("z1", "jo.dev")
    private val login = CloudflareLogin("cf-token", "r1", Cloudflare.now() + 3600)
    private val deployedSteps =
        listOf(Step.Upload(Semver(1, 0, 0)), Step.Domain("notes.jo.dev"), Step.Waiting("https://realm.jo.workers.dev"))

    /**
     * A worker just deployed on workers.dev whose custom domain notes.jo.dev has an A record in the way, until
     * it's deleted; attaching fails [failures] times first. Both addresses answer its owner.
     */
    private fun cloud(failures: Int = 0) = run {
        var record = true
        var failing = failures
        FakeClouds { route, _ ->
            when {
                route == "PUT $CF/accounts/a1/workers/domains" -> when {
                    record -> cfError(HttpStatusCode.Conflict, 100117, "Hostname already has DNS records")
                    failing-- > 0 -> cfError(HttpStatusCode.InternalServerError, 10013, "An unknown error has occurred")
                    else -> cf("""{"id":"d1","hostname":"notes.jo.dev","zone_id":"z1","zone_name":"jo.dev","service":"realm"}""")
                }

                route == "GET $CF/zones/z1/dns_records" ->
                    cf(if (record) """[{"id":"r1","type":"A","name":"notes.jo.dev","content":"192.0.2.1"}]""" else "[]")

                route == "DELETE $CF/zones/z1/dns_records/r1" -> {
                    record = false
                    cf("""{"id":"r1"}""")
                }

                route.endsWith("/api/info") -> json(OWNER_INFO)

                route.endsWith("/api/realms") -> json(TWO_REALMS)

                else -> null
            }
        }
    }

    private suspend fun FakeClouds.conflict(): Deployed {
        val conflict = api().attachDomain(ACCOUNT, DomainChoice(zone, "notes")) as DomainOutcome.Conflict
        return Deployed(ACCOUNT, "https://realm.jo.workers.dev", Info(version = 3, scope = "owner"), conflict)
    }

    private class Run(val landed: Landed, val progress: List<List<Step>>, val asked: List<ReplaceQuestion>)

    private suspend fun FakeClouds.land(plan: Plan.Ready, deployed: Deployed, vararg answers: Boolean): Run {
        val progress = mutableListOf<List<Step>>()
        val asked = mutableListOf<ReplaceQuestion>()
        val landed = api().land(
            plan,
            deployed,
            deployedSteps,
            login,
            "test",
            { CloudflareSession(it.login) {} },
            progress = { progress += it },
            agrees = { q -> answers[asked.size].also { asked += q } },
        )
        return Run(landed, progress, asked)
    }

    @Test
    fun agreedRecordsGoAndTheWorkerIsSignedInToOnItsDomain() = runTest {
        val c = cloud()
        val run = c.land(Plan.Fresh(RELEASE, found()), c.conflict(), true)

        val s = assertIs<Landed.SignedInTo>(run.landed).signedIn
        assertEquals("https://notes.jo.dev", s.connection.server)
        assertEquals("a1", (s.connection as Connection.ByCloudflare).login.account)
        assertEquals(listOf("Main", "Lisbon trip"), s.realms.map { it.name })
        assertEquals("https://realm.jo.workers.dev", run.asked.single().origin)
        assertEquals(listOf("DELETE cf/zones/z1/dns_records/r1"), c.routes.filter { it.startsWith("DELETE") })
        val upload = Step.Upload(Semver(1, 0, 0))
        assertEquals(
            listOf(
                listOf(upload, Step.Domain("notes.jo.dev")),
                listOf(upload, Step.Domain("notes.jo.dev"), Step.Waiting("https://notes.jo.dev")),
            ),
            run.progress,
        )
    }

    @Test
    fun declinedRecordsStayAndTheWorkerIsSignedInToOnWorkersDev() = runTest {
        val c = cloud()
        val run = c.land(Plan.Fresh(RELEASE, found()), c.conflict(), false)

        assertEquals("https://realm.jo.workers.dev", assertIs<Landed.SignedInTo>(run.landed).signedIn.connection.server)
        assertTrue(c.routes.none { it.startsWith("DELETE") }, c.routes.toString())
        assertEquals(emptyList(), run.progress)
    }

    @Test
    fun aFailedTryAsksAgainSayingWhy() = runTest {
        val c = cloud(failures = 1)
        val run = c.land(Plan.Fresh(RELEASE, found()), c.conflict(), true, true)

        assertNull(run.asked[0].problem)
        assertEquals("An unknown error has occurred", run.asked[1].problem?.message)
        assertEquals("https://notes.jo.dev", assertIs<Landed.SignedInTo>(run.landed).signedIn.connection.server)
    }

    @Test
    fun anUpdateNeedsNoNewSignIn() = runTest {
        val c = cloud()
        val update = Plan.Update(RELEASE, found(), Info(version = 2, scope = "owner"))
        val run = c.land(update, Deployed(ACCOUNT, "https://realm.jo.workers.dev", Info(version = 3, scope = "owner"), null))

        assertIs<Landed.Updated>(run.landed)
        assertEquals(emptyList(), c.routes)
    }

    @Test
    fun aWorkerThatDoesNotAnswerYetIsSignedInToLater() = runTest {
        val c = cloud()
        val run = c.land(Plan.Fresh(RELEASE, found()), Deployed(ACCOUNT, "https://realm.jo.workers.dev", null, null))

        val later = assertIs<Landed.NotAnswering>(run.landed).connection
        assertEquals("https://realm.jo.workers.dev", later.server)
        assertEquals("a1", later.login.account)
        assertEquals(emptyList(), c.routes)
    }
}
