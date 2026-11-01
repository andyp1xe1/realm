package realm.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import realm.Loaded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadingTest {
    /**
     * Draws [Loading] a frame every 16 ms for a second (the last at 992 ms), from [start], the value arriving at [arrives] ms (never,
     * if null); gives the times of the frames that drew the skeleton.
     */
    private fun skeletonFrames(arrives: Long?, start: Loaded<String> = Loaded.Unknown): List<Long> {
        var state by mutableStateOf(start)
        val indicators = Indicators()
        val scene = ImageComposeScene(200, 100) {
            CompositionLocalProvider(LocalIndicators provides indicators) {
                Loading(state, skeleton = { SkeletonLine(0.5f) }) { Text(it) }
            }
        }
        val frames = mutableListOf<Long>()
        for (ms in 0L..1000L step 16) {
            if (arrives != null && ms >= arrives && state == Loaded.Unknown) state = Loaded.Known("here")
            scene.render(ms * 1_000_000)
            if (indicators.showing) frames += ms
        }
        scene.close()
        return frames
    }

    @Test
    fun aValueThatArrivesWithinTheDelayNeverShowsTheSkeleton() {
        assertEquals(emptyList(), skeletonFrames(arrives = 300))
        assertEquals(emptyList(), skeletonFrames(arrives = INDICATOR_DELAY_MILLIS - 20L))
    }

    @Test
    fun aSlowOneShowsTheSkeletonOnlyAfterTheDelayThenFadesToTheValue() {
        val frames = skeletonFrames(arrives = 700)
        assertTrue(frames.isNotEmpty(), "the skeleton never showed")
        assertTrue(frames.first() > INDICATOR_DELAY_MILLIS, "the skeleton showed at ${frames.first()} ms")
        assertTrue(frames.last() < 700 + 400, "the skeleton was still there at ${frames.last()} ms") // gone as the value fades in
    }

    @Test
    fun aValueNeverArrivingKeepsTheSkeleton() {
        val frames = skeletonFrames(arrives = null)
        assertTrue(frames.first() > INDICATOR_DELAY_MILLIS && frames.last() == 992L, "skeleton at $frames")
    }

    @Test
    fun aValueKnownFromTheStartShowsAtOnce() = assertEquals(emptyList(), skeletonFrames(arrives = null, start = Loaded.Known("kept")))
}
