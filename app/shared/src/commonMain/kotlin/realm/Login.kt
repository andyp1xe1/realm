package realm

import kotlinx.coroutines.CompletableDeferred

/**
 * Where logging in with Cloudflare, or deploying or updating the worker with that login, has got to. One value, assigned
 * whole, so the sheet never draws a step without what it shows: a plan without its release, progress without
 * its plan. [CloudflareSetup.login] is null when neither is under way.
 */
sealed interface Login {
    /** The browser is out, or the worker is being looked for. */
    data object LoggingIn : Login

    /** Looking at the account and the newest release to work out an update of the worker that's there. */
    data object Planning : Login

    /**
     * [plan], to confirm before anything is written, with what was picked so far. [problem] is why the last
     * try stopped, like R2 being off, to fix and try again.
     */
    class Confirm(val session: CloudflareSession, val plan: Plan, val choices: Choices, val problem: ApiException?) : Login

    /** Working out a plan failed: there's no release this app can deploy, say. */
    class Failed(val message: String) : Login

    /** Deploying [plan]: [steps] so far, the last one under way. */
    class Deploying(val plan: Plan.Ready, val steps: List<Step>) : Login

    /** Deployed [plan], but DNS records stand in the way of the custom domain: [answer] whether they may go. */
    class Replace(val plan: Plan.Ready, val question: ReplaceQuestion, private val agrees: CompletableDeferred<Boolean>) : Login {
        fun answer(agree: Boolean) {
            agrees.complete(agree)
        }
    }
}

/** A newer worker than the server you're signed in to runs, of the same fork. [canDeploy] with a Cloudflare login. */
data class ServerUpdate(val from: Int, val to: Int, val canDeploy: Boolean)
