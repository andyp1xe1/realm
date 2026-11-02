package realm

import kotlinx.coroutines.CompletableDeferred

/**
 * Where logging in with Cloudflare, or deploying or updating the worker, has got to: one whole value, so
 * the sheet never draws a step without what it shows.
 */
sealed interface Login {
    /** The browser is out, or the worker is being looked for. */
    data object LoggingIn : Login

    /** Working out an update of the worker that's there. */
    data object Planning : Login

    /** [plan], to confirm before anything is written; [problem] is why the last try stopped. */
    class Confirm(val session: CloudflareSession, val plan: Plan, val choices: Choices, val problem: ApiException?) : Login

    class Failed(val message: String) : Login

    /** [steps] so far, the last one under way. */
    class Deploying(val plan: Plan.Ready, val steps: List<Step>) : Login

    /** Deployed, but DNS records stand in the way of the custom domain: [answer] whether they may go. */
    class Replace(val plan: Plan.Ready, val question: ReplaceQuestion, private val agrees: CompletableDeferred<Boolean>) : Login {
        fun answer(agree: Boolean) {
            agrees.complete(agree)
        }
    }
}

/** A newer worker of the same fork than the server runs; [canDeploy] with a Cloudflare login. */
data class ServerUpdate(val from: Int, val to: Int, val canDeploy: Boolean)
