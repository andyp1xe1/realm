package realm

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppReleaseTest {
    private val gh = "api.github.com/repos/andyp1xe1/realm/releases"
    private val dl = "github.com/andyp1xe1/realm/releases/download"
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val apk = "the new app".encodeToByteArray()

    private fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(data)
        sign()
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toHexString()

    private fun apkName(tag: Semver) = "realm-$tag.apk"

    private fun release(tag: String, prerelease: Boolean = false, files: List<String> = listOf("realm-$tag.apk")) = """
        {"tag_name":"$tag","prerelease":$prerelease,"assets":[
          ${(files + "app.json" + "app.json.sig").joinToString(",") {
        """{"name":"$it","browser_download_url":"https://$dl/$tag/$it"}"""
    }}]}
    """

    private fun appJson(tag: String, files: Map<String, String> = mapOf("realm-$tag.apk" to sha256(apk))) =
        """{"tag":"$tag","files":{${files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}"""

    /** GitHub with [releases]; each tag's app.json from [manifests], signed by [signer], and every other file [apk]. */
    private fun github(releases: List<String>, manifests: Map<String, String>, signer: (ByteArray) -> ByteArray = ::sign) =
        FakeClouds { route, _ ->
            val (tag, name) = route.split('/').takeLast(2)
            when {
                route == "GET $gh" -> json("[${releases.joinToString(",")}]")
                name == "app.json" -> manifests[tag]?.let { asset(it.encodeToByteArray()) }
                name == "app.json.sig" -> manifests[tag]?.let { asset(signer(it.encodeToByteArray())) }
                route.startsWith("GET $dl/") -> asset(apk)
                else -> null
            }
        }

    private fun MockRequestHandleScope.asset(body: ByteArray) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf("${body.size}")),
    )

    private suspend fun FakeClouds.newer(installed: String) = newerApp(AppVersion.parse(installed)!!, ::apkName, engine, key)

    @Test
    fun offersTheNewestSignedStableRelease() = runTest {
        val gh = github(
            listOf(release("v1.3.0-rc.1", prerelease = true), release("v1.2.0"), release("v1.1.0")),
            mapOf("v1.3.0-rc.1" to appJson("v1.3.0-rc.1"), "v1.2.0" to appJson("v1.2.0"), "v1.1.0" to appJson("v1.1.0")),
        )
        assertEquals(AppUpdate(Semver(1, 2, 0), "https://$dl/v1.2.0/realm-v1.2.0.apk", sha256(apk)), gh.newer("1.1.0"))
        assertTrue(gh.routes.none { "rc" in it }, gh.routes.toString())
    }

    @Test
    fun aReleaseCandidateIsReplacedByItsRelease() = runTest {
        val gh = github(listOf(release("v1.2.0")), mapOf("v1.2.0" to appJson("v1.2.0")))
        assertEquals(Semver(1, 2, 0), gh.newer("1.2.0-rc.3")?.tag)
        assertNull(gh.newer("1.2.0"))
        assertNull(gh.newer("1.3.0-rc.1"))
    }

    @Test
    fun skipsReleasesWithoutThisFile() = runTest {
        val gh = github(
            listOf(release("v1.3.0", files = emptyList()), release("v1.2.0"), release("v1.1.0", files = emptyList())),
            mapOf("v1.3.0" to appJson("v1.3.0", emptyMap()), "v1.2.0" to appJson("v1.2.0")),
        )
        assertEquals(Semver(1, 2, 0), gh.newer("1.0.0")?.tag)
        assertNull(gh.newer("1.2.0"))
    }

    @Test
    fun refusesAReleaseSignedWithAnotherKey() = runTest {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val forged = { data: ByteArray ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(other.private)
                update(data)
                sign()
            }
        }
        val gh = github(listOf(release("v1.2.0")), mapOf("v1.2.0" to appJson("v1.2.0")), forged)
        assertEquals(Semver(1, 2, 0), assertFailsWith<BadSignature> { gh.newer("1.1.0") }.tag)
    }

    @Test
    fun refusesAnotherReleasesSignedFiles() = runTest {
        val gh = github(listOf(release("v1.2.0")), mapOf("v1.2.0" to appJson("v1.1.0", mapOf("realm-v1.2.0.apk" to sha256(apk)))))
        assertFailsWith<BadSignature> { gh.newer("1.1.0") }
    }

    @Test
    fun refusesGarbageForASignature() = runTest {
        val gh = github(listOf(release("v1.2.0")), mapOf("v1.2.0" to appJson("v1.2.0"))) { "not a signature".encodeToByteArray() }
        assertFailsWith<BadSignature> { gh.newer("1.1.0") }
    }

    @Test
    fun keepsADownloadOnlyWhenItMatches() = runTest {
        val dir = Files.createTempDirectory("realm-update").toFile()
        val into = File(dir, "realm.apk")
        val gh = github(emptyList(), emptyMap())
        val heard = mutableListOf<Float>()
        AppUpdate(Semver(1, 2, 0), "https://$dl/v1.2.0/realm-v1.2.0.apk", sha256(apk)).download(into, gh.engine) { heard += it }
        assertEquals(apk.decodeToString(), into.readText())
        assertEquals(1f, heard.last())

        val damaged = AppUpdate(Semver(1, 2, 0), "https://$dl/v1.2.0/realm-v1.2.0.apk", sha256("something else".encodeToByteArray()))
        assertFailsWith<DamagedDownload> { damaged.download(into, gh.engine) }
        assertFalse(into.exists())
        dir.deleteRecursively()
    }

    @Test
    fun readsTheVersionsBuildsAreGiven() {
        assertEquals(AppVersion(Semver(1, 2, 3)), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(Semver(1, 2, 3), 4), AppVersion.parse("1.2.3-rc.4"))
        assertEquals("1.2.3-rc.4", AppVersion.parse("1.2.3-rc.4").toString())
        for (bad in listOf("0.1", "v1.2.3", "1.2.3-test", "")) assertNull(AppVersion.parse(bad), bad)
    }
}
