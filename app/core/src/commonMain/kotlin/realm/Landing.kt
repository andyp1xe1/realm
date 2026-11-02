package realm

sealed interface Landed {
    /** [plan] updated the worker in place: the sign-in to it still holds. */
    data class Updated(val plan: Plan.Update) : Landed

    /** Deployed, but not answering yet: sign in to [connection] once it does. */
    data class NotAnswering(val connection: Connection.ByCloudflare) : Landed

    class SignedInTo(val signedIn: SignedIn) : Landed
}

/** May the DNS records in [conflict] go? The worker answers on [origin] either way; [problem] is why the last try failed. */
data class ReplaceQuestion(val conflict: DomainOutcome.Conflict, val origin: String, val problem: ApiException?)

/**
 * Finishes [deployed]: replaces DNS records in the way of its custom domain only while [agrees] says so, then
 * signs in to a fresh worker with [login]. [progress] gets every step so far as it starts.
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
                    done = done.copy(origin = origin, answer = waitForOwner(origin), domain = out)
                    conflict = null
                }
            }
        } catch (e: ApiException) {
            logFailure("Putting realm on $hostname", e)
            problem = e
        }
    }
    val owner = login.copy(account = done.account.id)
    return when {
        plan is Plan.Update -> Landed.Updated(plan)
        done.answer is Answer.NotYet -> Landed.NotAnswering(Connection.ByCloudflare(done.origin, owner))
        else -> Landed.SignedInTo(signInWithCloudflare(done.origin, owner, source, fresh = true, session, engine))
    }
}
