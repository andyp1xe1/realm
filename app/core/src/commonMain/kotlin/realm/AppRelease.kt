package realm

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.statement.bodyAsBytes
import kotlinx.serialization.Serializable

/** A build of the app as its version names it: 1.2.0, or 1.2.0-rc.3 for a release candidate. */
data class AppVersion(val release: Semver, val candidate: Int? = null) {
    /** Whether release [tag] replaces this build: a later release, or the release this candidate leads up to. */
    fun olderThan(tag: Semver): Boolean = tag > release || (tag == release && candidate != null)

    override fun toString() = "$release".removePrefix("v") + (candidate?.let { "-rc.$it" } ?: "")

    companion object {
        private val version = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-rc\.(\d{1,6}))?""")

        /** The version a build was given, like 1.2.0-rc.3; null for anything else, like a build from source. */
        fun parse(version: String): AppVersion? = this.version.matchEntire(version)?.groupValues?.let {
            AppVersion(Semver(it[1].toInt(), it[2].toInt(), it[3].toInt()), it[4].toIntOrNull())
        }
    }
}

/** The app file at [url] in release [tag], whose SHA-256 is [sha256] as the release's signed app.json says. */
data class AppUpdate(val tag: Semver, val url: String, val sha256: String)

/** app.json as the release workflow writes and signs it: each file in release [tag] by name, with its SHA-256. */
@Serializable
private data class AppFiles(val tag: String, val files: Map<String, String>)

class BadSignature(val tag: Semver) : ApiException("Release $tag isn't signed with this app's key, so it isn't installed.")

class DamagedDownload(val tag: Semver) : ApiException("The download of release $tag doesn't match what the release says. Try again.")

/**
 * The newest stable release of this app's fork that's newer than [installed] and has a file named [file] for its tag,
 * as its app.json says, signed with [key]; null when there's none. A release that isn't signed so is [BadSignature].
 */
suspend fun newerApp(
    installed: AppVersion,
    file: (Semver) -> String,
    engine: HttpClientEngine? = null,
    key: String = Api.APP_KEY,
): AppUpdate? = client(engine).use { http ->
    for ((tag, r) in http.stableReleases()) {
        if (!installed.olderThan(tag)) break
        val filesUrl = r.asset("app.json") ?: continue
        val signatureUrl = r.asset("app.json.sig") ?: throw BadSignature(tag)
        val text = http.fromGitHub(filesUrl).bodyAsBytes()
        if (!verifies(key, text, http.fromGitHub(signatureUrl).bodyAsBytes())) throw BadSignature(tag)
        val files = runCatching { cloudflareJson.decodeFromString<AppFiles>(text.decodeToString()) }.getOrNull()
        if (files?.tag != "$tag") throw BadSignature(tag)
        val name = file(tag)
        val sha256 = files.files[name] ?: continue
        return@use AppUpdate(tag, r.asset(name) ?: continue, sha256.lowercase())
    }
    null
}

/** Whether [signature] is [publicKey]'s ECDSA P-256 signature over the SHA-256 of [data]. */
internal expect fun verifies(publicKey: String, data: ByteArray, signature: ByteArray): Boolean
