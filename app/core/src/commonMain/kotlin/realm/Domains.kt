package realm

import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A custom domain to put the worker on: [name] under [zone], like "notes" for notes.jo.dev, or the zone itself when null. */
data class DomainChoice(val zone: Zone, val name: String? = null) {
    val hostname: String get() = name?.trim()?.trim('.')?.lowercase()?.ifEmpty { null }?.let { "$it.${zone.name}" } ?: zone.name
}

sealed interface DomainOutcome {
    data class Attached(val domain: Domain) : DomainOutcome

    /** DNS records Cloudflare won't replace stand on the hostname; pass this back to [attachDomain] to delete exactly [records]. */
    data class Conflict(val choice: DomainChoice, val records: List<DnsRecord>) : DomainOutcome
}

/** The account's active domains, to pick a custom domain from. */
suspend fun CloudflareApi.zones(account: Account): List<Zone> {
    val all = mutableListOf<Zone>()
    var page = 1
    while (true) {
        val got = call<List<Zone>>(HttpMethod.Get, "/zones?account.id=${account.id}&per_page=$ZONES_PAGE&page=$page")
        all += got
        if (got.size < ZONES_PAGE) break
        page++
    }
    return all.filter { it.status == "active" }.sortedBy { it.name }
}

/**
 * Puts the realm worker on [choice]'s hostname, or returns a [DomainOutcome.Conflict] and changes nothing. Given
 * [replace], deletes only the records it lists. The certificate can't be read with this login: poll HTTPS.
 */
suspend fun CloudflareApi.attachDomain(account: Account, choice: DomainChoice, replace: DomainOutcome.Conflict? = null): DomainOutcome {
    val body = buildJsonObject {
        put("hostname", choice.hostname)
        put("service", Cloudflare.WORKER)
        put("zone_id", choice.zone.id)
        put("environment", "production")
    }
    suspend fun attach(): Domain? = try {
        call<Domain>(HttpMethod.Put, "/accounts/${account.id}/workers/domains", body)
    } catch (e: CloudflareError) {
        if (DNS_IN_THE_WAY in e.codes) null else throw e
    }
    attach()?.let { return DomainOutcome.Attached(it) }
    val records = dnsRecords(choice)
    val agreed = replace?.takeIf { it.choice.hostname == choice.hostname }?.records?.map { it.id }?.toSet()
    if (agreed == null || records.any { it.id !in agreed }) return DomainOutcome.Conflict(choice, records)
    for (r in records) call<JsonElement>(HttpMethod.Delete, "/zones/${choice.zone.id}/dns_records/${r.id}")
    return attach()?.let { DomainOutcome.Attached(it) } ?: DomainOutcome.Conflict(choice, dnsRecords(choice))
}

/** Takes the realm worker off [domain], unless it's the last address the worker answers on: then [LastAddress]. */
suspend fun CloudflareApi.removeDomain(account: Account, domain: Domain) {
    val left = addresses(account).origins - "https://${domain.hostname}"
    if (left.isEmpty()) throw LastAddress(domain.hostname)
    call<JsonElement?>(HttpMethod.Delete, "/accounts/${account.id}/workers/domains/${domain.id}")
}

private suspend fun CloudflareApi.dnsRecords(choice: DomainChoice): List<DnsRecord> =
    call(HttpMethod.Get, "/zones/${choice.zone.id}/dns_records?name=${choice.hostname.encodeURLParameter()}")

private const val ZONES_PAGE = 50
internal const val DNS_IN_THE_WAY = 100117
