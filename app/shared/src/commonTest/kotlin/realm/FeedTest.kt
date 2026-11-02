package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
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
    private fun TestScope.feed(answer: () -> String): Model {
        val engine = MockEngine(
            MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler) }.also {
                it.addHandler { respond(answer(), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
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
