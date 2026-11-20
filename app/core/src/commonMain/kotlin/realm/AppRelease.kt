package realm

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.statement.bodyAsBytes
import kotlinx.serialization.Serializable

/** A build of the app as its version names it: 1.2.0, or 1.2.0-rc.3 for a release candidate. */
data class AppVersion(val release: Semver, val candidate: Int? = null) : Comparable<AppVersion> {
    /** Whether release [tag] replaces this build: a later release, or the release this candidate leads up to. */
    fun olderThan(tag: Semver): Boolean = tag > release || (tag == release && candidate != null)

    /** By release, a candidate coming before its release. */
    override fun compareTo(other: AppVersion) =
        compareValuesBy(this, other, { it.release }, { it.candidate == null }, { it.candidate ?: 0 })

    override fun toString() = "$release".removePrefix("v") + (candidate?.let { "-rc.$it" } ?: "")

    companion object {
        private val version = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-rc\.(\d{1,6}))?""")

        /** The version a build was given, like 1.2.0-rc.3; null for anything else, like a build from source. */
        fun parse(version: String): AppVersion? = this.version.matchEntire(version)?.groupValues?.let {
            AppVersion(Semver(it[1].toInt(), it[2].toInt(), it[3].toInt()), it[4].toIntOrNull())
        }
    }
}

/**
 * The app file at [url] in release [tag], whose SHA-256 is [sha256] as the release's signed app.json says, with the
 * [protocol] it speaks when the release says.
 */
data class AppUpdate(val tag: Semver, val url: String, val sha256: String, val protocol: Int? = null)

/**
 * What a look for a newer app found: the release newer than the copy that looked, or null when there was none, and
 * the [protocol] it speaks when it says, with the [newest] app release listed then; [by] is the version that looked, and only it or a newer copy can go by it.
 */
@Serializable
data class AppLook(val newer: Semver?, val newest: Semver?, val protocol: Int? = null, val by: String? = null)

/** An update of the app and the server asked for, with the [app] release installed for it so far. */
@Serializable
data class UpdateAsked(val app: Semver?)

@Serializable
private data class AppFiles(val tag: String, val files: Map<String, String>, val protocol: Int? = null)

class BadSignature(val tag: Semver) : ApiException("Release $tag isn't signed with this app's key, so it isn't installed.")

class DamagedDownload(val tag: Semver) : ApiException("The download of release $tag doesn't match what the release says. Try again.")

/**
 * The newest of [releases] that's newer than [installed] and has a file named [file] for its tag, as its app.json
 * says, signed with [key]; null when there's none. A release that isn't signed so is [BadSignature].
 */
suspend fun newerApp(
    installed: AppVersion,
    file: (Semver) -> String,
    releases: Releases,
    engine: HttpClientEngine? = null,
    key: String = Api.APP_KEY,
): AppUpdate? = client(engine).use { http ->
    for (r in releases.apps) {
        if (!installed.olderThan(r.tag)) break
        val filesUrl = r.files["app.json"] ?: continue
        val signatureUrl = r.files["app.json.sig"] ?: throw BadSignature(r.tag)
        val text = http.fromGitHub(filesUrl).bodyAsBytes()
        if (!verifies(key, text, http.fromGitHub(signatureUrl).bodyAsBytes())) throw BadSignature(r.tag)
        val files = runCatching { cloudflareJson.decodeFromString<AppFiles>(text.decodeToString()) }.getOrNull()
        if (files?.tag != r.name) throw BadSignature(r.tag)
        val name = file(r.tag)
        val sha256 = files.files[name] ?: continue
        return@use AppUpdate(r.tag, r.files[name] ?: continue, sha256.lowercase(), files.protocol)
    }
    null
}

/** Whether [signature] is [publicKey]'s ECDSA P-256 signature over the SHA-256 of [data]. */
internal expect fun verifies(publicKey: String, data: ByteArray, signature: ByteArray): Boolean
