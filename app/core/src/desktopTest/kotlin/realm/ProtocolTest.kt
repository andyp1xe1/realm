package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ProtocolTest {
    private val recording = File(System.getProperty("user.dir"), "../../worker/protocol/${Api.VERSION}.json")
    private val fix = "change APP_CALLS in worker/test/protocol.ts to match, then run npm run record-protocol in worker/"

    @Test
    fun theAppSendsAndReadsWhatItsProtocolRecordingHas() = runTest {
        val calls = Json.parseToJsonElement(recording.readText()).jsonObject.getValue("calls").jsonArray.map { it.jsonObject }
        val differences = mutableListOf<String>()
        var next = 0
        val engine = MockEngine { req ->
            val call = calls.getOrNull(next++)
                ?: error(
                    "The app makes a call protocol ${Api.VERSION} has no recording of, ${req.method.value} ${req.url.encodedPath}: $fix",
                )
            val name = call.getValue("name").jsonPrimitive.content
            val sent = call.getValue("request").jsonObject
            val (path, query) = sent.getValue("path").jsonPrimitive.content.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (req.method.value !=
                sent.text("method")
            ) {
                differences += "$name: the app sends ${req.method.value}, recorded ${sent.text("method")}"
            }
            if (req.url.encodedPath != path) differences += "$name: the app asks for ${req.url.encodedPath}, recorded $path"
            if (req.url.parameters.entries() != parseQueryString(query).entries()) {
                differences += "$name: the app asks with ${req.url.encodedQuery}, recorded $query"
            }
            val body = req.body.toByteArray().decodeToString()
            val recorded = sent["body"]
            val same = when {
                recorded == null -> body.isEmpty()
                sent.text("type") == "application/json" -> Json.parseToJsonElement(body.ifEmpty { "null" }) == recorded
                else -> body == recorded.jsonPrimitive.content
            }
            if (!same) differences += "$name: the app sends $body, recorded $recorded"
            val got = call.getValue("response").jsonObject
            val answer = got.getValue("body").let {
                if (got.text("type") ==
                    "application/json"
                ) {
                    it.toString()
                } else {
                    it.jsonPrimitive.content
                }
            }
            respond(
                answer,
                HttpStatusCode.fromValue(got.getValue("status").jsonPrimitive.int),
                headersOf(HttpHeaders.ContentType, got.text("type").orEmpty()),
            )
        }

        val api = Api("https://realm.test/api", { "token" }, "test", engine)
        api.info()
        api.realms()
        val trips = api.createRealm("Trips")
        api.renameRealm(trips.id, "Travel")
        val realm = api.realm(trips.id)
        val note = realm.note("pack the tent")
        val file = realm.upload(Text("list.txt", "tent, stove"), "packing") {}
        realm.upload(Text("map.txt", "north", size = -1), null) {}
        assertFails { realm.upload(Text("late.txt", "", size = -1, fails = true), null) {} }
        realm.list()
        realm.list(query = "tent")
        realm.item(note.id)
        realm.edit(note.id, "pack the big tent")
        realm.bytes(file.id)
        val link = realm.createLink("full", label = "Ana")
        realm.links()
        val shared = Api("https://realm.test/s/${link.token}", null, "test", engine)
        shared.info()
        shared.list()
        shared.note("bring snacks")
        realm.revoke(link.token)
        realm.delete(note.id)
        api.help()
        api.deleteRealm(trips.id)

        assertEquals(emptyList(), differences, "The app's calls differ from protocol ${Api.VERSION}'s recording: $fix")
        assertEquals(calls.size, next, "The app made ${calls.size - next} fewer calls than protocol ${Api.VERSION}'s recording: $fix")
    }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    private class Text(
        override val name: String,
        text: String,
        override val size: Long = text.length.toLong(),
        private val fails: Boolean = false,
    ) : LocalFile {
        private val bytes = text.encodeToByteArray()
        override val mime = "text/plain"

        override fun reader() = object : ByteReader {
            private var at = 0

            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (fails) throw IOException("the file went away")
                if (at == bytes.size) return -1
                val n = minOf(len, bytes.size - at)
                bytes.copyInto(buf, off, at, at + n)
                at += n
                return n
            }

            override fun close() {}
        }
    }
}
