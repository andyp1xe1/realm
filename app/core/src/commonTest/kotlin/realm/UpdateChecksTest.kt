package realm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateChecksTest {
    private var now = 1_000_000L
    private var asked = 0
    private val prefs = MapPrefs()
    private val day = 24 * 60 * 60L

    private fun checks(version: Int = 3) = UpdateChecks(prefs, {
        asked++
        Release(Semver(1, 2, 0), MANIFEST.copy(version = version), "abc".encodeToByteArray(), null)
    }) { now }

    @Test
    fun looksAtMostOnceADayUnlessAsked() {
        val c = checks()
        assertTrue(c.due(force = false))
        now += day - 1
        assertFalse(c.due(force = false))
        assertTrue(c.due(force = true))
        now += day
        assertTrue(c.due(force = false))
        c.reset()
        assertTrue(c.due(force = false))
    }

    @Test
    fun asksGitHubAtMostOnceADayEvenAcrossRestarts() = runTest {
        assertEquals(3, checks().latest(force = false))
        now += day - 1
        assertEquals(3, checks(version = 4).latest(force = false)) // a restart: what GitHub said is kept
        assertEquals(1, asked)
        now += 1
        assertEquals(4, checks(version = 4).latest(force = false))
        assertEquals(4, checks(version = 5).latest(force = false))
        assertEquals(5, checks(version = 5).latest(force = true))
        assertEquals(3, asked)
        assertEquals("v1.2.0", checks().knownTag)
    }

    @Test
    fun offersOnlyANewerReleaseOfThisFork() = runTest {
        val c = checks(version = 3)
        assertEquals(3, c.newerFor(Info(version = 2, scope = "owner"), force = true))
        assertNull(c.newerFor(Info(version = 3, scope = "owner"), force = true))
        assertNull(c.newerFor(Info(fork = "someone/realm", version = 2, scope = "owner"), force = true))
    }
}
