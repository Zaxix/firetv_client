package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.profiles.LocalWatchStore
import com.auroratv.recommend.HouseholdTop10
import com.auroratv.recommend.HouseholdWatch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Top 10 in Your Home": most watched by this device's profiles on the server. */
class HouseholdTop10Test {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day
    private val hour = 3600L * 10_000_000

    @Test
    fun ranksByTimeWatchedLatelyThenAllTime() {
        val w = listOf(
            HouseholdWatch("film-a", 2 * hour, now - 1 * day),
            HouseholdWatch("show-b", 1 * hour, now - 2 * day), HouseholdWatch("show-b", 2 * hour, now - 3 * day), // 3 h lately
            HouseholdWatch("film-c", 2 * hour, now - 5 * day), // same time as film-a, watched longer ago
            HouseholdWatch("show-old", 40 * hour, now - 200 * day), // lots, but months ago
            HouseholdWatch("nothing", 0, now),
        )
        assertEquals(listOf("show-b", "film-a", "film-c", "show-old"), HouseholdTop10.rank(w, now))
        assertEquals(2, HouseholdTop10.rank(w, now, limit = 2).size)
    }

    @Test
    fun readsServerAndDeviceHistories() {
        fun item(id: String, type: String, series: String? = null, played: Boolean, count: Int = 0, pos: Long = 0, date: String? = "2026-11-05T10:00:00.0000000Z") =
            BaseItemDto(id = id, type = type, seriesId = series, runTimeTicks = hour, userData = UserItemData(played = played, playCount = count, playbackPositionTicks = pos, lastPlayedDate = date))
        val server = HouseholdTop10.fromServer(listOf(
            item("m1", "Movie", played = true, count = 2),
            item("e1", "Episode", series = "s1", played = false, pos = hour / 2),
            item("x", "MusicVideo", played = true),
            item("m2", "Movie", played = true, date = null),
        ))
        assertEquals(listOf("m1" to 2 * hour, "s1" to hour / 2), server.map { it.titleId to it.ticks }, "plays count per play; episodes count for their show")
        assertEquals(HouseholdTop10.parseIsoMs("2026-11-05T10:00:00Z"), server[0].atMs)
        assertNull(HouseholdTop10.parseIsoMs("not a date"))

        val files = MemoryFileStore()
        val local = LocalWatchStore(files, "kid", { now })
        local.userData(BaseItemDto(id = "e9", type = "Episode", seriesId = "s9", runTimeTicks = hour))
        local.progress("e9", hour / 4, stopped = true)
        local.userData(BaseItemDto(id = "m9", type = "Movie", runTimeTicks = hour))
        local.progress("m9", hour, stopped = true)
        val fromDevice = HouseholdTop10.fromLocal(local.history()).associate { it.titleId to it.ticks }
        assertEquals(mapOf("s9" to hour / 4, "m9" to hour), fromDevice)
    }

    private fun entry(id: String, type: String, series: String? = null, played: Boolean = true, date: String = "2026-11-05T10:00:00Z") =
        """{"Id":"$id","Name":"$id","Type":"$type"${series?.let { ",\"SeriesId\":\"$it\"" } ?: ""},"RunTimeTicks":$hour,"UserData":{"Played":$played,"PlayCount":1,"LastPlayedDate":"$date"}}"""

    @Test
    fun combinesProfilesAndShowsOnlyWhatTheViewerMaySee() = runTest {
        val recent = "2026-11-20T10:00:00Z"
        val histories = mapOf(
            "dad" to listOf(entry("film-a", "Movie", date = recent), entry("e1", "Episode", "show-b", date = recent), entry("e2", "Episode", "show-b", date = recent)),
            "mum" to listOf(entry("e3", "Episode", "show-b", date = recent), entry("film-r", "Movie", date = recent)),
        )
        val fake = FakeServer { req ->
            val ids = req.url.parameters["ids"]
            when {
                // The viewer (a child's profile) can't see the R-rated film: the server leaves it out.
                ids != null -> json("""{"Items":[${ids.split(',').filter { it != "film-r" }.joinToString(",") { """{"Id":"$it","Name":"$it","Type":"${if (it.startsWith("show")) "Series" else "Movie"}"}""" }}]}""")
                req.url.encodedPath == "/Items" -> json("""{"Items":[${histories[req.url.parameters["userId"]].orEmpty().joinToString(",")}]}""")
                else -> json("{}")
            }
        }
        val today = HouseholdTop10.parseIsoMs("2026-11-21T00:00:00Z")!!
        // A device-only profile watched film-c three days ago.
        val local = LocalWatchStore(MemoryFileStore(), "kid", { today - 3 * day })
        local.userData(BaseItemDto(id = "film-c", type = "Movie", runTimeTicks = hour)); local.progress("film-c", hour, stopped = true)
        val top = HouseholdTop10 { today }
        val viewer = api(fake, userId = "kid")
        val got = top.top(viewer, "srv", listOf(api(fake, userId = "dad"), api(fake, userId = "mum")), listOf(local))
        assertEquals(listOf("show-b", "film-a", "film-c"), got.map { it.id }, "show-b: 3 h from two profiles; film-r hidden from this viewer")
        // Worked out once, then reused for a while (no more history requests).
        val before = fake.requests.count { it.url.parameters["sortBy"] == "DatePlayed" }
        top.top(viewer, "srv", listOf(api(fake, userId = "dad"), api(fake, userId = "mum")), listOf(local))
        assertEquals(before, fake.requests.count { it.url.parameters["sortBy"] == "DatePlayed" })
        // Too little watched in the house: no row.
        val empty = HouseholdTop10 { today }
        assertTrue(empty.top(viewer, "srv", emptyList(), emptyList()).isEmpty())
    }
}
