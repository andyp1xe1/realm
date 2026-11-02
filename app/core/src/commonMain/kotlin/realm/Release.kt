// Where the worker the app deploys comes from: the newest release of this app's own repo on GitHub that this
// app understands, checked against the checksum its manifest gives.
package realm

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** worker.json as worker/scripts/build.ts writes it: how to upload the worker.js beside it. */
@Serializable
data class Manifest(
    /** The GitHub "owner/repo" of the fork, or null for realm itself, as in [Info]. */
    val fork: String? = null,
    val version: Int,
    val name: String,
    @SerialName("main_module") val mainModule: String,
    val sha256: String,
    @SerialName("compatibility_date") val compatibilityDate: String,
    @SerialName("r2_buckets") val buckets: List<BucketBinding> = emptyList(),
    @SerialName("d1_databases") val databases: List<DatabaseBinding> = emptyList(),
    val crons: List<String> = emptyList(),
)

@Serializable
data class BucketBinding(val binding: String, @SerialName("bucket_name") val bucketName: String)

@Serializable
data class DatabaseBinding(val binding: String, @SerialName("database_name") val databaseName: String)

/** A release tag's version, vMAJOR.MINOR.PATCH. */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "v$major.$minor.$patch"

    companion object {
        private val tag = Regex("""v(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")

        /** The version a release tag names; null for anything else, like v1.2.0-rc.1. */
        fun parse(tag: String): Semver? =
            this.tag.matchEntire(tag)?.groupValues?.let { Semver(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
    }
}

/**
 * The worker to deploy: [code] is worker.js, checked against [manifest]'s sha256. [newer] is a later release
 * that needs a newer app than this one, if there is one, so the app can say to update it.
 */
class Release(val tag: Semver, val manifest: Manifest, val code: ByteArray, val newer: Semver?)

/** There's no release this app can deploy. With [newer], there is one, but it needs a newer app. */
class NoRelease(val newer: Semver?) :
    ApiException(
        if (newer ==
            null
        ) {
            "There's no realm release to deploy yet."
        } else {
            "The worker's newest release, $newer, needs a newer app. Update the app."
        },
    )

/** A release's worker.js isn't what its worker.json says it is, so it isn't deployed. */
class ChecksumMismatch(val tag: Semver) : ApiException("The worker in release $tag doesn't match its checksum, so it can't be deployed.")

/** The GitHub repo releases come from: this app's own, never one a server names. */
internal val RELEASES = Api.FORK ?: "andyp1xe1/realm"

/**
 * The newest release this app can deploy: of [RELEASES], tagged vMAJOR.MINOR.PATCH, neither draft nor
 * pre-release, built from this app's fork, and of an API version no newer than [Api.VERSION]. Throws
 * [NoRelease] when there's none, and [ChecksumMismatch] when its worker.js isn't the one its manifest names.
 */
suspend fun latestRelease(engine: HttpClientEngine? = null): Release = client(engine).use { http ->
    suspend fun fetch(url: String): HttpResponse {
        val res = try {
            http.get(url) {
                header("User-Agent", "realm")
                header("Accept", "application/vnd.github+json")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Can't reach GitHub", cause = e)
        }
        if (!res.status.isSuccess()) throw ApiException("GitHub said no (HTTP ${res.status.value})", res.status.value)
        return res
    }

    val candidates = fetch("https://api.github.com/repos/$RELEASES/releases?per_page=100").bodyAsText()
        .let {
            runCatching {
                cloudflareJson.decodeFromString<List<GitHubRelease>>(it)
            }.getOrElse { throw ApiException("GitHub sent something odd", cause = it) }
        }
        .filter { !it.draft && !it.prerelease }
        .mapNotNull { r -> Semver.parse(r.tag)?.let { it to r } }
        .sortedByDescending { it.first }
    var newer: Semver? = null
    for ((tag, r) in candidates) {
        val manifestUrl = r.asset("worker.json") ?: continue
        // Release assets come as application/octet-stream, so this reads the JSON itself.
        val text = fetch(manifestUrl).bodyAsText()
        val manifest = runCatching { cloudflareJson.decodeFromString<Manifest>(text) }.getOrNull() ?: continue
        if (manifest.fork != Api.FORK) continue
        if (manifest.version > Api.VERSION) {
            newer = newer ?: tag
            continue
        }
        val codeUrl = r.asset(manifest.mainModule) ?: continue
        val code = fetch(codeUrl).bodyAsBytes()
        if (sha256(code).toHexString() != manifest.sha256.lowercase()) throw ChecksumMismatch(tag)
        return@use Release(tag, manifest, code, newer)
    }
    throw NoRelease(newer)
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    fun asset(name: String) = assets.firstOrNull { it.name == name }?.url
}

@Serializable
private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
