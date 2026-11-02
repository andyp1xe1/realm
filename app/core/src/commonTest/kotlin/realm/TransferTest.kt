package realm

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransferTest {
    private val file = ByteArray(300_000) { (it % 251).toByte() }

    private val server = FakeServer { path ->
        when (path) {
            "/api/items/big/raw", "/s/one/items/big/raw" -> respond(
                file,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, file.size.toString()),
            )

            "/s/one/info" -> json(
                """{"version":3,"scope":"read","items":1,"item":{"id":"big","kind":"file","name":"../photos/big.bin","created":1}}""",
            )

            "/s/many/info" -> json("""{"version":3,"scope":"read","items":3}""")

            else -> notFound()
        }
    }

    @Test
    fun streamsADownloadIntoAWriter() = runTest {
        val api = Api("https://realm.jo.dev/api", Bearer { "t" }, "test", server.engine)
        val got = Sink()
        val seen = mutableListOf<Pair<Long, Long?>>()
        val n = api.download("big", got) { done, total -> seen += done to total }

        assertEquals(file.size.toLong(), n)
        assertContentEquals(file, got.bytes())
        assertEquals(file.size.toLong() to file.size.toLong(), seen.last())
    }

    @Test
    fun aFailedDownloadWritesNothing() = runTest {
        val api = Api("https://realm.jo.dev/api", Bearer { "t" }, "test", server.engine)
        val got = Sink()
        val e = assertFailsWith<ApiException> { api.download("gone", got) }
        assertEquals(404, e.status)
        assertEquals(0, got.bytes().size)
    }

    @Test
    fun findsTheFileBehindALinkToIt() = runTest {
        val f = sharedFile("realm.jo.dev/s/one", "test", server.engine)
        assertEquals("big", f.item.id)
        assertEquals("big.bin", f.item.fileName) // no folders from the server in where it's saved
        val got = Sink()
        f.api.download(f.item.id, got)
        assertEquals("https://realm.jo.dev/s/one/items/big/raw", server.requests.last().url.toString())
    }

    @Test
    fun aLinkToSeveralItemsIsNotOneFile() = runTest {
        assertFailsWith<ApiException> { sharedFile("https://realm.jo.dev/s/many", "test", server.engine) }
    }

    @Test
    fun aNoteSavesUnderItsId() {
        assertEquals("n1.txt", Item("n1", "note", text = "hi", created = 0).fileName)
    }

    private class Sink : ByteWriter {
        private val chunks = mutableListOf<ByteArray>()
        override fun write(buf: ByteArray, off: Int, len: Int) {
            chunks += buf.copyOfRange(off, off + len)
        }
        fun bytes() = chunks.fold(ByteArray(0)) { a, b -> a + b }
    }
}
