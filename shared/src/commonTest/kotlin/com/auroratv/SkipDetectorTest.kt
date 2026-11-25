package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.ChapterInfo
import com.auroratv.playback.SkipDetector
import com.auroratv.playback.SkipRange
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipDetectorTest {
    private fun s(sec: Int) = sec * 10_000_000L
    private fun chapters(vararg starts: Int) = starts.mapIndexed { i, t -> ChapterInfo(s(t), "Chapter ${i + 1}") }

    @Test
    fun recurringChapterLengthsBecomeOpeningAndEnding() {
        // Cold open, 90 s OP, part A, part B, 89 s ED, preview. Other episodes: different cold-open length.
        val mine = chapters(0, 75, 165, 700, 1290, 1379)
        val ep2 = chapters(0, 120, 210, 760, 1300, 1389) to 1_440_000L
        val ep3 = chapters(0, 40, 130, 650, 1288, 1377) to 1_440_000L
        val ranges = SkipDetector.recurringChapterRanges(mine, 1_440_000L, listOf(ep2, ep3))
        assertEquals(listOf(SkipRange(SkipRange.Kind.Intro, 75_000, 165_000), SkipRange(SkipRange.Kind.Credits, 1_290_000, 1_379_000)), ranges)
    }

    @Test
    fun periodicOrUnmatchedChaptersAreIgnored() {
        // Auto-generated chapters every 60 s: equal lengths mean nothing.
        val periodic = chapters(*IntArray(24) { it * 60 })
        assertTrue(SkipDetector.recurringChapterRanges(periodic, 1_440_000L, listOf(periodic to 1_440_000L)).isEmpty())
        // No other episode shares the lengths.
        val mine = chapters(0, 75, 165, 700, 1290, 1379)
        val other = chapters(0, 300, 800, 1200) to 1_440_000L
        assertTrue(SkipDetector.recurringChapterRanges(mine, 1_440_000L, listOf(other)).isEmpty())
    }

    @Test
    fun aniSkipFillsMissingMarkersForAnimeWithoutLeakingTheToken() = runTest {
        val server = FakeServer { req ->
            val url = req.url.toString()
            when {
                req.url.host == "graphql.anilist.co" -> {
                    val body = (req.body as TextContent).text
                    if ("\"search\":\"Skylark Isles\"" in body) json("""{"data":{"Media":{"id":100,"idMal":4000,"format":"TV","episodes":12,"startDate":{"year":2024},
                        "title":{"romaji":"Skylark Isles","english":null,"native":null},"synonyms":[],
                        "relations":{"edges":[{"relationType":"PREQUEL","node":{"id":50,"type":"ANIME","format":"MOVIE"}},
                                              {"relationType":"SEQUEL","node":{"id":200,"idMal":5000,"type":"ANIME","format":"TV","episodes":12}}]}}}}""")
                    else if ("\"id\":200" in body) json("""{"data":{"Media":{"id":200,"idMal":5000,"format":"TV","episodes":12,"title":{"romaji":"Skylark Isles 2nd Season"},"relations":{"edges":[]}}}}""")
                    else json("""{"data":{"Media":null}}""")
                }
                req.url.host == "api.aniskip.com" -> {
                    assertTrue(url.startsWith("https://api.aniskip.com/v2/skip-times/5000/3?"), url)
                    assertEquals(listOf("op", "ed", "mixed-op", "mixed-ed", "recap"), req.url.parameters.getAll("types"))
                    json("""{"found":true,"results":[{"interval":{"startTime":88.5,"endTime":178.5},"skipType":"op","skipId":"a","episodeLength":1420.0},
                        {"interval":{"startTime":1310.0,"endTime":1400.0},"skipType":"ed","skipId":"b","episodeLength":1420.0}],"message":"ok","statusCode":200}""")
                }
                url.contains("/Items/series1") -> json("""{"Id":"series1","Name":"Skylark Isles","Type":"Series","Genres":["Anime"]}""")
                url.contains("/Items/season2") -> json("""{"Id":"season2","Name":"Season 2","Type":"Season","IndexNumber":2}""")
                url.contains("/Shows/series1/Episodes") -> json("""{"Items":[],"TotalRecordCount":0}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val detector = SkipDetector(api(server), server.client) { true }
        val ep = BaseItemDto(id = "ep3", name = "Third", type = "Episode", seriesId = "series1", seasonId = "season2",
            indexNumber = 3, parentIndexNumber = 2, runTimeTicks = s(1420))
        val result = assertNotNull(detector.detect(ep, emptySet()))
        assertEquals("AniSkip", result.source)
        assertEquals(listOf(SkipRange(SkipRange.Kind.Intro, 88_500, 178_500), SkipRange(SkipRange.Kind.Credits, 1_310_000, 1_400_000)), result.ranges)
        // The Jellyfin token only ever goes to the Jellyfin server.
        server.requests.filter { it.url.host != "jf.local" }.forEach { r ->
            assertNull(r.headers["X-Emby-Token"], r.url.toString())
            assertTrue(r.headers[HttpHeaders.Authorization].orEmpty().isEmpty(), r.url.toString())
        }
        // Only kinds the server lacks are filled.
        val creditsOnly = assertNotNull(SkipDetector(api(server), server.client) { true }.detect(ep, setOf(SkipRange.Kind.Intro)))
        assertEquals(listOf(SkipRange.Kind.Credits), creditsOnly.ranges.map { it.kind })
        // Online lookups off: nothing leaves the server.
        val before = server.requests.size
        assertNull(SkipDetector(api(server), server.client) { false }.detect(ep, emptySet()))
        assertTrue(server.requests.drop(before).none { it.url.host != "jf.local" })
    }

    @Test
    fun aniListSearchMustMatchTheSeries() = runTest {
        val server = FakeServer { req ->
            when {
                req.url.host == "graphql.anilist.co" -> json("""{"data":{"Media":{"id":9,"idMal":9,"episodes":24,"startDate":{"year":1999},"title":{"romaji":"Something Else Entirely"},"relations":{"edges":[]}}}}""")
                req.url.host == "api.aniskip.com" -> error("must not query AniSkip for an unmatched show")
                req.url.toString().contains("/Items/series1") -> json("""{"Id":"series1","Name":"Skylark Isles","Type":"Series","Genres":["Anime"],"ProductionYear":2024}""")
                req.url.toString().contains("/Shows/") -> json("""{"Items":[],"TotalRecordCount":0}""")
                else -> json("""{"Id":"season1","Type":"Season"}""")
            }
        }
        val ep = BaseItemDto(id = "ep1", type = "Episode", seriesId = "series1", seasonId = "season1", indexNumber = 1, parentIndexNumber = 1, runTimeTicks = s(1420))
        assertNull(SkipDetector(api(server), server.client) { true }.detect(ep, emptySet()))
        assertTrue(server.requests.none { it.url.host == "api.aniskip.com" })
    }

    @Test
    fun nonAnimeShowsNeverGoOnline() = runTest {
        val server = FakeServer { req ->
            when {
                req.url.host != "jf.local" -> error("no online lookup for non-anime")
                req.url.toString().contains("/Items/series1") -> json("""{"Id":"series1","Name":"Harbor Lights","Type":"Series","Genres":["Drama"]}""")
                req.url.toString().contains("/Shows/") -> json("""{"Items":[],"TotalRecordCount":0}""")
                else -> json("""{"Id":"x","Type":"Season"}""")
            }
        }
        val ep = BaseItemDto(id = "ep1", type = "Episode", seriesId = "series1", seasonId = "season1", indexNumber = 1, parentIndexNumber = 1, runTimeTicks = s(2600))
        assertNull(SkipDetector(api(server), server.client) { true }.detect(ep, emptySet()))
        assertTrue(server.requests.all { it.url.host == "jf.local" })
    }
}
