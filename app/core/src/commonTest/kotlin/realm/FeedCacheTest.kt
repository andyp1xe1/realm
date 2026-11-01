package realm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedCacheTest {
    private val store = MapPrefs()
    private fun item(n: Int) = Item("id" + n.toString().padStart(3, '0'), "note", "note $n", created = 1_000L - n)

    @Test
    fun theFirstPageComesBackInTheNextRun() {
        val page = Page(listOf(item(1), Item("f", "file", null, "a.pdf", "application/pdf", 2_400, "cli", 7)), next = "f")
        FeedCache(store, "realm:main").keepFirst(page)
        assertEquals(page, FeedCache(store, "realm:main").first())
        assertNull(FeedCache(store, "realm:trip").first()) // each space its own
    }

    @Test
    fun anEmptyRealmIsKeptAsEmptyNotAsUnknown() {
        FeedCache(store, "realm:new").keepFirst(Page(emptyList()))
        assertEquals(Page(emptyList()), FeedCache(store, "realm:new").first())
    }

    @Test
    fun aChunkHoldsAtMostAPageAndPointsPastItsLastItem() {
        val many = (1..60).map(::item)
        FeedCache(store, "realm:main").keepFirst(Page(many, next = "id060"))
        val kept = FeedCache(store, "realm:main").first()!!
        assertEquals(many.take(FeedCache.PAGE), kept.items)
        assertEquals("id050", kept.next) // the next page starts after the 50th, not after the 60th
    }

    @Test
    fun forgettingDropsEveryChunkOfThatSpaceOnly() {
        FeedCache(store, "realm:main").keepFirst(Page(listOf(item(1))))
        FeedCache(store, "realm:trip").keepFirst(Page(listOf(item(2))))
        store.set("feed/realm:main/1", """{"items":[]}""") // a later chunk, as a newer app may keep
        FeedCache(store, "realm:main").forget()
        assertNull(FeedCache(store, "realm:main").first())
        assertTrue(store.map.keys.none { it.startsWith("feed/realm:main/") })
        assertEquals(listOf(item(2)), FeedCache(store, "realm:trip").first()?.items)
    }
}
