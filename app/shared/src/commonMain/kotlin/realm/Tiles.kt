package realm

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

class Tiles internal constructor(private val platform: Platform) {
    /** The latest few items of each space, by key; as kept from last time until they're fetched. */
    val previews = mutableStateMapOf<String, List<Item>>()
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val thumbLoading = mutableSetOf<String>()
    private val thumbOrder = ArrayDeque<String>()

    internal fun restore(keys: List<String>) = keys.forEach { k -> kept(k).get()?.let { previews[k] = it } }

    internal suspend fun fetch(space: Space) {
        try {
            show(space.key, space.api.list(limit = PREVIEW).items)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    internal fun show(key: String, items: List<Item>) {
        val latest = items.take(PREVIEW)
        if (previews[key] == latest) return
        previews[key] = latest
        kept(key).set(latest)
    }

    internal fun forget(key: String, drop: Boolean) {
        previews.remove(key)
        if (drop) kept(key).set(null)
    }

    fun thumb(id: String): ImageBitmap? = thumbs[id]

    /** Loads a tile's picture of [item] from the disk cache, else downloads it unless [space] is use-limited. */
    suspend fun loadThumb(space: Space, item: Item) {
        if (item.id in thumbs || item.id in thumbLoading || (item.size ?: 0) > Model.MAX_INLINE_IMAGE) return
        thumbLoading += item.id
        try {
            val bytes = imageBytes(platform, space.api, item.id, download = !space.limited) ?: return
            withContext(Dispatchers.Default) { platform.decodeImage(bytes, 480) }?.let { bmp ->
                thumbs[item.id] = bmp
                thumbOrder.addLast(item.id)
                while (thumbOrder.size > 120) thumbs.remove(thumbOrder.removeFirst())
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
        /** Items shown per tile. */
        const val PREVIEW = 8
    }
}
