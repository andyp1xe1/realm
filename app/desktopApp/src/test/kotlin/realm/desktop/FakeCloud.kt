package realm.desktop

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import realm.CF
import realm.FakeClouds
import realm.Recorded
import realm.cf
import realm.cfError
import realm.json
import realm.recorded
import realm.reshaped

/**
 * Jo's Cloudflare account as [Recorded], and everything deploying talks to. [worker] is what the realm worker
 * answers its owner, null while there's none, and with none the account has no realm worker, bucket or
 * database; deploying makes it version 3. [r2] off answers buckets as R2 does when it's not turned on.
 * notes.jo.dev has an A record in the way until it's deleted. With [accounts] 3, the login also has Studio's
 * two accounts, a2 and a3, neither with realm. [databases] other D1 databases are on each account; [subdomain]
 * is Jo's workers.dev subdomain, null for none; the upload fails while [uploadFails].
 */
internal class FakeCloud(
    var worker: String? = null,
    val r2: Boolean = true,
    val accounts: Int = 1,
    var release: Int = 4,
    val databases: Int = 0,
    var subdomain: String? = "jo",
    var uploadFails: Boolean = false,
    /** How much longer than usual each read takes, as on a slow network. */
    var slowness: Long = 0,
) {
    private var record = true
    private var workersDev = true
    private var hostname = ""
    private val domains = mutableListOf<String>()

    private val assets = "github.com/andyp1xe1/realm/releases/download/v0.1.0"

    val engine = FakeClouds { full, seen ->
        val account = Regex("/accounts/(a[0-9])").find(full)?.groupValues?.get(1)
        val jo = account == null || account == "a1"
        val asJo = if (account == null) full else full.replace("/accounts/$account", "/accounts/a1")
        val route = asJo.replace(CF, "cf")
        val read = route.startsWith("GET ")
        val hasRealm = worker != null && jo
        delay(if (read) 40 + slowness else 450)
        when {
            route == "POST dash.cloudflare.com/oauth2/token" -> json("""{"access_token":"cf","refresh_token":"r","expires_in":3600}""")

            route == "GET $assets/worker.json" -> json(
                """{"version":$release,"name":"realm","main_module":"worker.js","compatibility_date":"2026-10-01",
                    "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],
                    "d1_databases":[{"binding":"DB","database_name":"realm"}],"crons":["17 4 * * *"]}""",
            )

            route == "GET $assets/worker.js" -> respond("abc")

            route == "GET cf/accounts" && accounts > 1 -> json(
                reshaped(Recorded.ACCOUNTS) { jo ->
                    val a1 = jo.jsonArray.single().jsonObject
                    JsonArray(listOf(a1, a1.with("id" to "a2", "name" to "Studio"), a1.with("id" to "a3", "name" to "Studio")))
                },
            )

            route == "GET cf/accounts/a1/workers/scripts" && !hasRealm -> json(reshaped(Recorded.SCRIPTS) { it.without("id", "realm") })

            route == "GET cf/accounts/a1/workers/subdomain" -> {
                val sub = if (jo) subdomain else "studio$account"
                sub?.let { json(reshaped(Recorded.SUBDOMAIN) { r -> r.jsonObject.with("subdomain" to it) }) }
                    ?: json(Recorded.NO_SUBDOMAIN, HttpStatusCode.NotFound)
            }

            route == "PUT cf/accounts/a1/workers/subdomain" -> {
                subdomain = Regex(""""subdomain":"([^"]+)"""").find(seen.text)!!.groupValues[1]
                cf("""{"subdomain":"$subdomain"}""")
            }

            route == "GET cf/accounts/a1/workers/domains" && jo ->
                json(reshaped(Recorded.DOMAINS) { JsonArray(domains.map(Json::parseToJsonElement)) })

            route.startsWith("DELETE cf/accounts/a1/workers/domains/") -> {
                domains.removeAll { "\"id\":\"${route.substringAfterLast('/')}\"" in it }
                cf("null")
            }

            route == "GET cf/accounts/a1/workers/scripts/realm/subdomain" ->
                json(reshaped(Recorded.SCRIPT_SUBDOMAIN) { it.jsonObject.with("enabled" to workersDev) })

            route == "POST cf/accounts/a1/workers/scripts/realm/subdomain" -> {
                workersDev = """"enabled":true""" in seen.text
                cf("""{"enabled":$workersDev}""")
            }

            route == "GET cf/accounts/a1/r2/buckets" && !r2 -> json(Recorded.R2_OFF, HttpStatusCode.Forbidden)

            route == "GET cf/accounts/a1/r2/buckets" && !hasRealm -> json(
                reshaped(Recorded.BUCKETS) { JsonObject(mapOf("buckets" to it.jsonObject.getValue("buckets").without("name", "realm"))) },
            )

            route == "GET cf/accounts/a1/d1/database" -> json(
                reshaped(Recorded.D1) { found ->
                    val db = found.jsonArray.single().jsonObject
                    val others = if (seen.url.parameters["name"] != null) {
                        emptyList()
                    } else {
                        (1..databases).map { db.with("uuid" to "o$it", "name" to "notes-$it") }
                    }
                    JsonArray((if (hasRealm) listOf(db) else emptyList()) + others)
                },
            )

            route == "GET cf/zones" && seen.url.parameters["account.id"] != "a1" ->
                json(reshaped(Recorded.ZONES) { JsonArray(emptyList()) })

            route == "POST cf/accounts/a1/r2/buckets" ->
                if (r2) cf("""{"name":"realm"}""") else json(Recorded.R2_OFF, HttpStatusCode.Forbidden)

            route == "POST cf/accounts/a1/d1/database" ->
                if (databases >= 10) {
                    cfError(HttpStatusCode.Conflict, 7406, "You have reached the maximum number of databases")
                } else {
                    cf("""{"uuid":"db1","name":"realm"}""")
                }

            route == "PUT cf/accounts/a1/workers/scripts/realm" ->
                if (uploadFails) {
                    cfError(HttpStatusCode.InternalServerError, 10021, "Uploading the script failed. Try again in a minute.")
                } else {
                    worker = """{"version":4,"scope":"owner"}"""
                    cf("""{"id":"realm"}""")
                }

            route == "PUT cf/accounts/a1/workers/domains" -> {
                hostname = Regex(""""hostname":"([^"]+)"""").find(seen.text)!!.groupValues[1]
                if (record) {
                    cfError(HttpStatusCode.Conflict, 100117, "Hostname already has DNS records")
                } else {
                    val d = """{"id":"d-$hostname","hostname":"$hostname","zone_id":"z1","service":"realm"}"""
                    domains += d
                    cf(d)
                }
            }

            route == "GET cf/zones/z1/dns_records" ->
                cf(if (record) """[{"id":"r1","type":"A","name":"$hostname","content":"192.0.2.1"}]""" else "[]")

            route == "DELETE cf/zones/z1/dns_records/r1" -> {
                record = false
                cf("""{"id":"r1"}""")
            }

            " cf/" in route -> if (read) recorded(asJo)?.let { json(it) } else cf("{}")

            route.endsWith("/api/info") -> worker?.let { json(it) } ?: json("""{"error":"not found"}""", HttpStatusCode.NotFound)

            route.endsWith("/api/realms") -> json("""{"realms":[{"id":"main","name":"Main","created":1,"count":0}]}""")

            route.endsWith("/links") -> json("""{"links":[]}""")

            route.startsWith("GET api.github.com/") -> null

            else -> json("""{"items":[],"next":null}""")
        }
    }.engine
}

private fun JsonObject.with(vararg changes: Pair<String, Any>) =
    JsonObject(this + changes.map { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

private fun JsonElement.without(key: String, value: String) =
    JsonArray(jsonArray.filter { it.jsonObject[key]?.jsonPrimitive?.content != value })
