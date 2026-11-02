package realm.jvm

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultBridgeTest {
    @Test fun aResultReachesTheCallerThatAsked() = runBlocking {
        val bridge = ResultBridge<String>()
        val opened = mutableListOf<String>()
        val call = async(start = CoroutineStart.UNDISPATCHED) { bridge.await { opened += "picker" } }
        bridge.deliver("a.jpg")
        assertEquals("a.jpg", withTimeoutOrNull(1000) { call.await() })
        assertEquals(listOf("picker"), opened)
    }

    @Test fun aSecondRequestCancelsTheFirstInsteadOfLeavingItWaiting() = runBlocking {
        val bridge = ResultBridge<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { bridge.await {} }
        val second = async(start = CoroutineStart.UNDISPATCHED) { bridge.await {} }
        bridge.deliver("b.jpg")
        assertEquals("b.jpg", withTimeoutOrNull(1000) { second.await() })
        val firstEnded = withTimeoutOrNull(1000) { first.join() } != null
        assertTrue(firstEnded && first.isCancelled, "the first caller is still waiting")
    }
}
