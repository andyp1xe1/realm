// Looking after a realm worker that's deployed: where it answers, whether there's a newer release for it, and
// turning its addresses on and off without leaving it with none.
package realm

import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the realm worker on [account] answers, and the domains it could answer on too. */
@Serializable
data class Addresses(
    val account: Account,
    /** Its workers.dev address, like https://realm.jo.workers.dev, on or off; null when the account has no subdomain. */
    val workersDev: String?,
    /** Whether it answers on [workersDev]. */
    val workersDevOn: Boolean,
    /** The custom domains it answers on. */
    val domains: List<Domain>,
    /** The account's active domains, to add one from. */
    val zones: List<Zone>,
) {
    /** Every address it answers on, as an origin, workers.dev first. */
    val origins: List<String> get() = listOfNotNull(workersDev.takeIf { workersDevOn }) + domains.map { "https://${it.hostname}" }
}

/** Where the realm worker on [account] answers. */
suspend fun CloudflareApi.addresses(account: Account): Addresses {
    val domains = domains(account)
    val workersDev = subdomain(account)?.let { CloudflareApi.workersDev(it) }
    return Addresses(account, workersDev, workersDev != null && workersDevOn(account), domains, zones(account))
}

/** Turns the worker's workers.dev address on or off; never off while it's the only one: then [LastAddress]. */
suspend fun CloudflareApi.setWorkersDev(account: Account, on: Boolean) {
    if (!on) {
        val a = addresses(account)
        val origin = a.workersDev
        if (origin != null && (a.origins - origin).isEmpty()) throw LastAddress(origin.removePrefix("https://"))
    }
    call<JsonElement?>(
        HttpMethod.Post,
        "/accounts/${account.id}/workers/scripts/${Cloudflare.WORKER}/subdomain",
        buildJsonObject {
            put("enabled", on)
            put("previews_enabled", false)
        },
    )
}

/**
 * Takes the worker off [origin], one of [addresses]: a custom domain comes off, workers.dev is turned off.
 * Never the last address the worker answers on: then [LastAddress].
 */
suspend fun CloudflareApi.takeOff(addresses: Addresses, origin: String) {
    val domain = addresses.domains.firstOrNull { "https://${it.hostname}" == origin }
    when {
        domain != null -> removeDomain(addresses.account, domain)
        origin == addresses.workersDev -> setWorkersDev(addresses.account, false)
        else -> throw ApiException("realm doesn't answer on ${origin.removePrefix("https://")}.")
    }
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
