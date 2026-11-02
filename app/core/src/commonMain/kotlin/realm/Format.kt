// Reading and writing the small things people type and see: durations, sizes, one-line previews.
package realm

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private val durationPart = Regex("""(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|ms|s|m|h|d|w)""")

/**
 * A lifetime as typed: "30m", "12h", "7d", "2w", or parts like "1h30m", with fractions like "1.5d".
 * Null unless it's one of those and longer than nothing.
 */
fun parseDuration(text: String): Duration? {
    val s = text.trim()
    if (s.isEmpty()) return null
    var total = Duration.ZERO
    var at = 0
    while (at < s.length) {
        val m = durationPart.matchAt(s, at) ?: return null
        val n = m.groupValues[1].toDouble()
        total += when (m.groupValues[2]) {
            "ns" -> n.nanoseconds
            "us", "µs" -> n.microseconds
            "ms" -> n.milliseconds
            "s" -> n.seconds
            "m" -> n.minutes
            "h" -> n.hours
            "d" -> n.days
            else -> (n * 7).days
        }
        at = m.range.last + 1
    }
    return total.takeIf { it.isPositive() }
}

private val sizeUnits = listOf(
    "TB" to (1L shl 40),
    "GB" to (1L shl 30),
    "MB" to (1L shl 20),
    "KB" to (1L shl 10),
    "B" to 1L,
    "T" to (1L shl 40),
    "G" to (1L shl 30),
    "M" to (1L shl 20),
    "K" to (1L shl 10),
)

/** A size as typed, in bytes: "500MB", "2 GB", "10M", "1.5g", or a plain number of bytes. Null unless it's more than 0. */
fun parseSize(text: String): Long? {
    var s = text.trim().uppercase()
    var unit = 1L
    sizeUnits.firstOrNull { s.endsWith(it.first) }?.let { (suffix, n) ->
        s = s.removeSuffix(suffix).trim()
        unit = n
    }
    val n = s.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
    return (n * unit).toLong()
}

/** A size in the largest unit that keeps it over 1, to a tenth: "820 B", "1.4 MB". Blank for null. */
fun humanSize(n: Long?): String {
    if (n == null) return ""
    var f = n.toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (f >= 1024 && i < units.lastIndex) {
        f /= 1024
        i++
    }
    if (i == 0) return "$n B"
    val r = (f * 10).toLong()
    return "${r / 10}.${r % 10} ${units[i]}"
}

/** A lifetime in its largest whole unit: "30m", "12h", "7d". */
fun ttlLabel(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}

/** [text] on one line, its runs of space and line breaks as single spaces, cut to [max] characters with an ellipsis. */
fun oneLine(text: String, max: Int): String {
    val s = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    if (s.length <= max) return s
    var cut = max - 1
    if (cut > 0 && s[cut - 1].isHighSurrogate()) cut-- // not half an emoji
    return s.take(cut) + '…'
}

/** An item in a few words: its file name, or the start of its text in quotes. */
fun describe(item: Item): String = item.name ?: item.text?.let { "“${oneLine(it, 40)}”" } ?: item.id
