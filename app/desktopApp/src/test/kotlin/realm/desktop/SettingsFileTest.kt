package realm.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFileTest {
    private val tmp = Files.createTempDirectory("realm-settings").toFile()
    private val file = File(tmp, "realm/app.json")

    private fun open() = SettingsFile(file)

    @AfterTest fun cleanUp() {
        tmp.deleteRecursively()
    }

    @Test fun aValueOf100KbRoundTripsAndSurvivesARestart() {
        val big = (0 until 100 * 1024).map { "abc\"\\\n é{}"[it % 10] }.joinToString("")
        open().set("realms", big)
        assertEquals(big, open().get("realms"))
    }

    @Test fun aKeyOver80CharactersWorks() {
        val key = "server-facts:https://" + "a".repeat(200) + ".workers.dev"
        open().set(key, "v")
        assertEquals("v", open().get(key))
    }

    @Test fun valuesAndRemovalsSurviveARestart() {
        open().apply {
            set("links", "[1]")
            set("connection", "c")
            set("connection", null)
        }
        val again = open()
        assertEquals("[1]", again.get("links"))
        assertNull(again.get("connection"))
    }

    @Test fun eachWriteLeavesOneWholeFileOnlyTheUserCanRead() {
        val s = open()
        repeat(20) { s.set("k$it", "v$it") }
        val onDisk = Json.parseToJsonElement(file.readText())
        assertEquals(JsonObject((0 until 20).associate { "k$it" to JsonPrimitive("v$it") }), onDisk)
        assertEquals(listOf("app.json"), file.parentFile.list()!!.toList())
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parentFile.toPath())))
        }
    }

    @Test fun aCorruptFileIsKeptAsideAndTheAppStartsEmpty() {
        file.parentFile.mkdirs()
        val corrupt = """{"cloudflare": "half a lo"""
        file.writeText(corrupt)
        val s = open()
        assertNull(s.get("cloudflare"))
        assertEquals(corrupt, File(file.path + ".bad").readText())
        s.set("links", "[]")
        assertEquals("[]", open().get("links"))
    }

    @Test fun aFileThatIsNotAnObjectOfStringsCountsAsCorrupt() {
        file.parentFile.mkdirs()
        file.writeText("""{"links": 3}""")
        assertNull(open().get("links"))
        assertTrue(File(file.path + ".bad").isFile)
    }
}
