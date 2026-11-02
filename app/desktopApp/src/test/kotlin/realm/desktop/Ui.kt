// The previews' stand-in for the app's UI thread. The app's coroutines run on its UI thread, where it also
// draws; the previews draw on their main thread, so their coroutines must come back to it too. Otherwise a
// fetch finishing on a network thread publishes state there, and Compose refuses state applied off the
// thread that draws ("multithreaded access to SnapshotStateObserver").
package realm.desktop

import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutines on the thread that first used it, the one that draws: at once when started there, as the
 * app's would be, and otherwise queued until the next frame or [pause].
 */
internal object Ui : CoroutineDispatcher() {
    private val thread: Thread = Thread.currentThread()
    private val queue = ConcurrentLinkedQueue<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    /** Runs what came back from other threads, as the app's UI thread would between frames. */
    fun drain() {
        check(Thread.currentThread() === thread) { "the previews draw on one thread" }
        while (true) (queue.poll() ?: return).run()
    }

    /** Waits [ms] of real time, for a stand-in server say, then runs what came back meanwhile. */
    fun pause(ms: Long) {
        Thread.sleep(ms)
        drain()
    }
}

/** Draws a frame at [nanos], first running what came back to the UI thread, as the app would. */
internal fun ImageComposeScene.renderOnUi(nanos: Long): Image {
    Ui.drain()
    return render(nanos)
}
