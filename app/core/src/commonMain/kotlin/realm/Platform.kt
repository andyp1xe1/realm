package realm

/** The browser, for logins that come back to localhost. Implemented by each app's Platform. */
interface Browser {
    /**
     * Opens [url] in the browser and waits for it to come back to http://localhost:[port][path], as an OAuth
     * login does. Returns the query parameters it came back with.
     */
    suspend fun authorize(url: String, port: Int, path: String): Map<String, String>
}

interface LocalFile {
    val name: String
    val mime: String?
    val size: Long

    /** Opens a fresh blocking reader over the content. */
    fun reader(): ByteReader
}

interface ByteReader : AutoCloseable {
    /** Like InputStream.read: number of bytes read, or -1 at the end. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
}

interface Prefs {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}
