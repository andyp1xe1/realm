package realm.jvm

import kotlinx.coroutines.CompletableDeferred

/**
 * Turns a launch-then-callback API, such as an Android result launcher, into a suspending call.
 * One request is in flight at a time: a new one cancels the caller still waiting on the last.
 */
class ResultBridge<O> {
    private var pending: CompletableDeferred<O>? = null

    /** Calls [launch] and suspends until [deliver]. */
    suspend fun await(launch: () -> Unit): O {
        val d = CompletableDeferred<O>()
        pending?.cancel()
        pending = d
        launch()
        return d.await()
    }

    fun deliver(result: O) {
        pending?.complete(result)
        pending = null
    }
}
