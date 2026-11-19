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

private object SemverAsTag : KSerializer<Semver> {
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

/** A stable release of this app's fork: its files by name, and the worker it ships, as its worker.json says, if it's this fork's. */
@Serializable
data class Listed(val tag: Semver, val files: Map<String, String>, val worker: Manifest? = null)

/** The release whose worker this app can deploy: [code] is where its worker.js is. */
data class Deployable(val tag: Semver, val manifest: Manifest, val code: String)

/** This app's fork's stable releases, newest first, down to the newest whose worker this app can deploy. */
@Serializable
data class Releases(val list: List<Listed>) {
    val newest: Listed? get() = list.firstOrNull()

    /** The newest release whose worker speaks an API version up to [Api.VERSION]. */
    val deployable: Deployable?
        get() = list.firstNotNullOfOrNull { l ->
            l.worker?.takeIf { it.version <= Api.VERSION }?.let { m -> l.files[m.mainModule]?.let { Deployable(l.tag, m, it) } }
        }

    /** The newest release, when its worker needs a newer app than this one: newer than [deployable]. */
    val needsNewerApp: Semver?
        get() = deployable?.tag.let { d -> list.takeWhile { it.tag != d }.firstOrNull { it.worker != null }?.tag }

    /** The worker of [deployable], fetched and checked; else [NoRelease] or [ChecksumMismatch]. */
    suspend fun worker(engine: HttpClientEngine? = null): Release {
        val d = deployable ?: throw NoRelease(needsNewerApp)
        val code = client(engine).use { it.fromGitHub(d.code).bodyAsBytes() }
        if (sha256(code).toHexString() != d.manifest.sha256.lowercase()) throw ChecksumMismatch(d.tag)
        return Release(d.tag, d.manifest, code, needsNewerApp)
    }
}

/** What GitHub lists of this app's fork's stable releases, each with its worker.json, down to the newest this app can deploy. */
suspend fun fetchReleases(engine: HttpClientEngine? = null): Releases = client(engine).use { http ->
    val list = mutableListOf<Listed>()
    for ((tag, r) in http.stableReleases()) {
        val files = r.assets.associate { it.name to it.url }
        val manifest = files["worker.json"]?.let { url ->
            val text = http.fromGitHub(url).bodyAsText()
            runCatching { cloudflareJson.decodeFromString<Manifest>(text) }.getOrNull()
        }?.takeIf { it.fork == Api.FORK && (it.build ?: "$tag") == "$tag" }
        list += Listed(tag, files, manifest)
        if (manifest != null && manifest.version <= Api.VERSION && manifest.mainModule in files) break
    }
    Releases(list)
}

/** The newest stable release of this app's fork that this app can deploy; else [NoRelease] or [ChecksumMismatch]. */
suspend fun latestRelease(engine: HttpClientEngine? = null): Release = fetchReleases(engine).worker(engine)

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
    if (!res.status.isSuccess()) throw ApiException("GitHub said no (HTTP ${res.status.value})", res.status.value)
    return res
}

/** The releases of this app's fork that aren't drafts or release candidates, newest first. */
internal suspend fun HttpClient.stableReleases(): List<Pair<Semver, GitHubRelease>> =
    fromGitHub("https://api.github.com/repos/$RELEASES/releases?per_page=100").bodyAsText()
        .let {
            runCatching {
                cloudflareJson.decodeFromString<List<GitHubRelease>>(it)
            }.getOrElse { throw ApiException("GitHub sent something odd", cause = it) }
        }
        .filter { !it.draft && !it.prerelease }
        .mapNotNull { r -> Semver.parse(r.tag)?.let { it to r } }
        .sortedByDescending { it.first }

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
)

@Serializable
internal data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
