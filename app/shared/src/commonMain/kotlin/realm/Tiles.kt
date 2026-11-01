// What the overview's tiles show of each space: its latest few items, kept between runs so the overview shows
// them at once, then refreshed in place, a tile changing only when what it shows did; and their pictures.
package realm

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

class Tiles internal constructor(private val platform: Platform) {
    /** The latest few items of each space, by key: as kept from last time until they're fetched. */
    val previews = mutableStateMapOf<String, List<Item>>()
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val thumbLoading = mutableSetOf<String>()
    private val thumbOrder = ArrayDeque<String>()

    /** Shows what was kept for each of [keys], before anything is fetched. */
    internal fun restore(keys: List<String>) = keys.forEach { k -> kept(k).get()?.let { previews[k] = it } }

    /** Asks [space] for its latest items; what it had stays when that fails. */
    internal suspend fun fetch(space: Space) {
        try {
            show(space.key, space.api.list(limit = PREVIEW).items)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** [items] as [key]'s preview, and kept for next time. The same as it shows already changes nothing. */
    internal fun show(key: String, items: List<Item>) {
        val latest = items.take(PREVIEW)
        if (previews[key] == latest) return
        previews[key] = latest
        kept(key).set(latest)
    }

    /** Stops showing [key]'s preview; [drop] forgets what was kept too, as for a space that's gone. */
    internal fun forget(key: String, drop: Boolean) {
        previews.remove(key)
        if (drop) kept(key).set(null)
    }

    fun thumb(id: String): ImageBitmap? = thumbs[id]

    /** A tile's picture of [item]: from the image cache on disk, else downloaded, unless [space] is use-limited. */
    suspend fun loadThumb(space: Space, item: Item) {
        if (item.id in thumbs || item.id in thumbLoading || (item.size ?: 0) > Model.MAX_INLINE_IMAGE) return
        thumbLoading += item.id
        try {
            // A use-limited link only shows what's already on disk: a download would spend a use.
            val bytes = imageBytes(platform, space.api, item.id, download = !space.limited) ?: return
            withContext(Dispatchers.Default) { platform.decodeImage(bytes, 480) }?.let { bmp ->
                thumbs[item.id] = bmp
                thumbOrder.addLast(item.id)
                while (thumbOrder.size > 120) thumbs.remove(thumbOrder.removeFirst()) // the disk has the rest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            thumbLoading -= item.id
        }
    }

    private fun kept(key: String) = Remembered(platform.cache, "preview/$key", ListSerializer(Item.serializer()))

    companion object {
        /** Items shown per tile: enough to fill a tall one; the oldest clip off the top. */
        const val PREVIEW = 8
    }
}
