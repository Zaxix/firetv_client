package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ratings.RatingsKeyStore
import com.auroratv.ratings.RatingsKeys
import com.auroratv.ratings.RatingsService
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingsTest {
    private val movie = BaseItemDto(id = "m1", name = "Heat", type = "Movie", criticRating = 83.0, providerIds = mapOf("Tmdb" to "949", "Imdb" to "tt0113277"))

    private val mdbJson = """
        {"title":"Heat","type":"movie","ratings":[
          {"source":"imdb","value":8.3,"score":83,"votes":745123},
          {"source":"metacritic","value":76,"score":76,"votes":null},
          {"source":"tomatoes","value":88,"score":88,"votes":null},
          {"source":"popcorn","value":94,"score":94,"votes":250000},
          {"source":"letterboxd","value":4.1,"score":82},
          {"source":"rogerebert","value":null,"score":null}
        ]}
    """.trimIndent()

    private val traktJson = """
        [
          {"id":1,"comment":"An absolute masterclass in tension; the diner scene alone is worth the price of admission.","spoiler":false,"review":false,"likes":120,"created_at":"2021-03-01T10:00:00.000Z","user_stats":{"rating":9},"user":{"username":"dana","name":"Dana R","private":false}},
          {"id":2,"comment":"The ending where the hero dies at the airport is heartbreaking and perfectly shot.","spoiler":true,"review":false,"likes":500,"user":{"username":"x"}},
          {"id":3,"comment":"Great movie but [spoiler]the bank job fails[/spoiler] and that changes everything.","spoiler":false,"review":false,"likes":300,"user":{"username":"y"}},
          {"id":4,"comment":"Too short.","spoiler":false,"review":false,"likes":900,"user":{"username":"z"}},
          {"id":5,"comment":"${"A long and thoughtful review of the craft, the score and the performances. ".repeat(6)}","spoiler":false,"review":true,"likes":3,"created_at":"2020-01-02T00:00:00.000Z","user_rating":8,"user":{"username":"critic99","name":"Hidden Name","private":true}}
        ]
    """.trimIndent()

    @Test
    fun mdbListParsing() {
        val r = RatingsService.parseMdbList(mdbJson)
        assertEquals(88, r.tomatometer); assertEquals(94, r.popcornmeter)
        assertEquals(8.3, r.imdb); assertEquals(745123, r.imdbVotes)
        assertEquals(76, r.metacritic); assertEquals(4.1, r.letterboxd)
    }

    @Test
    fun traktReviewsAreSpoilerFreeAndCarryTheReviewersScore() {
        val reviews = RatingsService.parseTraktComments(traktJson)
        assertEquals(listOf(5L, 1L), reviews.map { it.id }, "spoiler-flagged, spoiler-tagged and one-line comments dropped; long reviews first")
        val long = reviews.first()
        assertEquals(8, long.rating, "legacy user_rating")
        assertEquals("critic99", long.author, "private profiles show the username, not the real name")
        assertTrue(long.isLongReview)
        val short = reviews[1]
        assertEquals(9, short.rating, "user_stats.rating")
        assertEquals("Dana R", short.author)
        assertEquals("2021-03-01", short.date)
    }

    @Test
    fun withoutKeysOnlyTheServersCriticScoreIsShownAndNothingGoesOnline() = runTest {
        val server = FakeServer { json("{}") }
        val service = RatingsService(server.client) { RatingsKeys() }
        val r = service.ratings(movie)
        assertEquals(83, r?.tomatometer); assertNull(r?.popcornmeter)
        assertTrue(service.reviews(movie).isEmpty())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun keysOnlyGoToTheirOwnServiceAndResultsAreCached() = runTest {
        val server = FakeServer { req ->
            when (req.url.host) {
                "api.mdblist.com" -> json(mdbJson)
                "api.trakt.tv" -> json(traktJson)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val service = RatingsService(server.client) { RatingsKeys(mdblist = "mdb-secret", trakt = "trakt-client") }
        val r = service.ratings(movie)!!
        assertEquals(94, r.popcornmeter)
        assertEquals(2, service.reviews(movie).size)
        service.ratings(movie); service.reviews(movie)
        assertEquals(2, server.requests.size, "second look-ups come from the cache")
        val mdb = server.requests.first { it.url.host == "api.mdblist.com" }
        assertEquals("/tmdb/movie/949", mdb.url.encodedPath)
        assertEquals("mdb-secret", mdb.url.parameters["apikey"])
        assertNull(mdb.headers["trakt-api-key"])
        val trakt = server.requests.first { it.url.host == "api.trakt.tv" }
        assertEquals("/movies/tt0113277/comments/likes", trakt.url.encodedPath)
        assertEquals("trakt-client", trakt.headers["trakt-api-key"])
        assertEquals("2", trakt.headers["trakt-api-version"])
        assertNull(trakt.url.parameters["apikey"])
        assertTrue(server.requests.none { it.headers["Authorization"] != null }, "no Jellyfin credentials leave for third parties")
    }

    @Test
    fun failuresFallBackToTheServerScore() = runTest {
        val server = FakeServer { json("{\"error\":\"bad key\"}", HttpStatusCode.Unauthorized) }
        val service = RatingsService(server.client) { RatingsKeys(mdblist = "bad") }
        assertEquals(83, service.ratings(movie)?.tomatometer)
        service.ratings(movie)
        assertEquals(1, server.requests.size, "a failed look-up isn't retried immediately")
    }

    @Test
    fun keysRoundTripThroughTheJellyfinProfile() = runTest {
        var stored = """{"Id":"aurora-ratings","SortBy":"SortName","CustomPrefs":{"other":"keep"},"Client":"AuroraTV"}"""
        val server = FakeServer { req ->
            if (req.method.value == "POST") {
                stored = (req.body as io.ktor.http.content.TextContent).text
                json("")
            } else json(stored)
        }
        val tvVault = MemoryVault(); val pcVault = MemoryVault()
        val pc = RatingsKeyStore(pcVault)
        pc.save(RatingsKeys("m-key", "t-key"))
        pc.push(api(server), pc.keys.value)
        assertTrue(stored.contains("\"other\":\"keep\""), "other custom prefs survive")
        val tv = RatingsKeyStore(tvVault)
        tv.pull(api(server))
        assertEquals(RatingsKeys("m-key", "t-key"), tv.keys.value)
        assertEquals("m-key", tvVault.data["ratings:mdblist"], "stored in the secure vault")
        val post = server.requests.first { it.method.value == "POST" }
        assertEquals("/DisplayPreferences/aurora-ratings", post.url.encodedPath)
        assertEquals("AuroraTV", post.url.parameters["client"])
    }
}
