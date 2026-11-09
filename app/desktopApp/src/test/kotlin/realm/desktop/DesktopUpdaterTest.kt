package realm.desktop

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import realm.ApiException
import realm.AppUpdate
import realm.AppVersion
import realm.FakeClouds
import realm.Semver
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdaterTest {
    private val tmp = Files.createTempDirectory("realm-updater").toFile()
    private val appImage = File(tmp, "realm-v1.1.0-x86_64.AppImage").apply { writeText("the old app") }
    private val new = "the new app".encodeToByteArray()
    private val github = FakeClouds { _, _ -> respond(new, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${new.size}")) }
    private val update = AppUpdate(
        Semver(1, 2, 0),
        "https://github.com/andyp1xe1/realm/releases/download/v1.2.0/realm-v1.2.0-x86_64.AppImage",
        MessageDigest.getInstance("SHA-256").digest(new).toHexString(),
    )

    private fun updater(target: File = appImage) =
        DesktopUpdater(AppVersion.parse("1.1.0")!!, target, DesktopUpdater.Kind.AppImage, github.engine)

    @AfterTest fun cleanUp() {
        File(tmp, "ro").setWritable(true)
        tmp.deleteRecursively()
    }

    @Test fun namesTheReleaseFileItRunsAs() {
        assertEquals("realm-v1.2.0-x86_64.AppImage", updater().file(Semver(1, 2, 0)))
        assertEquals(
            "realm-v1.2.0-linux-x64.jar",
            DesktopUpdater(updater().version, appImage, DesktopUpdater.Kind.Jar).file(Semver(1, 2, 0)),
        )
    }

    @Test fun replacesTheAppInPlaceOnlyOnceInstalled() = runBlocking {
        var before: String? = null
        assertTrue(updater().install(update, {}) { before = appImage.readText() })
        assertEquals("the old app", before)
        assertEquals("the new app", appImage.readText())
        assertTrue(appImage.canExecute())
        assertEquals(listOf(appImage.name), tmp.list()!!.toList())
    }

    @Test fun saysSoWhenItCannotWriteWhereTheAppIs() = runBlocking {
        val dir = File(tmp, "ro").apply { mkdirs() }
        val target = File(dir, appImage.name).apply { writeText("the old app") }
        dir.setWritable(false)
        assertFailsWith<ApiException> { updater(target).install(update, {}) {} }
        assertEquals("the old app", target.readText())
        assertFalse(github.seen.any())
    }
}
