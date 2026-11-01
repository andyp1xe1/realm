// What a Cloudflare account needs before realm can be deployed there, checked in one go so onboarding can show
// every requirement at once, and the one way to get a plan to deploy from it: only when every one is met.
package realm

import io.ktor.http.HttpMethod

/** Whether R2, where realm keeps files, is turned on for the account. */
sealed interface R2Check {
    data object On : R2Check

    /** Only the user can turn it on, at [R2NotEnabled.URL]. */
    data object Off : R2Check
}

/** The account's workers.dev subdomain, which realm answers on. */
sealed interface SubdomainCheck {
    /** It has one: realm answers on realm.[name].workers.dev. */
    data class Has(val name: String) : SubdomainCheck

    /**
     * It has none yet: one is typed, and deploying gives it to the account first; or opening Workers & Pages in
     * the dashboard, at [URL], gives it one, as Cloudflare says.
     */
    data object Missing : SubdomainCheck {
        const val URL = "https://dash.cloudflare.com/?to=/:account/workers/workers-and-pages"
    }
}

/** Whether there's a D1 database for realm's items, or room for one. */
sealed interface DatabaseCheck {
    /** The database [name] is there already, and deploying uses it. */
    data class Reuses(val name: String) : DatabaseCheck

    /** There's room: the account has [count] of the free plan's [D1_FREE_LIMIT]. */
    data class Room(val count: Int) : DatabaseCheck

    /** The account has [count] databases, as many as the free plan allows: one has to go first. */
    data class Full(val count: Int) : DatabaseCheck {
        companion object {
            const val URL = "https://dash.cloudflare.com/?to=/:account/workers/d1"
        }
    }
}

/** The realm release there is to deploy. */
sealed interface ReleaseCheck {
    data class Ready(val release: Release) : ReleaseCheck

    /** The newest release, [newer], needs a newer app than this one. */
    data class NeedsNewerApp(val newer: Semver) : ReleaseCheck

    /** realm has no release yet. */
    data object None : ReleaseCheck

    /** GitHub couldn't say, or the release isn't what it claims: [message] says which, in words for the user. */
    data class Failed(val message: String) : ReleaseCheck
}

/**
 * What deploying realm to [account] needs, each requirement checked. [deployed] when there's a realm worker
 * on it already, to sign in to instead. [zones] are its domains, to put realm on one of them.
 */
data class Requirements(
    val account: Account,
    val deployed: Boolean,
    val r2: R2Check,
    val subdomain: SubdomainCheck,
    val database: DatabaseCheck,
    val release: ReleaseCheck,
    val zones: List<Zone>,
    /** Of the release's R2 buckets, the ones there already. */
    internal val buckets: Set<String>,
)

/** A fresh deploy every requirement allows: [plan] with [choices], whose address is a valid one. */
data class Deployment(val plan: Plan.Fresh, val choices: Choices)

/**
 * Checks what deploying realm to [account] needs: R2, a workers.dev subdomain, room for a D1 database, and a
 * release, from GitHub with this API's engine. Changes nothing. A release GitHub can't give is a failed
 * requirement, as is the rest; it throws only when Cloudflare can't be asked.
 */
suspend fun CloudflareApi.requirements(account: Account): Requirements {
    val a = "/accounts/${account.id}"
    val release = try {
        ReleaseCheck.Ready(latestRelease(engine))
    } catch (e: NoRelease) {
        e.newer?.let { ReleaseCheck.NeedsNewerApp(it) } ?: ReleaseCheck.None
    } catch (e: ApiException) {
        ReleaseCheck.Failed(e.message ?: "Can't reach GitHub")
    }
    val manifest = (release as? ReleaseCheck.Ready)?.release?.manifest
    val bucketNames = manifest?.buckets?.map { it.bucketName } ?: listOf(Cloudflare.WORKER)
    val databaseNames = manifest?.databases?.map { it.databaseName } ?: listOf(Cloudflare.WORKER)

    val deployed = hasScript(account)
    val subdomain = subdomain(account)?.let { SubdomainCheck.Has(it) } ?: SubdomainCheck.Missing
    val (buckets, r2) = try {
        val all = call<BucketList>(HttpMethod.Get, "$a/r2/buckets?per_page=1000").buckets.map { it.name }.toSet()
        bucketNames.filter { it in all }.toSet() to R2Check.On
    } catch (e: CloudflareError) {
        if (R2_OFF in e.codes) emptySet<String>() to R2Check.Off else throw e
    }
    val missing = databaseNames.filter { database(account, it) == null }
    val database = if (missing.isEmpty()) {
        DatabaseCheck.Reuses(databaseNames.first())
    } else {
        val count = call<List<D1>>(HttpMethod.Get, "$a/d1/database?per_page=$D1_PAGE").size
        if (count + missing.size > D1_FREE_LIMIT) DatabaseCheck.Full(count) else DatabaseCheck.Room(count)
    }
    return Requirements(account, deployed, r2, subdomain, database, release, zones(account), buckets)
}

/** Whether every requirement is met, the address aside. */
val Requirements.met: Boolean
    get() = !deployed && r2 == R2Check.On && database !is DatabaseCheck.Full && release is ReleaseCheck.Ready

/**
 * The deploy [choices] make, when every requirement is met and the address is a valid one: a workers.dev
 * subdomain picked if the account has none, and a custom domain's subdomain, if one is picked, a valid name.
 * Null otherwise: nothing else gives a fresh deploy to start.
 */
fun Requirements.deployment(choices: Choices): Deployment? {
    if (!met) return null
    val release = (release as ReleaseCheck.Ready).release
    val has = (subdomain as? SubdomainCheck.Has)?.name
    val picked = if (has == null) validSubdomain(choices.subdomain.orEmpty()) ?: return null else null
    if (choices.domain?.isValid == false) return null
    val found = Found(
        account = account,
        script = null,
        buckets = buckets,
        databases = (database as? DatabaseCheck.Reuses)?.let { setOf(it.name) }.orEmpty(),
        r2Enabled = true,
        subdomain = has,
        workersDevOn = false,
        zones = zones,
        domains = emptyList(),
    )
    return Deployment(Plan.Fresh(release, found), Choices(subdomain = picked, domain = choices.domain))
}

/**
 * [input] as a workers.dev subdomain or a DNS name's label: lowercased, letters, digits and hyphens, not
 * starting or ending with one, at most 63 long. Null when it isn't one.
 */
fun validSubdomain(input: String): String? = input.trim().lowercase().takeIf { label.matches(it) }

/** Whether the subdomain picked is blank, the domain itself, or valid labels like notes or my.notes. */
val DomainChoice.isValid: Boolean
    get() = name?.trim()?.trim('.')?.ifEmpty { null }?.split('.')?.all { validSubdomain(it) != null } ?: true

private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** How many D1 databases the free plan allows; making one more is refused with 7406. */
const val D1_FREE_LIMIT = 10

/** Enough to tell whether there's room under [D1_FREE_LIMIT]. */
private const val D1_PAGE = 100
