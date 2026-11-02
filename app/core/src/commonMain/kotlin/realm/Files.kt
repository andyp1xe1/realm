package realm

/** Something to upload: a file the user picked, a photo, a recording. */
interface LocalFile {
    val name: String
    val mime: String?

    /** In bytes; negative when it isn't known up front, as for a pipe. */
    val size: Long

    /** Opens a fresh blocking reader over the content. */
    fun reader(): ByteReader
}

interface ByteReader : AutoCloseable {
    /** Like InputStream.read: number of bytes read, or -1 at the end. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
}

/** Where a download goes, a piece at a time: a file, or stdout. Blocking. */
fun interface ByteWriter {
    /** Like OutputStream.write: all [len] bytes from [off], or throws. */
    fun write(buf: ByteArray, off: Int, len: Int)
}
