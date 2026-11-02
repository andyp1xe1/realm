package realm

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Items' pictures as decoded, by the item's cache key and the size decoded to; the latest [KEPT] stay. */
class Bitmaps(private val platform: Platform) {
    private val decoded = mutableStateMapOf<Pair<String, Int>, ImageBitmap>()
    private val order = ArrayDeque<Pair<String, Int>>()
    private val loading = mutableSetOf<Pair<String, Int>>()

    operator fun get(key: String, size: Int): ImageBitmap? = decoded[key to size]

    internal fun put(key: String, size: Int, bitmap: ImageBitmap) {
        decoded[key to size] = bitmap
        order.addLast(key to size)
        while (order.size > KEPT) decoded.remove(order.removeFirst())
    }

    /** Decodes [item]'s picture from [api] to [size], from the disk cache, else downloaded when [download]. */
    internal suspend fun load(api: Api, item: Item, size: Int, download: Boolean) {
        val at = api.cacheKey(item.id) to size
        if (at in decoded || at in loading || (item.size ?: 0) > Model.MAX_INLINE_IMAGE) return
        loading += at
        try {
            val bytes = imageBytes(platform, api, item.id, download) ?: return
            withContext(Dispatchers.Default) { platform.decodeImage(bytes, size) }?.let { put(at.first, size, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading -= at
        }
    }

    companion object {
        /** The size a feed shows a picture at. */
        const val FULL = 1280

        /** The size a tile shows a picture at. */
        const val THUMB = 480

        const val KEPT = 200
    }
}
