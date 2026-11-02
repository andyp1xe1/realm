package realm

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

/** A token or code: [value] goes only where it's sent, and printing it shows no part of it. Serialized as the bare string. */
@Serializable
@JvmInline
value class Secret(val value: String) {
    override fun toString() = "Secret(redacted)"
}

/** A reply that didn't decode, said by the parser error's [type] and the JSON [path] it stopped at, never the reply's text. */
class Undecodable(e: SerializationException) : Exception() {
    val type: String = e::class.simpleName ?: "SerializationException"
    val path: String? = e.message?.let { pathIn.find(it)?.groupValues?.get(1) }
    override val message get() = "$type at ${path ?: "an unknown path"}"
}

/** Decodes [text], which may hold secrets; a failure throws [Undecodable] instead of the parser's error, which quotes [text]. */
internal inline fun <reified T> Json.decodeQuietly(text: String): T = try {
    decodeFromString<T>(text)
} catch (e: SerializationException) {
    throw Undecodable(e)
}

private val pathIn = Regex("""at path: (\$\S*)""")
