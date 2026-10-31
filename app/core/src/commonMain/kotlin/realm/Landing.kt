// After deploying: putting the worker on its custom domain past the DNS records in the way, once the user
// agrees, then signing in to it as logging in would. The app shows each step and asks; this decides what's next.
package realm

import kotlinx.coroutines.CancellationException

/** Where a finished deploy came to. */
sealed interface Landed {
    /** [plan] updated the worker in place: the sign-in to it still holds. */
    data class Updated(val plan: Plan.Update) : Landed

    /**
     * Deployed, but its address doesn't reach us yet, as a new one may not for a few minutes: sign in to
     * [connection] and fetch its realms once it answers.
     */
    data class NotAnswering(val connection: Connection.ByCloudflare) : Landed

    /** Deployed and signed in to, with its realms. */
    class SignedInTo(val signedIn: SignedIn) : Landed
}

/**
 * DNS records stand in the way of [conflict]'s hostname: may they go? The worker answers on [origin] either
 * way; [problem] is why the last try failed.
 */
data class ReplaceQuestion(val conflict: DomainOutcome.Conflict, val origin: String, val problem: ApiException?)

/**
 * Lands [deployed], what deploying [plan] with this API's login came to after [steps]. A custom domain with
 * DNS records in the way is put in place only if [agrees] to deleting them says so, asked again after a
 * failed try; otherwise the worker stays on workers.dev. A fresh worker is then signed in to with [login],
 * as [signInWithCloudflare] does with [source] and [session]. [progress] gets every step so far as it starts.
 */
suspend fun CloudflareApi.land(
    plan: Plan.Ready,
    deployed: Deployed,
    steps: List<Step>,
    login: CloudflareLogin,
    source: String,
    session: (Connection.ByCloudflare) -> CloudflareSession,
    progress: suspend (List<Step>) -> Unit,
    agrees: suspend (ReplaceQuestion) -> Boolean,
): Landed {
    var done = deployed
    var conflict = done.domain as? DomainOutcome.Conflict
    // Up to the domain: putting it there starts again once the user has answered.
    val before = steps.takeWhile { it !is Step.Domain }
    var problem: ApiException? = null
    while (conflict != null && agrees(ReplaceQuestion(conflict, done.origin, problem))) {
        val hostname = conflict.choice.hostname
        val trying = before + Step.Domain(hostname)
        progress(trying)
        problem = null
        try {
            when (val out = attachDomain(done.account, conflict.choice, conflict)) {
                is DomainOutcome.Conflict -> conflict = out

                is DomainOutcome.Attached -> {
                    val origin = "https://${out.domain.hostname}"
                    progress(trying + Step.Waiting(origin))
                    done = done.copy(origin = origin, info = waitForOwner(origin), domain = out)
                    conflict = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("Putting realm on $hostname", e)
            problem = e as? ApiException ?: ApiException(explain(e), cause = e)
        }
    }
    val owner = login.copy(account = done.account.id)
    return when {
        plan is Plan.Update -> Landed.Updated(plan)
        done.info == null -> Landed.NotAnswering(Connection.ByCloudflare(done.origin, owner))
        else -> Landed.SignedInTo(signInWithCloudflare(done.origin, owner, source, session, engine))
    }
}
