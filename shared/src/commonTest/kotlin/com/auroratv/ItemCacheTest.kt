package com.auroratv

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A title's details are asked for once in a short while, and again as soon as something changes it. */
class ItemCacheTest {
    private fun server() = FakeServer { req ->
        val path = req.url.encodedPath
        when {
            path.startsWith("/Items/m1") -> json("""{"Id":"m1","Name":"Film","Type":"Movie","UserData":{"PlaybackPositionTicks":0}}""")
            path == "/Shows/s1/Seasons" -> json("""{"Items":[{"Id":"se1","Name":"Season 1","Type":"Season","IndexNumber":1}]}""")
            path == "/Shows/s1/Episodes" -> json("""{"Items":[{"Id":"e1","Name":"One","Type":"Episode","SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":1}]}""")
            path == "/Shows/NextUp" -> json("""{"Items":[{"Id":"e1","Name":"One","Type":"Episode","SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":1}]}""")
            else -> json("{}")
        }
    }

    private fun FakeServer.itemGets() = requests.count { it.url.encodedPath == "/Items/m1" && it.method.value == "GET" }

    @Test
    fun reopeningATitleUsesWhatWasJustFetched() = runTest {
        val s = server(); val a = api(s)
        a.item("m1"); a.item("m1"); a.item("m1")
        assertEquals(1, s.itemGets())
    }

    @Test
    fun watchingMarkingOrFavouritingFetchesItAgain() = runTest {
        val s = server(); val a = api(s)
        a.item("m1")
        a.setFavorite("m1", true)
        a.item("m1")
        assertEquals(2, s.itemGets(), "after a favourite")
        a.setPlayed("m1", true)
        a.item("m1")
        assertEquals(3, s.itemGets(), "after marking watched")
        a.invalidateArrangements() // what starting or stopping playback does
        a.item("m1")
        assertEquals(4, s.itemGets(), "after playback")
    }

    @Test
    fun reopeningAShowUsesItsListsUntilSomethingIsWatched() = runTest {
        val s = server(); val a = api(s)
        fun count(p: String) = s.requests.count { it.url.encodedPath == p }
        repeat(3) { a.seasons("s1"); a.episodes("s1", "se1"); a.seriesPlayTarget("s1") }
        assertEquals(1, count("/Shows/s1/Seasons")); assertEquals(1, count("/Shows/s1/Episodes")); assertEquals(1, count("/Shows/NextUp"))
        a.invalidateArrangements()
        a.seasons("s1"); a.episodes("s1", "se1"); a.seriesPlayTarget("s1")
        assertEquals(2, count("/Shows/s1/Seasons")); assertEquals(2, count("/Shows/s1/Episodes")); assertEquals(2, count("/Shows/NextUp"))
        // Home's own Next Up row is always asked for afresh.
        a.nextUp(); a.nextUp()
        assertEquals(4, count("/Shows/NextUp"))
    }
}
