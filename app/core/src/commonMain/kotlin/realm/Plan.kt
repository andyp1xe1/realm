// What deploying a release to an account would do, worked out before anything changes, for the app to show
// and ask about: what's there already, what gets created, and whether to deploy at all.
package realm

import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Something deploying creates or reuses on the account. */
sealed interface Resource {
    val name: String

    data class Bucket(override val name: String) : Resource

    data class Database(override val name: String) : Resource

    /** The worker itself; reused means it's updated in place. */
    data class Script(override val name: String) : Resource
}

/** The realm worker already on the account. */
sealed interface Existing {
    /** It answered at [origin] and said what it runs. */
    data class Answered(val origin: String, val info: Info) : Existing

    /** It's there, but what it runs couldn't be told: [why] says why, in words for the user. */
    data class Silent(val origin: String?, val why: String) : Existing
}

/** What's on the account now, as far as deploying cares. */
data class Found(
    val account: Account,
    /** Null when there's no realm worker yet. */
    val script: Existing?,
    /** Of the release's R2 buckets, the ones that exist. */
    val buckets: Set<String>,
    /** Of the release's D1 databases, the ones that exist, by exact name. */
    val databases: Set<String>,
    /** False when R2 is off on the account; deploying then stops at the bucket and says to turn it on. */
    val r2Enabled: Boolean,
    /** The account's workers.dev subdomain; null when it has none, so one has to be picked. */
    val subdomain: String?,
    /** Whether the worker answers on its workers.dev address now. */
    val workersDevOn: Boolean,
    /** The account's active domains, to pick a custom domain from. */
    val zones: List<Zone>,
    /** The custom domains the worker answers on now. */
    val domains: List<Domain>,
)

/** Why a release isn't deployed over the worker that's there. */
sealed interface Refusal {
    val message: String

    /** The worker is newer than the release: deploying would downgrade it. */
    data class Newer(val info: Info) : Refusal {
        override val message get() = "That worker runs version ${info.version}, newer than this release. Update the app."
    }

    /** The worker runs another fork's code, which realm's release would overwrite. */
    data class OtherFork(val info: Info) : Refusal {
        override val message get() = "That worker runs ${info.fork ?: "realm"}, not ${Api.FORK ?: "realm"}, so it isn't replaced."
    }

    /** It runs this release's version already. */
    data class Current(val info: Info) : Refusal {
        override val message get() = "That worker is up to date."
    }

    /** What it runs couldn't be told, so it isn't overwritten. */
    data class Unknown(override val message: String) : Refusal
}

/** What deploying [release] would do on [found]'s account, for a confirm dialog. */
sealed interface Plan {
    val release: Release
    val found: Found

    /** A plan that can go ahead, with [deploy]. */
    sealed interface Ready : Plan

    /** No realm worker yet: everything is set up from nothing. */
    data class Fresh(override val release: Release, override val found: Found) : Ready

    /** The worker runs [from], an older version of the same fork: it's updated in place, keeping its data and secrets. */
    data class Update(override val release: Release, override val found: Found, val from: Info) : Ready

    data class Refused(override val release: Release, override val found: Found, val reason: Refusal) : Plan

    /** What deploying creates. */
    val create: List<Resource> get() = resources().filter { !it.second }.map { it.first }

    /** What deploying keeps and reuses, the worker itself updated in place. */
    val reuse: List<Resource> get() = resources().filter { it.second }.map { it.first }

    private fun resources(): List<Pair<Resource, Boolean>> =
        release.manifest.buckets.map { Resource.Bucket(it.bucketName) to (it.bucketName in found.buckets) } +
            release.manifest.databases.map { Resource.Database(it.databaseName) to (it.databaseName in found.databases) } +
            (Resource.Script(Cloudflare.WORKER) to (found.script != null))
}

/** Whether to deploy [release] over the worker [found] has: never over another fork, a newer worker, or one that can't say. */
fun decide(release: Release, found: Found): Plan = when (val script = found.script) {
    null -> Plan.Fresh(release, found)

    is Existing.Silent -> Plan.Refused(release, found, Refusal.Unknown(script.why))

    is Existing.Answered -> {
        val info = script.info
        when {
            info.fork != release.manifest.fork -> Plan.Refused(release, found, Refusal.OtherFork(info))
            info.version > release.manifest.version -> Plan.Refused(release, found, Refusal.Newer(info))
            info.version == release.manifest.version -> Plan.Refused(release, found, Refusal.Current(info))
            else -> Plan.Update(release, found, info)
        }
    }
}

/** Looks at [account] and works out what deploying [release] there would do. Changes nothing. */
suspend fun CloudflareApi.plan(account: Account, release: Release): Plan {
    val a = "/accounts/${account.id}"
    val subdomain = subdomain(account)
    val domains = domains(account)
    val hasScript = hasScript(account)
    val workersDevOn = hasScript && workersDevOn(account)
    val script = if (hasScript) existing(account, domains, subdomain.takeIf { workersDevOn }) else null
    val (buckets, r2Enabled) = try {
        call<BucketList>(HttpMethod.Get, "$a/r2/buckets?per_page=1000").buckets.map { it.name }.toSet() to true
    } catch (e: CloudflareError) {
        if (R2_OFF in e.codes) emptySet<String>() to false else throw e
    }
    val databases = release.manifest.databases.map { it.databaseName }.filter { database(account, it) != null }.toSet()
    val found = Found(
        account = account,
        script = script,
        buckets = release.manifest.buckets.map { it.bucketName }.filter { it in buckets }.toSet(),
        databases = databases,
        r2Enabled = r2Enabled,
        subdomain = subdomain,
        workersDevOn = workersDevOn,
        zones = zones(account),
        domains = domains,
    )
    return decide(release, found)
}

/** What the worker that's there runs, asked at its address with this login as its owner. */
private suspend fun CloudflareApi.existing(account: Account, domains: List<Domain>, subdomain: String?): Existing {
    val origin = domains.firstOrNull()?.let { "https://${it.hostname}" } ?: subdomain?.let { CloudflareApi.workersDev(it) }
        ?: return Existing.Silent(null, "That worker has no address to ask what it runs: turn on its workers.dev address.")
    val api = Api.server(origin, bearer, "deploy", engine)
    return try {
        Existing.Answered(origin, api.info())
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudflareRejected) {
        throw e
    } catch (e: ApiException) {
        Existing.Silent(
            origin,
            when {
                e.status == 401 -> "That worker doesn't take Cloudflare logins yet. Log in to it first, so it can say what it runs."
                e.status == 404 -> "That worker is too old to say what it runs."
                else -> "That worker didn't say what it runs: ${e.message}"
            },
        )
    } catch (e: Exception) {
        Existing.Silent(origin, "Can't reach that worker at $origin to ask what it runs.")
    } finally {
        api.close()
    }
}

/** The D1 database called exactly [name], if there is one. The name filter matches parts of names too. */
internal suspend fun CloudflareApi.database(account: Account, name: String): D1? =
    call<List<D1>>(HttpMethod.Get, "/accounts/${account.id}/d1/database?name=${name.encodeURLParameter()}")
        .firstOrNull { it.name == name }

@Serializable
internal data class D1(val uuid: String, val name: String)

@Serializable
private data class BucketList(val buckets: List<Named> = emptyList())

@Serializable
private data class Named(val name: String)

/** Unverified: what R2 is believed to answer on an account that hasn't turned it on. */
internal const val R2_OFF = 10042
