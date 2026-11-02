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
    class Confirm(val session: CloudflareSession, val plan: Plan, val choices: Choices, val problem: ApiException?) : Login {
        /** R2 is on, which only the account's owner can do; nothing else stops a [Plan.Ready]. */
        val canDeploy: Boolean get() = plan is Plan.Ready && plan.found.r2Enabled
    }

    class Failed(val message: String) : Login

    /** Deploying [plan]: all its [steps] from the start, those before [current] done and [current] under way. */
    class Deploying(val plan: Plan.Ready, val steps: List<Step>, val current: Int) : Login {
        /**
         * On to [step], in its row; a [Step.Waiting] takes the waiting row whatever origin it came to, and trying
         * a [Step.Domain] again means waiting on that domain once more.
         */
        fun at(step: Step): Deploying {
            val i = steps.indexOf(step).takeIf { it >= 0 } ?: steps.indexOfFirst { it is Step.Waiting && step is Step.Waiting }
            if (i < 0) return this
            val next = steps.map {
                when {
                    it is Step.Waiting && step is Step.Waiting -> step
                    it is Step.Waiting && step is Step.Domain -> Step.Waiting("https://${step.hostname}")
                    else -> it
                }
            }
            return Deploying(plan, next, i)
        }
    }

    /** Deployed, but DNS records stand in the way of the custom domain: [answer] whether they may go. */
    class Replace(val plan: Plan.Ready, val question: ReplaceQuestion, private val agrees: CompletableDeferred<Boolean>) : Login {
        fun answer(agree: Boolean) {
            agrees.complete(agree)
        }
    }
}

/** A newer worker of the same fork than the server runs; [canDeploy] with a Cloudflare login. */
data class ServerUpdate(val from: Int, val to: Int, val canDeploy: Boolean)
