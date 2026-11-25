package com.auroratv

import com.auroratv.jellyfin.CustomSeason
import com.auroratv.jellyfin.EpisodeOrders
import com.auroratv.jellyfin.EpisodeSplit
import com.auroratv.jellyfin.GroupEpisode
import com.auroratv.jellyfin.GroupSeason
import com.auroratv.jellyfin.OrderPlan
import com.auroratv.jellyfin.OrderSeasonIds
import com.auroratv.jellyfin.OrderStore
import com.auroratv.jellyfin.SeriesOrder
import com.auroratv.jellyfin.SourceOrder
import com.auroratv.jellyfin.SplitPart
import com.auroratv.jellyfin.SplitStore
import com.auroratv.jellyfin.TmdbEpisodeGroups
import com.auroratv.jellyfin.VirtualEpisodes
import com.auroratv.jellyfin.VirtualIds
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.profiles.LocalWatchStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Episode orders: a show listed, browsed and played in another order (a DVD or intended order from
 * TMDB, or one made by hand), across seasons, without touching ids, files or progress.
 */
class EpisodeOrderTest {
    // A small Steven-Universe-like library in aired (server) order: four episodes made for season 1
    // aired in season 2 ("Open Book", "Say Uncle", "Story for Steven", "Shirt Club").
    private val seasons = listOf(
        BaseItemDto(id = "sn1", type = "Season", name = "Season 1", indexNumber = 1, seriesId = "su"),
        BaseItemDto(id = "sn2", type = "Season", name = "Season 2", indexNumber = 2, seriesId = "su"),
    )
    private fun ep(id: String, s: Int, e: Int, name: String, played: Boolean = false, last: String? = null) = BaseItemDto(
        id = id, type = "Episode", name = name, seriesId = "su", seriesName = "Gem Town", seasonId = "sn$s",
        parentIndexNumber = s, indexNumber = e, runTimeTicks = 11 * 60_000L * 10_000,
        userData = UserItemData(played = played, lastPlayedDate = last),
    )
    private val aired = listOf(
        ep("a1", 1, 1, "Gem Glow"), ep("a2", 1, 2, "Laser Light Cannon"), ep("a3", 1, 3, "The Return"),
        ep("b1", 2, 1, "Full Disclosure"), ep("b2", 2, 2, "Open Book"), ep("b3", 2, 3, "Joy Ride"),
        ep("b4", 2, 4, "Say Uncle"), ep("b5", 2, 5, "Story for Steven"), ep("b6", 2, 6, "Shirt Club"), ep("b7", 2, 7, "Love Letters"),
    )
    // Its DVD order (as TheTVDB / TMDB document it): those four close season 1.
    private val dvd = SourceOrder("g-dvd", "DVD Order", 3, listOf(
        GroupSeason(1, "Season 1", listOf(GroupEpisode(1, 1, name = "Gem Glow"), GroupEpisode(1, 2, name = "Laser Light Cannon"), GroupEpisode(1, 3, name = "The Return"),
            GroupEpisode(2, 2, name = "Open Book"), GroupEpisode(2, 6, name = "Shirt Club"), GroupEpisode(2, 5, name = "Story for Steven"), GroupEpisode(2, 4, name = "Say Uncle"))),
        GroupSeason(2, "Season 2", listOf(GroupEpisode(2, 1, name = "Full Disclosure"), GroupEpisode(2, 3, name = "Joy Ride"), GroupEpisode(2, 7, name = "Love Letters"))),
    ))

    private fun orders(auto: Boolean = true) = EpisodeOrders(OrderStore(MemoryFileStore(), "srv"), { auto })

    @Test
    fun aDvdOrderMovesEpisodesIntoTheirSeasonKeepingIdsAndShowingNewNumbers() {
        val o = orders()
        assertNull(EpisodeOrders.verify(dvd, aired), "the DVD order fits this library")
        val a = o.arrange("su", OrderPlan.Source(dvd), seasons, aired)
        assertEquals(listOf("sn1", "sn2"), a.seasons.map { it.id }, "the server's seasons (ids, pictures) are used")
        assertEquals(listOf("Gem Glow", "Laser Light Cannon", "The Return", "Open Book", "Shirt Club", "Story for Steven", "Say Uncle"),
            a.episodesBySeason["sn1"]!!.map { it.name })
        assertEquals(listOf("Full Disclosure", "Joy Ride", "Love Letters"), a.episodesBySeason["sn2"]!!.map { it.name })
        val openBook = a.all.first { it.name == "Open Book" }
        assertEquals("b2", openBook.id, "same item: the right video plays and its progress stays attached")
        assertEquals(1, openBook.parentIndexNumber); assertEquals(4, openBook.indexNumber); assertEquals("sn1", openBook.seasonId)
        assertEquals(aired.size, a.all.size, "nothing duplicated or lost")
    }

    @Test
    fun nextEpisodeAndNextUpFollowTheOrderAcrossSeasons() {
        val o = orders()
        val lib = aired.map { e ->
            when (e.id) {
                "a1", "a2", "a3" -> e.copy(userData = UserItemData(played = true, lastPlayedDate = "2026-11-0${e.id.last()}T10:00:00Z"))
                "b2" -> e.copy(userData = UserItemData(played = true, lastPlayedDate = "2026-11-05T10:00:00Z")) // Open Book, watched last
                else -> e
            }
        }
        val a = o.arrange("su", OrderPlan.Source(dvd), seasons, lib)
        val ids = a.all.map { it.id }
        assertEquals("b4", ids[ids.indexOf("b5") + 1], "after Story for Steven comes Say Uncle")
        assertEquals("b1", ids[ids.indexOf("b4") + 1], "after Say Uncle (end of season 1) comes Full Disclosure")
        assertEquals("b6", o.nextUp(a, allowStarted = false)?.id, "Next Up after Open Book is Shirt Club, not the server's Joy Ride")
        // An episode in progress: a series' own Play resumes it; Home's Next Up leaves it to Continue Watching.
        val started = o.arrange("su", OrderPlan.Source(dvd), seasons, lib.map { if (it.id == "b6") it.copy(userData = UserItemData(playbackPositionTicks = 5L, lastPlayedDate = "2026-11-06T10:00:00Z")) else it })
        assertEquals("b6", o.nextUp(started, allowStarted = true)?.id)
        assertEquals("b5", o.nextUp(started, allowStarted = false)?.id)
        // Nothing watched: no Next Up (the show's page offers its first episode instead).
        assertNull(o.nextUp(o.arrange("su", OrderPlan.Source(dvd), seasons, aired), allowStarted = false))
    }

    @Test
    fun anOrderWithDifferentNumberingIsRejectedAndNeverApplied() {
        // Same numbers, other titles: this library numbers its episodes another way.
        val other = dvd.copy(id = "g-other", seasons = dvd.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(name = "Other ${it.episode}") }) })
        assertNotNull(EpisodeOrders.verify(other, aired))
        // Most of the library missing from the order: rejected too.
        val tiny = dvd.copy(id = "g-tiny", seasons = listOf(GroupSeason(1, "Season 1", listOf(GroupEpisode(1, 1, name = "Gem Glow")))))
        assertNotNull(EpisodeOrders.verify(tiny, aired))
        val o = orders()
        o.store.update("su") { it.copy(mode = SeriesOrder.GROUP + "g-other", details = mapOf("g-other" to other), rejected = mapOf("g-other" to "titles differ")) }
        assertNull(o.plan("su"), "a rejected order is never used, even when chosen")
    }

    @Test
    fun automaticPrefersADocumentedIntendedOrderThenDvdAndFollowsTheSetting() {
        val intended = dvd.copy(id = "g-int", name = "Intended Order", type = 1)
        val o = orders()
        o.store.update("su") { it.copy(details = mapOf(dvd.id to dvd, intended.id to intended)) }
        assertEquals("Intended order", o.plan("su")?.label)
        o.store.update("su") { it.copy(details = mapOf(dvd.id to dvd)) }
        assertEquals("DVD order", o.plan("su")?.label)
        assertNull(orders(auto = false).also { off -> off.store.update("su") { it.copy(details = mapOf(dvd.id to dvd)) } }.plan("su"), "automatic ordering switched off")
        o.store.update("su") { it.copy(mode = SeriesOrder.JELLYFIN) }
        assertNull(o.plan("su"), "Jellyfin's order chosen for this show")
        // Production order or a story-arc list is never used on its own (it isn't the intended order).
        val prod = dvd.copy(id = "g-prod", name = "Production Order", type = 6)
        val o2 = orders()
        o2.store.update("su") { it.copy(details = mapOf(prod.id to prod)) }
        assertNull(o2.plan("su"))
        assertEquals(listOf("g-int", "g-dvd"), o2.candidates(listOf(
            com.auroratv.jellyfin.SourceOrderSummary("g-prod", "Production Order", 6, 10),
            com.auroratv.jellyfin.SourceOrderSummary("g-dvd", "DVD Order", 3, 10),
            com.auroratv.jellyfin.SourceOrderSummary("g-int", "Intended Order", 1, 10),
        )).map { it.id })
    }

    @Test
    fun aCustomOrderMovesEpisodesBetweenSeasonsAndKeepsNewOnesBesideTheirNeighbours() {
        val o = orders()
        val custom = listOf(CustomSeason(1, null, listOf("a1", "b1", "a2", "a3")), CustomSeason(2, "Part Two", listOf("b2", "b3", "b4", "b5", "b6")))
        // b7 (added to the library later) isn't in the custom order: it stays after b6, its neighbour on the server.
        val a = o.arrange("su", OrderPlan.Custom(custom), seasons, aired)
        assertEquals(listOf("a1", "b1", "a2", "a3"), a.episodesBySeason["sn1"]!!.map { it.id })
        assertEquals(listOf("b2", "b3", "b4", "b5", "b6", "b7"), a.episodesBySeason["sn2"]!!.map { it.id })
        assertEquals("Part Two", a.seasons[1].name)
        assertEquals(2, a.all.first { it.id == "b1" }.indexNumber, "Full Disclosure shows as S1 E2 now")
    }

    @Test
    fun aSeasonOnlyTheOrderHasGetsItsOwnId() {
        val three = dvd.copy(seasons = dvd.seasons + GroupSeason(3, "Volume 3", listOf(GroupEpisode(2, 7, name = "Love Letters"))))
        val fixed = three.copy(seasons = three.seasons.mapIndexed { i, s -> if (i == 1) s.copy(episodes = s.episodes.dropLast(1)) else s })
        val a = orders().arrange("su", OrderPlan.Source(fixed), seasons, aired)
        val s3 = a.seasons.last()
        assertTrue(OrderSeasonIds.isVirtual(s3.id))
        assertEquals("su", OrderSeasonIds.seriesOf(s3.id))
        assertEquals("Volume 3", s3.name)
        assertEquals(listOf("b7"), a.episodesBySeason[s3.id]!!.map { it.id })
    }

    @Test
    fun splitEpisodesTakeTheirPlaceInAnOrder() {
        // One file (two episodes, split into vep~f~0 and vep~f~1) in a hand-made order made before it was split.
        val o = orders()
        val parts = listOf(
            ep(VirtualIds.of("f", 0), 1, 1, "Kite"), ep(VirtualIds.of("f", 1), 1, 2, "Rain"), ep("x3", 1, 3, "Snow"),
        )
        val a = o.arrange("su", OrderPlan.Custom(listOf(CustomSeason(1, null, listOf("x3", "f")))), seasons, parts)
        assertEquals(listOf("x3", VirtualIds.of("f", 0), VirtualIds.of("f", 1)), a.all.map { it.id })
        // By number in a source order: each part is its own episode.
        val src = dvd.copy(seasons = listOf(GroupSeason(1, "Season 1", listOf(GroupEpisode(1, 2), GroupEpisode(1, 3), GroupEpisode(1, 1)))))
        assertEquals(listOf(VirtualIds.of("f", 1), "x3", VirtualIds.of("f", 0)), o.arrange("su", OrderPlan.Source(src), seasons, parts).all.map { it.id })
    }

    @Test
    fun ordersArePersistedAndCardsElsewhereShowTheOrderedNumbers() {
        val files = MemoryFileStore()
        val o = EpisodeOrders(OrderStore(files, "srv"))
        o.store.update("su") { it.copy(mode = SeriesOrder.CUSTOM, custom = listOf(CustomSeason(1, null, listOf("b2", "a1")))) }
        val reopened = EpisodeOrders(OrderStore(files, "srv"))
        val plan = reopened.plan("su")
        assertTrue(plan is OrderPlan.Custom, "a hand-made order survives a restart")
        reopened.remember(reopened.arrange("su", plan, seasons, aired))
        val card = reopened.relabel(aired.first { it.id == "b2" })
        assertEquals(1, card.parentIndexNumber); assertEquals(1, card.indexNumber); assertEquals("b2", card.id)
        // Back to Jellyfin's order: cards show the server's numbers again.
        reopened.store.update("su") { it.copy(mode = SeriesOrder.JELLYFIN) }
        assertEquals(2, reopened.relabel(aired.first { it.id == "b2" }).parentIndexNumber)
    }

    // ---- Through the API (fake server and fake TMDB) ------------------------------------------------

    private fun itemJson(e: BaseItemDto) = """{"Id":"${e.id}","Name":"${e.name}","Type":"Episode","SeriesId":"su","SeriesName":"Gem Town","SeasonId":"${e.seasonId}",
        "ParentIndexNumber":${e.parentIndexNumber},"IndexNumber":${e.indexNumber},"RunTimeTicks":${e.runTimeTicks},"LocationType":"FileSystem",
        "UserData":{"Played":${e.userData?.played == true}${e.userData?.lastPlayedDate?.let { ",\"LastPlayedDate\":\"$it\"" } ?: ""}}}"""

    private val tmdbList = """{"id":61175,"results":[{"id":"g-dvd","name":"DVD Order","type":3,"episode_count":10,"group_count":2},
        {"id":"g-prod","name":"Production Order","type":6,"episode_count":10,"group_count":2}]}"""
    private fun tmdbGroup(): String {
        fun e(s: Int, n: Int, name: String, order: Int) = """{"id":${s * 100 + n},"name":"$name","season_number":$s,"episode_number":$n,"order":$order}"""
        val s1 = dvd.seasons[0].episodes.mapIndexed { i, g -> e(g.season, g.episode, g.name!!, i) }.joinToString(",")
        val s2 = dvd.seasons[1].episodes.mapIndexed { i, g -> e(g.season, g.episode, g.name!!, i) }.joinToString(",")
        return """{"id":"g-dvd","name":"DVD Order","type":3,"groups":[{"id":"x2","name":"Season 2","order":1,"episodes":[$s2]},{"id":"x1","name":"Season 1","order":0,"episodes":[$s1]}]}"""
    }

    private fun server(lib: List<BaseItemDto>, tmdbUp: Boolean = true) = FakeServer { req ->
        val path = req.url.encodedPath
        when {
            req.url.host == "api.themoviedb.org" && !tmdbUp -> json("{}", io.ktor.http.HttpStatusCode.ServiceUnavailable)
            req.url.host == "api.themoviedb.org" && path.endsWith("/episode_groups") -> json(tmdbList)
            req.url.host == "api.themoviedb.org" && path.endsWith("/episode_group/g-dvd") -> json(tmdbGroup())
            path.endsWith("/Seasons") -> json("""{"Items":[{"Id":"sn1","Type":"Season","Name":"Season 1","IndexNumber":1,"SeriesId":"su"},{"Id":"sn2","Type":"Season","Name":"Season 2","IndexNumber":2,"SeriesId":"su"}]}""")
            path.endsWith("/Episodes") -> {
                val season = req.url.parameters["seasonId"]
                val start = req.url.parameters["startItemId"]
                var list = lib.filter { season == null || it.seasonId == season }
                if (start != null) list = list.dropWhile { it.id != start }
                req.url.parameters["limit"]?.toIntOrNull()?.let { list = list.take(it) }
                json("""{"Items":[${list.joinToString(",") { itemJson(it) }}]}""")
            }
            path == "/Shows/NextUp" -> json("""{"Items":[${itemJson(lib.first { it.id == "b3" })}]}""")
            else -> json("{}")
        }
    }

    private val series = BaseItemDto(id = "su", type = "Series", name = "Gem Town", providerIds = mapOf("Tmdb" to "61175"))

    @Test
    fun lookingUpTmdbAppliesAVerifiedDvdOrderEverywhere() = runTest {
        val lib = aired.map { if (it.id == "b2") it.copy(userData = UserItemData(played = true, lastPlayedDate = "2026-11-05T10:00:00Z")) else it }
        val fake = server(lib)
        val a = api(fake)
        a.order = orders()
        a.orderSource = TmdbEpisodeGroups(fake.client) { "0123456789abcdef0123456789abcdef" }
        assertEquals(listOf("sn1", "sn2"), a.seasons("su").map { it.id })
        assertEquals("Full Disclosure", a.episodes("su", seasonId = "sn2").first().name, "Jellyfin's order until an order is found")

        assertTrue(a.lookUpOrders(series), "the DVD order fits and applies automatically")
        assertTrue(fake.requests.none { it.url.encodedPath.endsWith("/episode_group/g-prod") }, "a production order isn't fetched for automatic use")
        assertEquals(listOf("Gem Glow", "Laser Light Cannon", "The Return", "Open Book", "Shirt Club", "Story for Steven", "Say Uncle"),
            a.episodes("su", seasonId = "sn1").map { it.name })
        // Next Episode from the player (episodes from the playing one on) crosses into season 2 after Say Uncle.
        assertEquals(listOf("b4", "b1"), a.episodes("su", startItemId = "b4", limit = 2).map { it.id })
        // The series' Next Up follows the order (the server would say Joy Ride), and so does Home's.
        assertEquals("b6", a.nextUp(seriesId = "su").single().id)
        assertEquals("b6", a.nextUp().single().id)
        // One request for all episodes is shared by the season list and each season for a while.
        val before = fake.requests.count { it.url.encodedPath.endsWith("/Episodes") }
        a.seasons("su"); a.episodes("su", seasonId = "sn2")
        assertEquals(before, fake.requests.count { it.url.encodedPath.endsWith("/Episodes") })
        // Looked up again only after a week.
        assertFalse(a.lookUpOrders(series))
        assertEquals(2, fake.requests.count { it.url.host == "api.themoviedb.org" })
        // The key goes only to TMDB, never to the media server.
        assertTrue(fake.requests.filter { it.url.host != "api.themoviedb.org" }.none { it.url.toString().contains("0123456789abcdef") })
    }

    @Test
    fun withTmdbUnreachableTheLastKnownOrderStaysAndHandMadeOrdersStillWork() = runTest {
        val files = MemoryFileStore()
        val store = OrderStore(files, "srv")
        store.update("su") { it.copy(details = mapOf(dvd.id to dvd), sources = emptyList(), sourcesAtMs = 0) }
        val fake = server(aired, tmdbUp = false)
        val a = api(fake)
        a.order = EpisodeOrders(store)
        a.orderSource = TmdbEpisodeGroups(fake.client) { "k" }
        assertFalse(a.lookUpOrders(series, force = true), "TMDB down: nothing changes")
        assertEquals("Open Book", a.episodes("su", seasonId = "sn1")[3].name, "the DVD order found earlier is still used offline")
        // No key at all: nothing is looked up; a hand-made order works regardless.
        val b = api(server(aired))
        b.order = EpisodeOrders(OrderStore(MemoryFileStore(), "srv"))
        b.orderSource = TmdbEpisodeGroups(fake.client) { "" }
        assertFalse(b.lookUpOrders(series))
        b.setOrder("su", SeriesOrder.CUSTOM, listOf(CustomSeason(1, null, listOf("b7", "a1"))))
        assertEquals(listOf("b7", "a1", "a2", "a3"), b.episodes("su", seasonId = "sn1").map { it.id })
        b.setOrder("su", SeriesOrder.JELLYFIN, emptyList())
        assertEquals(listOf("a1", "a2", "a3"), b.episodes("su", seasonId = "sn1").map { it.id }, "reset to Jellyfin's order")
    }

    @Test
    fun theShowsPlayStartsItsNextEpisodeNotTheNewestOne() = runTest {
        // The banner shows a series by its newest episode; Play must continue the show instead.
        val lib = aired.map { if (it.id in setOf("a1", "a2")) it.copy(userData = UserItemData(played = true, lastPlayedDate = "2026-11-0${it.id.last()}T10:00:00Z")) else it }
        val fake = FakeServer { req ->
            when {
                req.url.encodedPath == "/Shows/NextUp" -> json("""{"Items":[${itemJson(lib.first { it.id == "a3" })}]}""")
                req.url.encodedPath.endsWith("/Episodes") -> json("""{"Items":[${lib.joinToString(",") { itemJson(it) }}]}""")
                else -> json("{}")
            }
        }
        val a = api(fake)
        assertEquals("a3", a.seriesPlayTarget("su")?.id)
        // Nothing watched yet: the first episode.
        val fresh = FakeServer { req ->
            when {
                req.url.encodedPath == "/Shows/NextUp" -> json("""{"Items":[]}""")
                req.url.encodedPath.endsWith("/Episodes") -> json("""{"Items":[${aired.joinToString(",") { itemJson(it) }}]}""")
                else -> json("{}")
            }
        }
        assertEquals("a1", api(fresh).seriesPlayTarget("su")?.id)
    }

    @Test
    fun theSplitStoreAndOrdersWorkTogether() = runTest {
        // A file holding episodes 2–3 of season 1, split; the DVD-like order puts its second part first.
        val files = MemoryFileStore()
        val splits = SplitStore(files, "srv")
        val combined = ep("f", 1, 2, "Two / Three").copy(indexNumberEnd = 3, runTimeTicks = 22 * 60_000L * 10_000)
        splits.put("f", EpisodeSplit(combined.runTimeTicks!!, parts = listOf(SplitPart(0, 11 * 60_000L, 2), SplitPart(11 * 60_000L, 22 * 60_000L, 3)), manual = true, count = 2))
        val lib = listOf(ep("a1", 1, 1, "One"), combined)
        val fake = FakeServer { req ->
            when {
                req.url.encodedPath.endsWith("/Seasons") -> json("""{"Items":[{"Id":"sn1","Type":"Season","Name":"Season 1","IndexNumber":1,"SeriesId":"su"}]}""")
                req.url.encodedPath.endsWith("/Episodes") -> json("""{"Items":[${lib.joinToString(",") { itemJson(it).replace("\"IndexNumber\":2,", if (it.id == "f") "\"IndexNumber\":2,\"IndexNumberEnd\":3," else "\"IndexNumber\":2,") }}]}""")
                else -> json("{}")
            }
        }
        val a = api(fake)
        a.virtual = VirtualEpisodes(splits, LocalWatchStore(files, "p1", { 1L }, base = "split-watch"))
        a.order = EpisodeOrders(OrderStore(files, "srv"))
        a.setOrder("su", SeriesOrder.CUSTOM, listOf(CustomSeason(1, null, listOf(VirtualIds.of("f", 1), "a1", VirtualIds.of("f", 0)))))
        val eps = a.episodes("su", seasonId = "sn1")
        assertEquals(listOf(VirtualIds.of("f", 1), "a1", VirtualIds.of("f", 0)), eps.map { it.id })
        // Each part still plays its own stretch of the same file.
        assertEquals(11 * 60_000L, a.virtual!!.window(VirtualIds.of("f", 1))!!.startMs)
        assertEquals(0L, a.virtual!!.window(VirtualIds.of("f", 0))!!.startMs)
    }
}
