package realm.jvm

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StorageTest {
    private val tmp = Files.createTempDirectory("realm-storage").toFile()

    @AfterTest fun cleanUp() {
        tmp.deleteRecursively()
    }

    @Test fun cacheKeysThatDifferOnlyPastTheirFirst150CharactersAreKeptApart() {
        val cache = DiskCache(File(tmp, "cache"), maxBytes = 1 shl 20)
        val prefix = "h".repeat(160)
        cache.put("${prefix}a", byteArrayOf(1))
        cache.put("${prefix}b", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), cache.get("${prefix}a"))
        assertContentEquals(byteArrayOf(2), cache.get("${prefix}b"))
    }

    @Test fun cacheKeysThatDifferOnlyInPunctuationAreKeptApart() {
        val cache = DiskCache(File(tmp, "cache"), maxBytes = 1 shl 20)
        cache.put("a/b", byteArrayOf(1))
        cache.put("a:b", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), cache.get("a/b"))
        assertContentEquals(byteArrayOf(2), cache.get("a:b"))
    }

    @Test fun overwritingACachedValueReplacesItAndLeavesOneFile() {
        val dir = File(tmp, "cache")
        val cache = DiskCache(dir, maxBytes = 1 shl 20)
        cache.put("k", byteArrayOf(1))
        cache.put("k", byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(2, 3), DiskCache(dir, maxBytes = 1 shl 20).get("k"))
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun overwritingAStoredStringReplacesItAndLeavesOneFile() {
        val dir = File(tmp, "store")
        FileStore(dir).set("feed/x", "old")
        FileStore(dir).set("feed/x", "new")
        assertEquals("new", FileStore(dir).get("feed/x"))
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun aStringStoredByEarlierVersionsIsStillRead() {
        val dir = File(tmp, "store").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest("feed/x".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        File(dir, "feed_x-$hash").writeText("kept")
        assertEquals("kept", FileStore(dir).get("feed/x"))
    }
}
