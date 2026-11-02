package realm

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeployTest {
    private val owner = """{"version":3,"scope":"owner"}"""

    /** A Cloudflare account that takes every write, with [d1] as what the D1 name filter finds. */
    private fun clouds(d1: String = "[]", override: MockRequestHandleScope.(route: String) -> HttpResponseData? = { null }) =
        FakeClouds { route, _ ->
            override(route) ?: when (route) {
                "POST $CF/accounts/a1/r2/buckets" -> cf("""{"name":"realm"}""")
                "GET $CF/accounts/a1/d1/database" -> cf(d1)
                "POST $CF/accounts/a1/d1/database" -> cf("""{"uuid":"new-db","name":"realm"}""")
                "PUT $CF/accounts/a1/workers/scripts/realm" -> cf("""{"id":"realm"}""")
                "PUT $CF/accounts/a1/workers/scripts/realm/schedules" -> cf("""{"schedules":[{"cron":"17 4 * * *"}]}""")
                "POST $CF/accounts/a1/workers/scripts/realm/subdomain" -> cf("""{"enabled":true,"previews_enabled":false}""")
                "GET realm.jo.workers.dev/api/info" -> json(owner)
                else -> null
            }
        }

    @Test
    fun aFreshDeploySetsEverythingUpInOrder() = runTest {
        val c = clouds()
        val steps = mutableListOf<Step>()
        val done = c.api().deploy(Plan.Fresh(RELEASE, found()), Choices()) { steps += it }

        assertEquals(
            listOf(
                "POST cf/accounts/a1/r2/buckets",
                "GET cf/accounts/a1/d1/database",
                "POST cf/accounts/a1/d1/database",
                "PUT cf/accounts/a1/workers/scripts/realm",
                "PUT cf/accounts/a1/workers/scripts/realm/schedules",
                "POST cf/accounts/a1/workers/scripts/realm/subdomain",
                "GET realm.jo.workers.dev/api/info",
            ),
            c.routes,
        )
        assertEquals(
            listOf(
                Step.Bucket("realm"),
                Step.Database("realm"),
                Step.Upload(Semver(1, 0, 0)),
                Step.Schedule,
                Step.WorkersDev("https://realm.jo.workers.dev"),
                Step.Waiting("https://realm.jo.workers.dev"),
            ),
            steps,
        )
        assertEquals("https://realm.jo.workers.dev", done.origin)
        assertEquals("owner", done.info?.scope)

        val upload = c.seen.single { it.route == "PUT $CF/accounts/a1/workers/scripts/realm" }
        val module = part(upload.text, "worker.js")
        assertTrue("filename=\"worker.js\"" in module.headers, module.headers)
        assertTrue("Content-Type: application/javascript+module" in module.headers, module.headers)
        assertEquals("abc", module.body)

        val meta = metadata(upload)
        assertEquals("worker.js", meta["main_module"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01", meta["compatibility_date"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(
                mapOf("type" to "r2_bucket", "name" to "BUCKET", "bucket_name" to "realm"),
                mapOf("type" to "d1", "name" to "DB", "id" to "new-db"),
                mapOf("type" to "secret_text", "name" to "REALM_ACCOUNT", "text" to "a1"),
            ),
            meta["bindings"]!!.jsonArray.map { b -> b.jsonObject.mapValues { it.value.jsonPrimitive.content } }.toSet(),
        )
        assertEquals("""[{"cron":"17 4 * * *"}]""", c.seen.single { it.route.endsWith("/schedules") }.text)
        assertEquals("""{"name":"realm"}""", c.seen.first { it.route == "POST $CF/accounts/a1/d1/database" }.text)
    }

    @Test
    fun anUpdateKeepsTheWorkersSecretsAndAddresses() = runTest {
        val c = clouds(d1 = """[{"uuid":"db1","name":"realm"}]""")
        val plan = Plan.Update(
            RELEASE,
            found(Existing.Answered("https://realm.jo.workers.dev", Info(version = 2, scope = "owner")), workersDevOn = true),
            Info(version = 2, scope = "owner"),
        )
        c.api().deploy(plan)

        val meta = metadata(c.seen.single { it.route == "PUT $CF/accounts/a1/workers/scripts/realm" })
        assertEquals(listOf("secret_text"), meta["keep_bindings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(c.routes.none { it == "POST cf/accounts/a1/d1/database" || it.endsWith("/subdomain") }, c.routes.toString())
    }

    @Test
    fun findsTheDatabaseByItsExactName() = runTest {
        val c = clouds(d1 = """[{"uuid":"old","name":"realm-old"},{"uuid":"db1","name":"realm"}]""")
        c.api().deploy(Plan.Fresh(RELEASE, found()))

        val d1 = metadata(c.seen.single { it.route == "PUT $CF/accounts/a1/workers/scripts/realm" })["bindings"]!!.jsonArray
            .map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "d1" }
        assertEquals("db1", d1["id"]!!.jsonPrimitive.content)
        assertEquals("realm", c.seen.first { it.route == "GET $CF/accounts/a1/d1/database" }.url.parameters["name"])
        assertTrue("POST cf/accounts/a1/d1/database" !in c.routes)
    }

    @Test
    fun theFreePlansDatabaseLimitSaysSoAndUploadsNothing() = runTest {
        val c = clouds(d1 = """[{"uuid":"t","name":"realm-test"}]""") { route ->
            if (route == "POST $CF/accounts/a1/d1/database") cfError(HttpStatusCode.Forbidden, 7406, "limit") else null
        }
        assertFailsWith<D1Limit> { c.api().deploy(Plan.Fresh(RELEASE, found())) }
        assertTrue(c.routes.none { it.startsWith("PUT") }, c.routes.toString())
    }

    @Test
    fun aBucketThatExistsIsUsed() = runTest {
        val c = clouds { route ->
            if (route == "POST $CF/accounts/a1/r2/buckets") cfError(HttpStatusCode.Conflict, 10004, "exists") else null
        }
        assertEquals("owner", c.api().deploy(Plan.Fresh(RELEASE, found())).info?.scope)
    }

    @Test
    fun r2TurnedOffSaysWhereToTurnItOn() = runTest {
        val c = clouds { route ->
            if (route == "POST $CF/accounts/a1/r2/buckets") cfError(HttpStatusCode.Forbidden, 10042, "enable R2") else null
        }
        val e = assertFailsWith<R2NotEnabled> { c.api().deploy(Plan.Fresh(RELEASE, found())) }
        assertTrue(R2NotEnabled.URL in e.message!!)
    }

    @Test
    fun anAccountWithoutWorkersDevNeedsASubdomainPickedBeforeAnythingChanges() = runTest {
        val c = clouds()
        assertFailsWith<NeedsSubdomain> { c.api().deploy(Plan.Fresh(RELEASE, found(subdomain = null))) }
        assertEquals(emptyList(), c.routes)
    }

    @Test
    fun aNewAddressThatIsSlowToResolveIsStillADeploy() = runTest {
        val c = clouds { route -> if (route == "GET realm.jo.workers.dev/api/info") throw IllegalStateException("NXDOMAIN") else null }
        val done = c.api().deploy(Plan.Fresh(RELEASE, found()))
        assertEquals("https://realm.jo.workers.dev", done.origin)
        assertNull(done.info)
    }

    private class Part(val headers: String, val body: String)

    private fun part(multipart: String, name: String): Part {
        val boundary = multipart.substringBefore("\r\n")
        val p = multipart.split(boundary).first {
            "name=$name" in it.substringBefore("\r\n\r\n") ||
                "name=\"$name\"" in it.substringBefore("\r\n\r\n")
        }
        return Part(p.substringBefore("\r\n\r\n"), p.substringAfter("\r\n\r\n").removeSuffix("\r\n"))
    }

    private fun metadata(upload: Seen): JsonObject {
        val p = part(upload.text, "metadata")
        assertTrue("Content-Type: application/json" in p.headers, p.headers)
        return Json.parseToJsonElement(p.body).jsonObject
    }
}
