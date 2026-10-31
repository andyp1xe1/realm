// Looking after a realm worker that's deployed: where it answers, whether there's a newer release for it, and
// taking a custom domain off it without leaving it with no address.
package realm

/** Where the realm worker on [account] answers, and the domains it could answer on too. */
data class Addresses(
    val account: Account,
    /** Its workers.dev address, like https://realm.jo.workers.dev, while that's on. */
    val workersDev: String?,
    /** The custom domains it answers on. */
    val domains: List<Domain>,
    /** The account's active domains, to add one from. */
    val zones: List<Zone>,
) {
    /** Every address as an origin, workers.dev first. */
    val origins: List<String> get() = listOfNotNull(workersDev) + domains.map { "https://${it.hostname}" }
}

/** Where the realm worker on [account] answers. */
suspend fun CloudflareApi.addresses(account: Account): Addresses {
    val domains = domains(account)
    val workersDev = if (workersDevOn(account)) subdomain(account)?.let { CloudflareApi.workersDev(it) } else null
    return Addresses(account, workersDev, domains, zones(account))
}

/** [hostname] is the only address the worker answers on: taking it off would leave nobody able to reach it. */
class LastAddress(val hostname: String) : ApiException("realm answers only on $hostname, so it stays. Add another address first.")

/** What checking for a newer worker came to. */
sealed interface UpdateCheck {
    /** The worker runs [release]'s version already. */
    data class UpToDate(val release: Release, val info: Info) : UpdateCheck

    /** A newer release of the same fork: [plan] deploys it. */
    data class Available(val plan: Plan.Update) : UpdateCheck

    /** There's a release, but it isn't deployed over this worker: [plan] says why. */
    data class Refused(val plan: Plan.Refused) : UpdateCheck

    /** The newest release, [newer], needs a newer app than this one to deploy it. */
    data class NeedsNewerApp(val newer: Semver) : UpdateCheck
}

/** Looks for a newer release for the realm worker on [account] now, and what deploying it would do. Changes nothing. */
suspend fun CloudflareApi.checkUpdate(account: Account): UpdateCheck {
    val release = try {
        latestRelease(engine)
    } catch (e: NoRelease) {
        val newer = e.newer ?: throw e
        return UpdateCheck.NeedsNewerApp(newer)
    }
    return when (val plan = plan(account, release)) {
        is Plan.Update -> UpdateCheck.Available(plan)

        is Plan.Refused -> {
            val reason = plan.reason
            when {
                reason !is Refusal.Current -> UpdateCheck.Refused(plan)
                release.newer != null -> UpdateCheck.NeedsNewerApp(release.newer)
                else -> UpdateCheck.UpToDate(release, reason.info)
            }
        }

        is Plan.Fresh -> throw ApiException("There's no realm worker on that account any more.")
    }
}

/** Whether a worker that says [info] should be offered release [version]: only an older one of this app's fork. */
fun offersUpdate(info: Info, version: Int): Boolean = info.fork == Api.FORK && info.version < version

/** The account [id] as the login sees it, with its name if the login was given it. */
suspend fun CloudflareApi.account(id: String): Account = accounts().firstOrNull { it.id == id } ?: Account(id)
