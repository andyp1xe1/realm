// A space's feed as last fetched, kept between runs so it opens at once on what it showed, then refreshes in
// place. Item metadata only (what the server lists: id, kind, text, name, size and the like), never a file's or
// an image's bytes: images load on demand, through the image cache, as they always have.
package realm

/**
 * The feed of [space] (a key like "realm:main"), kept in [store] in chunks of up to [PAGE] items, newest first:
 * "feed/<space>/0" is the first, and each holds the cursor the server gave for the next. Only the first chunk is
 * kept for now; more can follow under the next numbers without changing what's kept.
 */
class FeedCache(private val store: Prefs, private val space: String) {
    /** The first chunk as kept; null when none is, or it no longer reads. */
    fun first(): Page? = chunk(0).get()

    /** Keeps [page] as the first chunk: its first [PAGE] items, with a cursor past the last one kept. */
    fun keepFirst(page: Page) = chunk(0).set(capped(page))

    /** Forgets every chunk kept, as on signing out or when the realm is deleted. */
    fun forget() {
        var n = 0
        while (store.get(key(n)) != null) store.set(key(n++), null)
    }

    private fun chunk(n: Int) = Remembered(store, key(n), Page.serializer())

    private fun key(n: Int) = "feed/$space/$n"

    companion object {
        /** The most items a chunk holds, and the feed asks for in its first page. */
        const val PAGE = 50

        /** [page] cut to [PAGE] items; the server's cursor is the last item's id, so the next page starts after it. */
        private fun capped(page: Page) = if (page.items.size <= PAGE) page else Page(page.items.take(PAGE), page.items[PAGE - 1].id)
    }
}
