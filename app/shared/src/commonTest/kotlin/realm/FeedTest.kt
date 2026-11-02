package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedTest {
    private var requests: (HttpRequestData) -> Unit = {}

    private fun TestScope.feed(answer: () -> String): Model {
        val engine = MockEngine(
            MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler) }.also {
                it.addHandler { req ->
                    requests(req)
                    respond(answer(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        return Model(TestPlatform(MapPrefs()), this, Api("https://realm.jo.dev/api", Bearer { "t" }, "test", engine))
    }

    @Test
    fun anEmptyFeedPolledNeverShowsItLoading() = runTest {
        val m = feed { """{"items":[],"next":null}""" }
        m.startEmpty()
        val shown = mutableListOf<Boolean>()
        repeat(3) {
            m.poll()
            shown += m.loading
            runCurrent()
            shown += m.loading
            advanceUntilIdle()
        }
        assertEquals(listOf(false), shown.distinct())
        assertTrue(m.isEmpty)
    }

    @Test
    fun pollingAsksOnlyForWhatCameSinceTheLastAnswer() = runTest {
        val asked = mutableListOf<String?>()
        val m = feed {
            when (asked.size) {
                1 ->
                    """{"items":[{"id":"b","kind":"note","text":"two","created":2},""" +
                        """{"id":"a","kind":"note","text":"one","created":1}],"since":7}"""

                2 -> """{"items":[{"id":"c","kind":"note","text":"three","created":3}],"since":8}"""

                else -> """{"items":[],"since":8}"""
            }
        }
        requests = { asked += it.url.parameters["since"] }
        m.refresh()
        advanceUntilIdle()
        m.poll()
        advanceUntilIdle()
        m.poll()
        advanceUntilIdle()
        assertEquals(listOf(null, "7", "8"), asked)
        assertEquals(listOf("c", "b", "a"), m.items.map { it.id })
    }

    private class Photo : LocalFile {
        var finished = 0
        override val name = "IMG_1.jpg"
        override val mime = "image/jpeg"
        override val size = 3L
        override fun reader() = object : ByteReader {
            var left = 3
            override fun read(buf: ByteArray, off: Int, len: Int): Int = if (left == 0) -1 else 1.also { left-- }
            override fun close() {}
        }
        override fun finished() {
            finished++
        }
    }

    @Test
    fun aFileIsFinishedWithOnceItsUploadedOrDismissedNotWhileItCanBeRetried() = runTest {
        var fails = true
        val m = feed {
            if (fails) error("offline") else """{"id":"p","kind":"image","name":"IMG_1.jpg","created":1}"""
        }
        fun TestScope.settle(done: () -> Boolean) {
            repeat(400) {
                advanceUntilIdle()
                if (done()) return
                Thread.sleep(5)
            }
        }
        val photo = Photo()
        m.sendFiles(listOf(photo))
        settle { m.pending.single().error != null }
        assertEquals(0, photo.finished)

        fails = false
        m.retry(m.pending.single())
        settle { m.pending.isEmpty() }
        assertEquals(1, photo.finished)

        fails = true
        val other = Photo()
        m.sendFiles(listOf(other))
        settle { m.pending.single().error != null }
        m.dismiss(m.pending.single())
        assertEquals(1, other.finished)
    }

    @Test
    fun aSentNoteKeepsItsRowAsItLands() = runTest {
        val m = feed { """{"id":"n1","kind":"note","text":"hi","created":1}""" }
        m.startEmpty()
        m.send("hi")
        val sending = m.rows.single()
        advanceUntilIdle()
        val landed = m.rows.single()
        assertEquals("n1", (landed as Row.Posted).item.id)
        assertEquals(sending.key, landed.key)
    }
}
