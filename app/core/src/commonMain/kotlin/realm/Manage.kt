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
    val workersDevOn: Boolean,
    val domains: List<Domain>,
    /** The account's active domains, to add one from. */
    val zones: List<Zone>,
) {
    /** Every address it answers on, as an origin, workers.dev first. */
    val origins: List<String> get() = listOfNotNull(workersDev.takeIf { workersDevOn }) + domains.map { "https://${it.hostname}" }
}

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

/** Takes the worker off [origin], one of [addresses]; never the last one: then [LastAddress]. */
suspend fun CloudflareApi.takeOff(addresses: Addresses, origin: String) {
    val domain = addresses.domains.firstOrNull { "https://${it.hostname}" == origin }
    when {
        domain != null -> removeDomain(addresses.account, domain)
        origin == addresses.workersDev -> setWorkersDev(addresses.account, false)
        else -> throw ApiException("realm doesn't answer on ${origin.removePrefix("https://")}.")
    }
}

class LastAddress(val hostname: String) : ApiException("realm answers only on $hostname, so it stays. Add another address first.")

/** The account [id] as the login sees it, with its name if the login was given it. */
suspend fun CloudflareApi.account(id: String): Account = accounts().firstOrNull { it.id == id } ?: Account(id)
