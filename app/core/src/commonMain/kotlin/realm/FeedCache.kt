package realm

/** The feed of [space] (a key like "realm:main") as last fetched, item metadata only, kept in [store]. */
class FeedCache(private val store: Prefs, private val space: String) {
    /** The first chunk as kept; null when none is, or it no longer reads. */
    fun first(): Page? = chunk(0).get()

    /** Keeps [page]'s first [PAGE] items, with a cursor past the last one kept. */
    fun keepFirst(page: Page) = chunk(0).set(capped(page))

    fun forget() {
        var n = 0
        while (store.get(key(n)) != null) store.set(key(n++), null)
    }

    private fun chunk(n: Int) = Remembered(store, key(n), Page.serializer())

    private fun key(n: Int) = "feed/$space/$n"

    companion object {
        /** The most items a chunk holds, and the feed asks for in its first page. */
        const val PAGE = 50

        private fun capped(page: Page) = if (page.items.size <= PAGE) page else Page(page.items.take(PAGE), page.items[PAGE - 1].id)
    }
}
