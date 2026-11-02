package realm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class FormatTest {
    @Test
    fun readsLifetimes() {
        assertEquals(1.days, parseDuration("1d"))
        assertEquals(12.hours, parseDuration("12h"))
        assertEquals(30.minutes, parseDuration("30m"))
        assertEquals(90.minutes, parseDuration("1h30m"))
        assertEquals(36.hours, parseDuration("1.5d"))
        assertEquals(14.days, parseDuration("2w"))
        for (bad in listOf("", "d", "abc", "5x", "1 d", "0s", "-1h")) assertNull(parseDuration(bad), bad)
    }

    @Test
    fun readsSizes() {
        assertEquals(10L shl 20, parseSize("10M"))
        assertEquals(500L shl 20, parseSize("500MB"))
        assertEquals(2L shl 30, parseSize("2 GB"))
        assertEquals(3L shl 29, parseSize("1.5g"))
        assertEquals(100L, parseSize("100"))
        for (bad in listOf("", "MB", "abc", "0", "-1K")) assertNull(parseSize(bad), bad)
    }

    @Test
    fun writesSizes() {
        assertEquals("820 B", humanSize(820))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("10.0 MB", humanSize(10L shl 20))
        assertEquals("", humanSize(null))
    }

    @Test
    fun putsTextOnOneLine() {
        assertEquals("a b c", oneLine("a\n  b\tc ", 10))
        assertEquals("hell" + '…', oneLine("hello world", 5))
    }

    @Test
    fun describesAnItem() {
        assertEquals("trip.jpg", describe(Item("i1", "image", name = "trip.jpg", created = 0)))
        assertEquals("“buy milk”", describe(Item("i2", "note", text = "buy\nmilk", created = 0)))
        assertEquals("i3", describe(Item("i3", "note", created = 0)))
    }
}
