package realm

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** worker.json as worker/scripts/build.ts writes it: how to upload the worker.js beside it. */
@Serializable
data class Manifest(
    /** The GitHub "owner/repo" of the fork, or null for realm itself. */
    val fork: String? = null,
    val version: Int,
    val name: String,
    @SerialName("main_module") val mainModule: String,
    val sha256: String,
    @SerialName("compatibility_date") val compatibilityDate: String,
    @SerialName("r2_buckets") val buckets: List<BucketBinding> = emptyList(),
    @SerialName("d1_databases") val databases: List<DatabaseBinding> = emptyList(),
    val crons: List<String> = emptyList(),
    /** The release tag the worker says it runs, when it was built for one. */
    val build: String? = null,
)

@Serializable
data class BucketBinding(val binding: String, @SerialName("bucket_name") val bucketName: String)

@Serializable
data class DatabaseBinding(val binding: String, @SerialName("database_name") val databaseName: String)

/** A release tag's version, vMAJOR.MINOR.PATCH. */
@Serializable(with = SemverAsTag::class)
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

internal object SemverAsTag : KSerializer<Semver> {
    override val descriptor = PrimitiveSerialDescriptor("realm.Semver", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Semver) = encoder.encodeString("$value")

    override fun deserialize(decoder: Decoder): Semver =
        decoder.decodeString().let { Semver.parse(it) ?: throw SerializationException("$it isn't a release tag") }
}

/** The worker to deploy: [code] is worker.js, checked against [manifest]; [newer] is a later release that needs a newer app. */
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

class ChecksumMismatch(val tag: Semver) : ApiException("The worker in release $tag doesn't match its checksum, so it can't be deployed.")

internal val RELEASES = Api.FORK ?: "andyp1xe1/realm"

/**
 * A stable release of this app's fork, by its GitHub tag [name], with its files by name, and the worker it ships, as
 * its worker.json says, if it's this fork's.
 */
@Serializable
data class Listed(val name: String, val tag: Semver, val files: Map<String, String>, val worker: Manifest? = null)

/** The release whose worker this app can deploy: [code] is where its worker.js is. */
data class Deployable(val tag: Semver, val manifest: Manifest, val code: String)

/**
 * This app's fork's stable releases, newest first: every release of the app, and the worker's down to the newest
 * this app can deploy.
 */
@Serializable
data class Releases(val apps: List<Listed>, val workers: List<Listed>) {
    /** The newest worker release whose worker speaks a protocol up to [Api.VERSION]. */
    val deployable: Deployable?
        get() = workers.firstNotNullOfOrNull { l ->
            l.worker?.takeIf { it.version <= Api.VERSION }?.let { m -> l.files[m.mainModule]?.let { Deployable(l.tag, m, it) } }
        }

    /** The newest worker release, when its worker speaks a newer protocol than this app: newer than [deployable]. */
    val needsNewerApp: Listed?
        get() = deployable?.tag.let { d -> workers.takeWhile { it.tag != d }.firstOrNull { it.worker != null } }

    /** The worker of [deployable], fetched and checked; else [NoRelease] or [ChecksumMismatch]. */
    suspend fun worker(engine: HttpClientEngine? = null): Release {
        val newer = needsNewerApp?.tag
        val d = deployable ?: throw NoRelease(newer)
        val code = client(engine).use { it.fromGitHub(d.code).bodyAsBytes() }
        if (sha256(code).toHexString() != d.manifest.sha256.lowercase()) throw ChecksumMismatch(d.tag)
        return Release(d.tag, d.manifest, code, newer)
    }
}

enum class ReleasePart { App, Worker }

/** What GitHub lists of this app's fork's stable releases, each worker release with its worker.json, as [Releases] keeps them. */
suspend fun fetchReleases(engine: HttpClientEngine? = null): Releases = client(engine).use { http ->
    val apps = mutableListOf<Listed>()
    val workers = mutableListOf<Listed>()
    for (r in http.stableReleases()) {
        val files = r.release.assets.associate { it.name to it.url }
        if (ReleasePart.App in r.parts) apps += Listed(r.name, r.tag, files)
        if (ReleasePart.Worker in r.parts && workers.none { it.worker.deployable(it.files) }) {
            val manifest = files["worker.json"]?.let { url ->
                val text = http.fromGitHub(url).bodyAsText()
                runCatching { cloudflareJson.decodeFromString<Manifest>(text) }.getOrNull()
            }?.takeIf { it.fork == Api.FORK && (it.build ?: "${r.tag}") == "${r.tag}" }
            workers += Listed(r.name, r.tag, files, manifest)
        }
    }
    Releases(apps, workers)
}

private fun Manifest?.deployable(files: Map<String, String>) = this != null && version <= Api.VERSION && mainModule in files

/** The newest stable release of this app's fork that this app can deploy; else [NoRelease] or [ChecksumMismatch]. */
suspend fun latestRelease(engine: HttpClientEngine? = null): Release = fetchReleases(engine).worker(engine)

/** GitHub turned this network away for asking too often, until [until], in Unix seconds. */
class GitHubBusy(val until: Long, now: Long = Cloudflare.now()) :
    ApiException(
        "GitHub is limiting this network for now. realm asks again by itself in about " +
            ((until - now + 59) / 60).let { if (it <= 1) "a minute." else "$it minutes." },
    )

internal suspend fun HttpClient.fromGitHub(url: String): HttpResponse {
    val res = try {
        get(url) {
            header("User-Agent", "realm")
            header("Accept", "application/vnd.github+json")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiException("Can't reach GitHub", cause = e)
    }
    val status = res.status.value
    val retry = res.headers["retry-after"]?.toLongOrNull()
    if ((status == 403 || status == 429) && (res.headers["x-ratelimit-remaining"] == "0" || retry != null)) {
        throw GitHubBusy(res.headers["x-ratelimit-reset"]?.toLongOrNull() ?: (Cloudflare.now() + (retry ?: 60)))
    }
    if (!res.status.isSuccess()) throw ApiException("GitHub said no (HTTP $status)", status)
    return res
}

internal data class Tagged(val name: String, val parts: Set<ReleasePart>, val tag: Semver, val release: GitHubRelease)

internal suspend fun HttpClient.stableReleases(): List<Tagged> = listing()
    .let {
        runCatching {
            cloudflareJson.decodeFromString<List<GitHubRelease>>(it)
        }.getOrElse { throw ApiException("GitHub sent something odd", cause = it) }
    }
    .filter { !it.draft && !it.prerelease }
    .mapNotNull { r ->
        val (parts, version) = when {
            r.tag.startsWith("app/") -> setOf(ReleasePart.App) to r.tag.removePrefix("app/")
            r.tag.startsWith("worker/") -> setOf(ReleasePart.Worker) to r.tag.removePrefix("worker/")
            else -> setOf(ReleasePart.App, ReleasePart.Worker) to r.tag
        }
        Semver.parse(version)?.let { Tagged(r.tag, parts, it, r) }
    }
    .sortedByDescending { it.tag }

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
)

@Serializable
internal data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

private suspend fun HttpClient.listing(): String = try {
    fromGitHub("https://github.com/$RELEASES/releases/latest/download/releases.json").bodyAsText()
} catch (e: ApiException) {
    if (e.status != 404) throw e
    fromGitHub("https://api.github.com/repos/$RELEASES/releases?per_page=100").bodyAsText()
}
