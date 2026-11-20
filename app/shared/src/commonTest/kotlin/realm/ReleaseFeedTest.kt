package realm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReleaseFeedTest {
    private var now = 1_000_000L
    private var asked = 0
    private var limit: GitHubBusy? = null
    private val heard = mutableListOf<Long>()
    private val feed = ReleaseFeed(MapPrefs(), {
        asked++
        limit?.let { throw it }
        Releases(emptyList(), emptyList())
    }, now = { now }, limitedUntil = { heard += it.until })

    @Test
    fun gitHubTurningThisNetworkAwayStopsAskingItUntilThen() = runTest {
        limit = GitHubBusy(now + 600, now)
        assertFailsWith<GitHubBusy> { feed.get(force = true) }
        assertEquals(listOf(now + 600), heard)
        assertEquals(
            "GitHub is limiting this network for now. realm asks again by itself in about 10 minutes.",
            feed.limited?.message,
        )

        limit = null
        assertFailsWith<GitHubBusy> { feed.get(force = true) }
        assertEquals(1, asked)

        now += 601
        feed.get(force = true)
        assertEquals(2, asked)
        assertNull(feed.limited)
    }
}
